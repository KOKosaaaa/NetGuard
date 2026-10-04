package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.view.Choreographer
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.navigation.fragment.NavHostFragment
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.model.*
import com.smarttools.netguard.service.TunnelVpnService
import com.smarttools.netguard.util.SpeedTester
import com.smarttools.netguard.util.GeoLookup
import com.smarttools.netguard.util.TrafficFormatter
import com.smarttools.netguard.repository.StatsRepository
import com.smarttools.netguard.widget.TrafficChartView
import com.smarttools.netguard.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owned-emulator UI fixture. Never starts a VPN or contacts a public speed endpoint. */
internal class HomeDesignAndroidProbe(private val test: Instrumentation) {
    private val app get() = test.targetContext.applicationContext as App
    private val geometryNotes = linkedSetOf<String>()
    private var currentActivity: MainActivity? = null
    private fun onUi(block: () -> Unit) {
        var error: Throwable? = null
        test.runOnMainSync { try { block() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
    }
    private fun waitUi(message: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + 8_000_000_000L
        var ok = false
        while (!ok && System.nanoTime() < until) {
            onUi { ok = predicate() }
            if (!ok) Thread.sleep(40)
        }
        check(ok) { message }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(owner: Any?, type: Class<*>, name: String): MutableStateFlow<T> =
        type.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>
    private fun <T> state(vm: MainViewModel, name: String, value: T) {
        flow<T>(vm, MainViewModel::class.java, name).value = value
    }
    private fun view(a: Activity, name: String): View = a.findViewById(
        a.resources.getIdentifier(name, "id", a.packageName).also { check(it != 0) { name } })
    private fun ownWindow() {
        val until = System.nanoTime() + 8_000_000_000L
        var actual: String? = null
        while (System.nanoTime() < until) {
            actual = test.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (actual == test.targetContext.packageName) return
            Thread.sleep(60)
        }
        error("Home obscured by $actual")
    }
    private fun screenshot(name: String) {
        ownWindow()
        renderedFrames()
        val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
        try { File(test.targetContext.getExternalFilesDir(null), "home-$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
    private fun renderedFrames() {
        val done = CountDownLatch(1)
        onUi {
            val decor = currentActivity?.window?.decorView ?: error("No Home activity for capture")
            fun next(left: Int) {
                decor.postInvalidateOnAnimation()
                Choreographer.getInstance().postFrameCallback {
                    if (left == 1) done.countDown() else next(left - 1)
                }
            }
            next(3)
        }
        check(done.await(3, TimeUnit.SECONDS)) { "Home stopped rendering before screenshot" }
        // Choreographer callbacks precede rendering; allow the last submitted buffer to be composed.
        Thread.sleep(120)
    }
    private fun revealTop(a: Activity) {
        repeat(3) {
            test.waitForIdleSync()
            onUi { (view(a, "home_scroll") as NestedScrollView).scrollTo(0, 0) }
            renderedFrames()
            var atTop = false
            onUi { atTop = (view(a, "home_scroll") as NestedScrollView).scrollY == 0 }
            if (atTop) return
        }
        error("Deferred layout/focus prevents top-of-Home capture")
    }
    private fun assertSystemBars(a: Activity) {
        onUi {
            val value = android.util.TypedValue()
            check(a.theme.resolveAttribute(android.R.attr.colorBackground, value, true))
            val background = value.data
            val surface = com.google.android.material.color.MaterialColors.getColor(
                a, com.google.android.material.R.attr.colorSurface, "Home QA")
            val bars = androidx.core.view.WindowInsetsControllerCompat(a.window, a.window.decorView)
            check(bars.isAppearanceLightStatusBars == (androidx.core.graphics.ColorUtils.calculateLuminance(background) > 0.5)) {
                "Status icons unreadable on resolved background #${Integer.toHexString(background)}"
            }
            check(bars.isAppearanceLightNavigationBars == (androidx.core.graphics.ColorUtils.calculateLuminance(surface) > 0.5)) {
                "Navigation icons mismatch resolved surface #${Integer.toHexString(surface)}"
            }
        }
    }
    private fun reveal(a: Activity, name: String) {
        onUi {
            val scroll = view(a, "home_scroll") as NestedScrollView
            val target = view(a, name)
            check(target.isShown) { "$name not shown" }
            val rect = Rect(0, 0, target.width, target.height)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            scroll.scrollTo(0, (rect.top - scroll.paddingTop).coerceAtLeast(0))
        }
        test.waitForIdleSync()
        onUi {
            val target = view(a, name); val visible = Rect()
            check(target.getGlobalVisibleRect(visible) && visible.width() >= target.width - 1 && visible.height() >= target.height - 1) {
                "$name cannot be fully reached by scrolling: $visible vs ${target.width}x${target.height}"
            }
        }
        ownWindow()
    }
    private fun click(a: Activity, name: String) {
        reveal(a, name)
        onUi { val v = view(a, name); check(v.isEnabled && v.performClick()) { "$name disabled" } }
    }
    private fun label(v: View): String = if (v.id == View.NO_ID) v.javaClass.simpleName else
        runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault(v.javaClass.simpleName)
    private fun restorePrefs(prefs: SharedPreferences, saved: Map<String, *>) {
        prefs.edit().clear().also { edit -> saved.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
        } } }.commit()
    }
    private fun assertGeometry(a: Activity) {
        test.waitForIdleSync()
        waitUi("Home still waiting for text measurement/layout") {
            !view(a, "home_scroll").isLayoutRequested
        }
        ownWindow()
        onUi {
            fun visit(v: View) {
                if (v.visibility != View.VISIBLE) return
                check(v.width > 0 && v.height > 0) { "Empty ${label(v)}" }
                if (v is TextView && v.text.isNotEmpty()) {
                    val layout = checkNotNull(v.layout) { "Unlaid text ${label(v)}" }
                    val explicitProfileEllipsis = v.id == R.id.tv_profile_name
                    if (!explicitProfileEllipsis) {
                        check((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 }) { "Ellipsized ${label(v)}: ${v.text}" }
                        check(layout.height <= v.height - v.compoundPaddingTop - v.compoundPaddingBottom + 2) {
                            "Vertical clipping ${label(v)} layout=${layout.height} view=${v.height} padding=${v.compoundPaddingTop + v.compoundPaddingBottom}"
                        }
                    }
                    assertTextWidth(v)
                }
                if (v is ViewGroup) {
                    val children = (0 until v.childCount).map(v::getChildAt).filter { it.visibility == View.VISIBLE }
                    // FrameLayout intentionally overlays a busy spinner; only linear siblings must be disjoint.
                    if (v is LinearLayout) children.zipWithNext().forEach { (first, second) ->
                        check(if (v.orientation == LinearLayout.VERTICAL) first.bottom <= second.top + 1 else first.right <= second.left + 1) {
                            "Overlapping ${label(first)} / ${label(second)} in ${label(v)}"
                        }
                    }
                    children.forEach { child ->
                        check(child.left >= -1 && child.right <= v.width + 1) { "Horizontal overflow ${label(child)} in ${label(v)}" }
                        visit(child)
                    }
                }
            }
            visit((view(a, "home_scroll") as ViewGroup).getChildAt(0))
        }
    }
    private fun assertTextWidth(v: TextView) {
        val layout = checkNotNull(v.layout)
        val available = v.width - v.compoundPaddingLeft - v.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            val raw = layout.getLineWidth(line)
            // Android Layout.getLineMax excludes invisible trailing whitespace on soft wraps.
            // Compare against the actual viewport, not a potentially huge singleLine layout width.
            val visible = layout.getLineMax(line)
            check(visible <= available + 2) {
                "Horizontal clipping ${label(v)} line=$line raw=$raw max=$visible available=$available layout=${layout.width}: ${v.text}"
            }
            if (raw > available + 2 && geometryNotes.size < 64) geometryNotes.add(
                "SOFT_WRAP ${label(v)} line=$line raw=$raw max=$visible available=$available text=${v.text}")
        }
    }
    private fun assertGeometryOracle(a: Activity) {
        onUi {
            fun measured(text: String, singleLine: Boolean): TextView = TextView(a).apply {
                setPadding(0, 0, 0, 0)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32f)
                typeface = android.graphics.Typeface.MONOSPACE
                this.text = text
                if (singleLine) setSingleLine(true)
                val width = kotlin.math.ceil(paint.measureText("AA").toDouble()).toInt() + 1
                measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
                layout(0, 0, measuredWidth, measuredHeight)
            }
            val wrapped = measured("AA AA AA", false)
            check((0 until wrapped.layout.lineCount).any { wrapped.layout.getLineWidth(it) > wrapped.width + 2 }) {
                "Soft-wrap fixture did not exercise a trailing space beyond the visible line"
            }
            assertTextWidth(wrapped)
            val clipped = measured("AAAAAAAAAAAA", true)
            check(runCatching { assertTextWidth(clipped) }.isFailure) {
                "Negative control: real single-line glyph clipping passed the geometry gate"
            }
        }
    }
    private fun denseChart(a: Activity, theme: ThemeMode) {
        val names = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
        for (peak in listOf(0, 6)) {
            val rows = names.mapIndexed { i, day -> StatsRepository.DayTraffic("10/${i + 1}", day,
                (if (i == peak) 15L else i + 3L) * 1_048_576, 131_072) }
            onUi {
                val chart = view(a, "traffic_chart") as TrafficChartView
                chart.setData(rows)
                check(chart.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES)
                val description = chart.contentDescription.toString()
                rows.forEach { row -> check(description.contains("${row.dayOfWeek}: ${TrafficFormatter.formatBytes(row.total)}")) {
                    "Chart accessibility lost day/value ${row.dayOfWeek}: $description"
                } }
            }
            test.waitForIdleSync()
            reveal(a, "traffic_chart"); assertGeometry(a)
            screenshot("${theme.name.lowercase()}-dense-peak-$peak")
        }
    }

    /** Accepts the actual WB no-auth SOCKS greeting, then withholds its reply indefinitely. */
    private class StalledSocks(port: Int) : AutoCloseable {
        val greeting = CountDownLatch(1)
        val eof = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        private val accepted = AtomicReference<Socket?>()
        private val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 8_000 }
        private val thread = Thread {
            try { server.accept().use { s ->
                accepted.set(s); s.soTimeout = 15_000
                val input = java.io.DataInputStream(s.getInputStream())
                val bytes = ByteArray(3); input.readFully(bytes)
                check(bytes.contentEquals(byteArrayOf(5, 1, 0))) { "WB test did not use unauthenticated SOCKS" }
                greeting.countDown()
                check(input.read() == -1) { "Unexpected bytes before SOCKS greeting reply" }
                eof.countDown()
            } } catch (t: Throwable) { failure.set(t); greeting.countDown() }
        }.apply { isDaemon = true; name = "qa-home-stalled-socks"; start() }
        override fun close() { accepted.get()?.close(); server.close(); thread.join(1_000) }
        fun awaitGreeting() {
            check(greeting.await(8, TimeUnit.SECONDS)) { "Real SpeedTest button never connected to relay SOCKS" }
            failure.get()?.let { throw it }
        }
        fun awaitClosed() {
            check(eof.await(2, TimeUnit.SECONDS)) { "Cancelled speed test left the SOCKS socket open" }
            failure.get()?.let { throw it }
        }
    }

    fun run() {
        val report = StringBuilder()
        var code = Activity.RESULT_CANCELED
        var main: MainActivity? = null
        var profileId = -1L
        val settings = app.loadSettings()
        val prefs = app.getPreferences(); val savedPrefs = prefs.all.toMap()
        val statsPrefs = app.getSharedPreferences("traffic_stats", android.content.Context.MODE_PRIVATE)
        val savedStatsPrefs = statsPrefs.all.toMap()
        val connection = flow<ConnectionState>(null, TunnelVpnService::class.java, "_connectionState")
        val active = flow<Long>(null, TunnelVpnService::class.java, "_activeProfileId")
        val oldConnection = connection.value; val oldActive = active.value
        val traffic = flow<TunnelVpnService.TrafficSnapshot>(null, TunnelVpnService::class.java, "_trafficStats")
        val oldTraffic = traffic.value
        val geo = GeoLookup::class.java.getDeclaredField("cachedUserLocation").apply { isAccessible = true }
        val geoTime = GeoLookup::class.java.getDeclaredField("cachedTimestamp").apply { isAccessible = true }
        val oldGeo = geo.get(null); val oldGeoTime = geoTime.getLong(null)
        val localeManager = if (Build.VERSION.SDK_INT >= 33) app.getSystemService(android.app.LocaleManager::class.java) else null
        val oldLocales = localeManager?.applicationLocales
        val oldCompatLocales = AppCompatDelegate.getApplicationLocales()
        var admitted = false
        try {
            check(oldConnection is ConnectionState.Disconnected && CredentialManager.getPort() == null) {
                "Requires an idle owned emulator; refusing to replace a real VPN/credential session"
            }
            admitted = true
            report.append(SpeedTestAndroidTlsProbe(test).run()).append('\n')
            onUi {
                if (Build.VERSION.SDK_INT >= 33) localeManager!!.applicationLocales = LocaleList.forLanguageTags("ru")
                else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
            }
            geo.set(null, GeoLookup.LatLon(55.8, 37.6)); geoTime.setLong(null, System.currentTimeMillis())
            prefs.edit().putBoolean("onboarding_done", true).commit()
            app.statsRepository.getStats() // Establish current day/week, then set owned preview counters.
            statsPrefs.edit().putLong("today_rx", 9_800_000).putLong("today_tx", 640_000)
                .putLong("week_rx", 58_000_000).putLong("week_tx", 2_500_000)
                .putLong("total_rx", 874_000_000_000).putLong("total_tx", 3_000_000_000).commit()
            traffic.value = TunnelVpnService.TrafficSnapshot(9_800_000, 640_000, 1_225_000, 362_000)
            val profileName = "QA WB Stream • Helsinki — очень длинное настоящее имя профиля / резервный сервер для проверки крупного шрифта 1234567890"
            profileId = runBlocking { app.database.profileDao().insert(ServerProfile(
                name = profileName, protocol = Protocol.WBSTREAM, address = "qa-owned-room-no-network")) }
            active.value = profileId
            for (theme in ThemeMode.entries) {
                connection.value = ConnectionState.Disconnected
                onUi { main?.finish() }; test.waitForIdleSync()
                app.saveSettings(settings.copy(themeMode = theme, launcherIconTheme = settings.launcherIconTheme ?: settings.themeMode,
                    showSpeedTest = true, showConnectionMap = true, trafficStatsMode = TrafficStatsMode.CHART))
                runBlocking { app.database.profileDao().getById(profileId)!!.let { app.database.profileDao().update(it.copy(name = profileName)) } }
                main = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                currentActivity = main
                val a = main!!; val vm = a.mainViewModel
                waitUi("Home not created") { a.findViewById<View>(R.id.home_scroll)?.isShown == true }
                waitUi("Own boot overlay still obscures Home") { a.findViewById<View>(R.id.boot_overlay)?.visibility != View.VISIBLE }
                check(a.resources.configuration.locales[0].language == "ru") { "Russian fixture locale not applied" }
                assertSystemBars(a)
                if (theme == ThemeMode.DARK) assertGeometryOracle(a)
                report.append("CONFIG $theme width=${a.resources.configuration.screenWidthDp}dp font=${a.resources.configuration.fontScale}\n")
                for ((name, status) in listOf("disconnected" to ConnectionState.Disconnected,
                    "connecting" to ConnectionState.Connecting, "error" to ConnectionState.Error("QA unavailable"))) {
                    onUi { connection.value = status }
                    test.waitForIdleSync(); Thread.sleep(100)
                    assertGeometry(a)
                    check(!view(a, "layout_speed_test").isShown) { "Speed test exposed while $name" }
                }
                onUi { connection.value = ConnectionState.Connected(System.currentTimeMillis() - 123_000) }
                waitUi("Actual long active profile not displayed") {
                    view(a, "layout_speed_test").isShown && (view(a, "tv_profile_name") as TextView).text.contains(profileName)
                }
                reveal(a, "tv_profile_name"); assertGeometry(a)
                if (theme == ThemeMode.DARK) screenshot("long-profile")
                runBlocking { app.database.profileDao().getById(profileId)!!.let { app.database.profileDao().update(it.copy(name = "hel · WB Stream")) } }
                waitUi("Short preview profile not displayed") { (view(a, "tv_profile_name") as TextView).text.contains("hel · WB Stream") }
                revealTop(a)
                test.waitForIdleSync(); assertGeometry(a); screenshot("${theme.name.lowercase()}-top")
                for (stage in SpeedTester.Stage.entries) {
                    onUi { state(vm, "_speedTesting", true); state(vm, "_speedStage", stage) }
                    waitUi("Stage did not render: $stage") { view(a, "tv_speed_stage").isShown && view(a, "btn_cancel_speed").isShown }
                    reveal(a, "btn_cancel_speed"); assertGeometry(a)
                }
                onUi {
                    state(vm, "_speedTesting", false); state<SpeedTester.Stage?>(vm, "_speedStage", null)
                    state(vm, "_speedResult", SpeedTester.SpeedResult(9.8, 7.4, 58))
                }
                waitUi("Result not rendered") { view(a, "tv_speed_result").isShown }
                reveal(a, "tv_speed_result"); assertGeometry(a); screenshot("${theme.name.lowercase()}-result")
                onUi { check(!(view(a, "tv_speed_result") as TextView).text.contains("-1")) { "Failure shown as numeric measurement" } }
                denseChart(a, theme)
                reveal(a, "tv_stats_total"); assertGeometry(a); screenshot("${theme.name.lowercase()}-stats")
                val home = (a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
                    .childFragmentManager.primaryNavigationFragment!!
                val refreshStats = home.javaClass.getDeclaredMethod("updateSessionStats").apply { isAccessible = true }
                for (mode in listOf(TrafficStatsMode.SIMPLE, TrafficStatsMode.HIDDEN, TrafficStatsMode.CHART)) {
                    app.saveSettings(app.loadSettings().copy(trafficStatsMode = mode))
                    onUi { refreshStats.invoke(home) }
                    test.waitForIdleSync()
                    onUi {
                        check((view(a, "history_card").visibility == View.GONE) == (mode == TrafficStatsMode.HIDDEN)) { "$mode left empty/missing history card" }
                        check((view(a, "traffic_chart").visibility == View.VISIBLE) == (mode == TrafficStatsMode.CHART))
                    }
                    assertGeometry(a)
                }
                report.append("PASS $theme: states, active profile, stages, results, reachable stats, geometry\n")
            }

            val a = main!!; val vm = a.mainViewModel
            onUi { state(vm, "_speedResult", SpeedTester.SpeedResult(1234.5, -1.0, -1)) }
            waitUi("Failed sample placeholders not rendered") { (view(a, "tv_speed_result") as TextView).text.contains(a.getString(R.string.speed_unmeasured)) }
            reveal(a, "tv_speed_result"); assertGeometry(a)
            onUi { check(!(view(a, "tv_speed_result") as TextView).text.contains("-1")) { "Failure shown as numeric measurement" } }
            onUi { state<SpeedTester.SpeedResult?>(vm, "_speedResult", null) }
            click(a, "btn_speed_test")
            waitUi("Missing credentials must show readable NOT_READY error") {
                !vm.speedTesting.value && vm.speedError.value == MainViewModel.SpeedError.NOT_READY &&
                    view(a, "tv_speed_error").isShown && (view(a, "tv_speed_error") as TextView).text.isNotBlank()
            }
            reveal(a, "tv_speed_error"); assertGeometry(a); screenshot("missing-credentials")
            report.append("PASS missing credentials: actual button / readable error\n")
            for (reason in listOf("button", "session", "credentials")) {
                val port = CredentialManager.generate().third
                StalledSocks(port).use { socks ->
                    click(a, "btn_speed_test"); socks.awaitGreeting()
                    waitUi("Busy cancellation control missing") { vm.speedTesting.value && view(a, "btn_cancel_speed").isShown }
                    check(vm.speedResult.value == null)
                    if (reason == "button") {
                        reveal(a, "btn_cancel_speed"); screenshot("running-cancellable"); click(a, "btn_cancel_speed")
                    } else if (reason == "session") onUi { connection.value = ConnectionState.Connected(System.currentTimeMillis() + 777) }
                    else CredentialManager.clear()
                    socks.awaitClosed()
                    waitUi("Cancelled $reason test still busy / published result") { !vm.speedTesting.value && vm.speedResult.value == null }
                }
                CredentialManager.clear()
                report.append("PASS actual stalled WB SOCKS cancelled by $reason, peer EOF, no result\n")
            }
            code = Activity.RESULT_OK
        } catch (t: Throwable) {
            report.append("FAIL ${t.stackTraceToString()}\n")
            runCatching { screenshot("failure") }
        } finally {
            if (admitted) {
                onUi { main?.mainViewModel?.cancelSpeedTest(); connection.value = ConnectionState.Disconnected; main?.finish() }
                test.waitForIdleSync(); CredentialManager.clear()
                connection.value = oldConnection; active.value = oldActive; traffic.value = oldTraffic
                if (profileId > 0) runBlocking { app.database.profileDao().deleteById(profileId) }
                app.saveSettings(settings)
                restorePrefs(prefs, savedPrefs)
                restorePrefs(statsPrefs, savedStatsPrefs)
                geo.set(null, oldGeo); geoTime.setLong(null, oldGeoTime)
                onUi {
                    if (Build.VERSION.SDK_INT >= 33) localeManager!!.applicationLocales = oldLocales!!
                    else AppCompatDelegate.setApplicationLocales(oldCompatLocales)
                }
            }
        }
        report.append(geometryNotes.joinToString("\n", postfix = "\n"))
        File(test.targetContext.getExternalFilesDir(null), "home-qa.txt").writeText(report.toString())
        test.finish(code, Bundle().apply { putString("stream", report.toString()) })
    }
}
