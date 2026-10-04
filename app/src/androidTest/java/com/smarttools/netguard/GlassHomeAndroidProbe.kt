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
internal class GlassHomeAndroidProbe(private val test: Instrumentation) {
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
        try { File(test.targetContext.getExternalFilesDir(null), "glass-$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
    private fun renderedFrames() {
        val done = CountDownLatch(1)
        val frames=java.util.concurrent.atomic.AtomicInteger()
        lateinit var decor:View
        lateinit var observer:android.view.ViewTreeObserver
        lateinit var listener:android.view.ViewTreeObserver.OnDrawListener
        onUi {
            val a=currentActivity ?: error("No Home activity for capture")
            val f=home(a)
            val visibleDialog=listOf("speedSheet","detailsSheet").mapNotNull { name ->
                runCatching { f.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(f) as? android.app.Dialog }.getOrNull()
            }.firstOrNull { it.isShowing && it.window?.decorView?.isShown==true }
            decor=visibleDialog?.window?.decorView ?: a.window.decorView
            check(decor.isAttachedToWindow && decor.isShown) { "Chosen capture window is not visible/attached" }
            observer=decor.viewTreeObserver
            listener=android.view.ViewTreeObserver.OnDrawListener {
                if(done.count>0) {
                    if(frames.incrementAndGet()>=3)done.countDown() else decor.postInvalidateOnAnimation()
                }
            }
            observer.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try {
            check(done.await(8, TimeUnit.SECONDS)) { "Visible window did not draw three frames before capture (drawn=${frames.get()})" }
        } finally {
            // OnDraw does not permit modifying the listener collection within its callback.
            onUi { if(observer.isAlive)observer.removeOnDrawListener(listener) }
        }
        // OnDraw precedes buffer composition; allow the final submitted buffer to be composed.
        Thread.sleep(120)
    }
    private fun restorePrefs(prefs: SharedPreferences, saved: Map<String, *>) {
        prefs.edit().clear().also { edit -> saved.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
        } } }.commit()
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
            check(eof.await(2, TimeUnit.SECONDS)) { "Cancelled speed test left the SOCKS socket open; peerFailure=${failure.get()}" }
            failure.get()?.let { throw it }
        }
    }

    private fun home(a: MainActivity): androidx.fragment.app.Fragment =
        (a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
            .childFragmentManager.primaryNavigationFragment!!
    private fun binding(a: MainActivity): com.smarttools.netguard.databinding.FragmentHomeBinding =
        home(a).let { f -> f.javaClass.getDeclaredField("_binding").apply { isAccessible = true }.get(f)
            as com.smarttools.netguard.databinding.FragmentHomeBinding }
    private fun sheet(a: MainActivity): com.google.android.material.bottomsheet.BottomSheetDialog =
        home(a).let { f -> f.javaClass.getDeclaredField("speedSheet").apply { isAccessible = true }.get(f)
            as com.google.android.material.bottomsheet.BottomSheetDialog }
    private fun nav(a: MainActivity, id: Int) {
        onUi { a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav).selectedItemId = id }
        test.waitForIdleSync()
    }
    private fun settled() { test.waitForIdleSync(); Thread.sleep(560); renderedFrames(); ownWindow() }
    private fun rect(v: View): Rect {
        val xy = IntArray(2); v.getLocationOnScreen(xy)
        return Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height)
    }
    private fun full(v: View, viewport: Rect) {
        val visible = Rect(); val expected = rect(v)
        check(v.isShown && v.width>0 && v.height>0 && v.getGlobalVisibleRect(visible)) { "Invisible ${v.id}" }
        check(visible.width() >= v.width-1 && visible.height() >= v.height-1 &&
            viewport.left <= expected.left+1 && viewport.right >= expected.right-1 &&
            viewport.top <= expected.top+1 && viewport.bottom >= expected.bottom-1) {
            "Clipped ${runCatching{v.resources.getResourceEntryName(v.id)}.getOrDefault(v.javaClass.simpleName)} expected=$expected visible=$visible viewport=$viewport"
        }
    }
    private fun onLaidOut(root: () -> View, assertions: () -> Unit) {
        val until=System.nanoTime()+8_000_000_000L
        var ready=false
        var pending="unknown"
        while(!ready && System.nanoTime()<until) {
            onUi {
                fun notReady(v:View):String? {
                    if(v.visibility!=View.VISIBLE)return null
                    val name=runCatching{v.resources.getResourceEntryName(v.id)}.getOrDefault(v.javaClass.simpleName)
                    if(v.isLayoutRequested)return "$name: layout requested"
                    if(v is TextView && v.text.isNotEmpty() && v.layout==null)return "$name: text Layout null (${v.text.take(80)})"
                    if(v is ViewGroup)for(i in 0 until v.childCount)notReady(v.getChildAt(i))?.let{return it}
                    return null
                }
                val unfinished=notReady(root())
                if(unfinished==null) {
                    // No event can change a timer/text layout between this readiness check and
                    // assertions. Actual clipping/overflow failures propagate; never retry them.
                    assertions()
                    ready=true
                } else pending=unfinished
            }
            if(!ready)Thread.sleep(25)
        }
        check(ready){"Visible layout did not become ready in 8s: $pending"}
    }
    private fun expectedHeading(a:MainActivity)=if(app.loadSettings().themeMode==ThemeMode.FSOCIETY)
        com.smarttools.netguard.widget.AppTypography.mono(a) else com.smarttools.netguard.widget.AppTypography.heading(a)

    /** Reuses one real native View/Paint: static texture must not poison the next frame's glow. */
    private fun assertTerminalRedraw(a:MainActivity) {
        if(!geometryNotes.add("fso-repeated-draw"))return
        run { // Invoked by mapAndFonts inside its existing main-thread assertion block.
            val native=com.smarttools.netguard.widget.LiquidBackdrop(a)
            val width=640;val height=960
            native.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            native.layout(0,0,width,height)
            fun draw()=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888).also {native.draw(android.graphics.Canvas(it))}
            native.setConnected(false,animate=false);val off=draw()
            native.setConnected(true,animate=false);val first=draw();val second=draw()
            try {
                for((x,y) in listOf(512 to 240,560 to 260,480 to 220)) {
                    val dark=android.graphics.Color.green(off.getPixel(x,y))
                    val lit=android.graphics.Color.green(first.getPixel(x,y))
                    val repeated=android.graphics.Color.green(second.getPixel(x,y))
                    check(lit-dark>=20 && repeated-dark>=20) { "FSO connected glow absent after reused texture Paint: OFF=$dark ON1=$lit ON2=$repeated" }
                    check(first.getPixel(x,y)==second.getPixel(x,y)) { "FSO static repeated frame changed color" }
                }
            } finally {off.recycle();first.recycle();second.recycle()}
        }
    }

    /** Actual inset/window geometry plus screenshot pixels, not just theme color attributes. */
    private fun assertSystemBars(a:MainActivity) {
        lateinit var backdrop:com.smarttools.netguard.widget.LiquidBackdrop
        var topInset=0
        onUi {
            backdrop=a.findViewById(R.id.liquid_backdrop)
            val decor=rect(a.window.decorView);val bg=rect(backdrop)
            val insets=checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView))
            val safe=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            topInset=safe.top
            check(bg.top==decor.top && bg.bottom==decor.bottom && bg.left==decor.left && bg.right==decor.right) {
                "Backdrop does not fill entire window: backdrop=$bg window=$decor safe=$safe"
            }
            check(a.window.statusBarColor==android.graphics.Color.TRANSPARENT && a.window.navigationBarColor==android.graphics.Color.TRANSPARENT) {
                "System bars still paint a separate opaque strip"
            }
            val host=rect(a.findViewById(R.id.nav_host_fragment));val nav=rect(a.findViewById(R.id.bottom_nav))
            check(host.top>=decor.top+safe.top && host.left>=decor.left+safe.left && host.right<=decor.right-safe.right) { "App content overlaps status/cutout insets" }
            check(nav.bottom<=decor.bottom-safe.bottom && nav.left>=decor.left+safe.left && nav.right<=decor.right-safe.right) { "Navigation overlaps system gesture/cutout insets" }
            val bars=androidx.core.view.WindowInsetsControllerCompat(a.window,a.window.decorView)
            val expected=androidx.core.graphics.ColorUtils.calculateLuminance(backdrop.base)>.5
            check(bars.isAppearanceLightStatusBars==expected && bars.isAppearanceLightNavigationBars==expected) { "System icon contrast flags wrong for actual backdrop" }
        }
        val key="bars-${backdrop.mode}-${backdrop.connectionLight>.99f}"
        if(topInset<8 || !geometryNotes.add(key))return
        ownWindow();renderedFrames()
        val screen=checkNotNull(test.uiAutomation.takeScreenshot())
        lateinit var expected:android.graphics.Bitmap
        onUi { expected=android.graphics.Bitmap.createBitmap(backdrop.width,backdrop.height,android.graphics.Bitmap.Config.ARGB_8888)
            backdrop.draw(android.graphics.Canvas(expected)) }
        try {
            // Center of status strip avoids clock, privacy/notification and battery icons.
            for(fraction in listOf(.42f,.5f,.58f))for(y in listOf(topInset/2,topInset-3)) {
                val x=(expected.width*fraction).toInt()
                val got=screen.getPixel(x,y);val want=expected.getPixel(x,y)
                val delta=maxOf(kotlin.math.abs(android.graphics.Color.red(got)-android.graphics.Color.red(want)),
                    kotlin.math.abs(android.graphics.Color.green(got)-android.graphics.Color.green(want)),
                    kotlin.math.abs(android.graphics.Color.blue(got)-android.graphics.Color.blue(want)))
                check(delta<=6) { "Status strip differs from actual native backdrop: x=$x y=$y got=$got expected=$want delta=$delta" }
            }
        } finally {screen.recycle();expected.recycle()}
    }

    /** Fake adapter records only: no DB writes, subscriptions, network fetches or profile clicks. */
    private fun groupedSubscriptionList(a:MainActivity,label:String) {
        val rv=a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_profiles)
        val adapter=rv.adapter as com.smarttools.netguard.ui.profiles.ProfileAdapter
        val lm=rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        val vm=androidx.lifecycle.ViewModelProvider(a)[com.smarttools.netguard.viewmodel.ProfileListViewModel::class.java]
        waitUi("Existing profile refresh did not settle before fake adapter fixture") { !vm.pinging.value }
        val original=adapter.currentList.toList()
        val subscriptions=original.filterIsInstance<com.smarttools.netguard.ui.profiles.ProfileAdapter.Item.Header>().associate{it.sub.id to it.sub}
        val profiles=original.filterIsInstance<com.smarttools.netguard.ui.profiles.ProfileAdapter.Item.Profile>().map{it.profile}
        val firstId=9_200_001L;val secondId=9_200_002L
        val fake=(0 until 28).map { index -> ServerProfile(id=9_300_000L+index,
            name="QA ${if(index<24)"Helsinki" else "Tokyo"} ${index+1} · WB Stream",protocol=Protocol.WBSTREAM,
            address="qa-owned-no-network",subscriptionId=if(index<24)firstId else secondId,lastPingMs=27+index) }
        val subs=mapOf(firstId to Subscription(id=firstId,name="QA Подписка · 24 сервера",profileCount=24,usedBytes=1_500_000_000,totalBytes=50_000_000_000),
            secondId to Subscription(id=secondId,name="QA Резервная подписка",profileCount=4))
        try {
            onUi {adapter.setData(fake,subs)}
            waitUi("Fake grouped rows did not commit") {adapter.currentList.size==30 && adapter.currentList.first().stableId=="h-$firstId"}
            onUi {lm.scrollToPositionWithOffset(0,0)};settled();assertSystemBars(a)
            if(app.loadSettings().themeMode==ThemeMode.FSOCIETY)onUi {
                fun checkMetadata(v:View) {
                    if(v is TextView && v.isShown && v.id in listOf(R.id.tv_name,R.id.tv_address,R.id.tv_sub_name,R.id.tv_ping,R.id.tv_protocol))
                        check(v.typeface==com.smarttools.netguard.widget.AppTypography.mono(a)) { "FSO visible metadata not monospace: ${a.resources.getResourceEntryName(v.id)}" }
                    if(v is ViewGroup)for(i in 0 until v.childCount)checkMetadata(v.getChildAt(i))
                }
                checkMetadata(rv)
            }
            screenshot("$label-grouped-top")
            onUi {lm.scrollToPositionWithOffset(12,0)};settled()
            onUi {
                check(lm.findFirstVisibleItemPosition()>0 && lm.findLastVisibleItemPosition()<24) { "Long-group fixture did not put both group boundaries offscreen" }
                check(rv.findViewHolderForAdapterPosition(0)==null && rv.findViewHolderForAdapterPosition(24)==null)
                val decoration=(0 until rv.itemDecorationCount).map{rv.getItemDecorationAt(it)}
                    .filterIsInstance<com.smarttools.netguard.ui.profiles.SubscriptionGroupDecoration>().single()
                val bitmap=android.graphics.Bitmap.createBitmap(rv.width,rv.height,android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    decoration.onDraw(android.graphics.Canvas(bitmap),rv,androidx.recyclerview.widget.RecyclerView.State())
                    var edgePixels=0
                    for(y in 0 until bitmap.height)for(x in 0 until bitmap.width) {
                        val alpha=android.graphics.Color.alpha(bitmap.getPixel(x,y))
                        if(x in bitmap.width/3..bitmap.width*2/3)check(alpha==0) { "Group paints opaque interior or phantom horizontal edge when both ends offscreen at $x,$y" }
                        else if(alpha>0)edgePixels++
                    }
                    check(edgePixels>rv.height/2) { "Group frame disappeared entirely; not a valid corner regression check" }
                } finally {bitmap.recycle()}
                check(rv.isVerticalFadingEdgeEnabled && rv.verticalFadingEdgeLength>=a.resources.displayMetrics.density*20) { "Native bottom fade missing" }
                check(rv.canScrollVertically(1)) { "Fade fixture not above list end" }
                fun draw()=android.graphics.Bitmap.createBitmap(rv.width,rv.height,android.graphics.Bitmap.Config.ARGB_8888).also {rv.draw(android.graphics.Canvas(it))}
                val faded=draw();lateinit var raw:android.graphics.Bitmap
                try {rv.isVerticalFadingEdgeEnabled=false;raw=draw()}finally{rv.isVerticalFadingEdgeEnabled=true}
                try {
                    val bottom=rv.height-rv.paddingBottom;val start=(bottom-rv.verticalFadingEdgeLength/2).coerceAtLeast(0)
                    var reference=0L;var actual=0L
                    for(y in start until bottom)for(x in 0 until rv.width) {
                        reference+=android.graphics.Color.alpha(raw.getPixel(x,y));actual+=android.graphics.Color.alpha(faded.getPixel(x,y))
                    }
                    check(reference>1000 && actual<reference*.8) { "Bottom fade did not attenuate actual list pixels: $actual / $reference" }
                    // Native fading ends before padding. clipToPadding=false used to draw the
                    // same row brightly AGAIN below that fade, making a hard strip above the dock.
                    for(y in bottom until rv.height)for(x in rv.width/3 until rv.width*2/3)
                        check(android.graphics.Color.alpha(faded.getPixel(x,y))==0) {
                            "Unfaded list content reappears in bottom padding after fade at $x,$y"
                        }
                } finally {faded.recycle();raw.recycle()}
            }
            screenshot("$label-grouped-middle")
            onUi {lm.scrollToPositionWithOffset(29,0)};settled()
            // Positioning the last row at the TOP is not the same as scrolling to the END,
            // especially in a short window; consume its remaining height/decorated margins.
            onUi {rv.scrollBy(0,rv.height*2)};settled()
            var endDiagnostic=""
            try { waitUi("Final profile never reachable") {
                endDiagnostic="items=${adapter.itemCount} first=${lm.findFirstVisibleItemPosition()} last=${lm.findLastVisibleItemPosition()} rvHeight=${rv.height} paddingBottom=${rv.paddingBottom} lastBottom=${rv.findViewHolderForAdapterPosition(29)?.itemView?.bottom} canDown=${rv.canScrollVertically(1)}"
                rv.findViewHolderForAdapterPosition(29)!=null && !rv.canScrollVertically(1)
            } } catch(t:IllegalStateException) {throw IllegalStateException("${t.message}; $endDiagnostic",t)}
            onUi {val last=rv.findViewHolderForAdapterPosition(29)!!.itemView
                check(last.bottom<=rv.height-rv.paddingBottom+1) { "Final row clipped by hard list boundary" }
            }
            screenshot("$label-grouped-bottom")
        } finally {onUi {adapter.setData(profiles,subscriptions)}}
    }

    /** Real IME, transient unsaved editor; no preference, profile or keyboard setting changes. */
    private fun keyboardInsets(a:MainActivity) {
        geometry(a)
        val root=a.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.main_shell)
        val nav=a.findViewById<View>(R.id.bottom_nav)
        val host=a.findViewById<View>(R.id.nav_host_fragment)
        val beforeNav=rect(nav);val beforeHost=rect(host)
        val b=binding(a)
        val beforeStatus=b.tvStatus.textSize to b.tvStatus.height
        val edit=android.widget.EditText(a).apply {
            id=View.generateViewId();hint="QA temporary keyboard";isSingleLine=true
            inputType=android.text.InputType.TYPE_CLASS_TEXT
        }
        fun insets()=checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView))
        val ime=androidx.core.view.WindowInsetsCompat.Type.ime()
        try {
            onUi {
                val safe=insets().getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                val p=androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topToTop=androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart=androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd=androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    topMargin=safe.top+(8*a.resources.displayMetrics.density).toInt()
                    marginStart=safe.left+(16*a.resources.displayMetrics.density).toInt()
                    marginEnd=safe.right+(16*a.resources.displayMetrics.density).toInt()
                }
                root.addView(edit,p);edit.bringToFront();check(edit.requestFocus())
            }
            test.waitForIdleSync()
            onUi {
                (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(edit,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                androidx.core.view.WindowInsetsControllerCompat(a.window,edit).show(ime)
            }
            waitUi("Real keyboard did not appear for IME inset gate") {insets().isVisible(ime) && insets().getInsets(ime).bottom>0}
            waitUi("Navigation failed to move above the visible keyboard") {
                rect(nav).bottom<=rect(a.window.decorView).bottom-insets().getInsets(ime).bottom
            }
            onUi {
                val safe=insets().getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                check(rect(host).top>=rect(a.window.decorView).top+safe.top)
                check(rect(host).bottom<=rect(nav).top)
                full(edit,Rect(0,safe.top,a.window.decorView.width,a.window.decorView.height-insets().getInsets(ime).bottom))
            }
            // This screenshot intentionally includes the real keyboard; package-focus guard is
            // unchanged and still requires the app's editor window to remain active.
            screenshot("fsociety-ime-visible")
        } finally {
            onUi {
                androidx.core.view.WindowInsetsControllerCompat(a.window,edit).hide(ime)
                (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(edit.windowToken,0)
                edit.clearFocus();root.removeView(edit)
            }
            waitUi("Keyboard stayed visible after temporary editor cleanup") {!insets().isVisible(ime)}
        }
        geometry(a)
        onUi {
            check(rect(nav)==beforeNav && rect(host)==beforeHost) { "IME hide did not restore original content/navigation bounds" }
            check((b.tvStatus.textSize to b.tvStatus.height)==beforeStatus) { "IME resize changed actual status font/height" }
        }
        screenshot("fsociety-ime-restored")
    }

    private fun geometry(a: MainActivity) {
        settled()
        assertSystemBars(a)
        onLaidOut({binding(a).root}) {
            val b = binding(a); val viewport = rect(b.root)
            val density=a.resources.displayMetrics.density
            val expectedColumns=b.root.width/density>500 && b.root.height/density<400
            check(b.root.orientation==(if(expectedColumns)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL)) {
                "Wrong fixed Home layout for ${b.root.width/density}x${b.root.height/density}dp: columns=$expectedColumns orientation=${b.root.orientation}"
            }
            fun visit(v: View) {
                check(v !is android.widget.ScrollView && v !is NestedScrollView) { "Home regained a ScrollView" }
                if (v.visibility != View.VISIBLE || v is android.widget.Space) return
                full(v,viewport)
                if(v is TextView && v.text.isNotEmpty()) {
                    val l=checkNotNull(v.layout); val content=v.width-v.compoundPaddingLeft-v.compoundPaddingRight
                    val ellipsisAllowed=v.id==R.id.tv_profile_name || v.id==R.id.tv_profile_protocol
                    check(l.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+2) { "Vertical text clip ${v.text}: lines=${l.lineCount} layout=${l.height} height=${v.height} contentWidth=$content padding=${v.compoundPaddingTop}/${v.compoundPaddingBottom} textSize=${v.textSize}" }
                    for(i in 0 until l.lineCount) {
                        check(ellipsisAllowed || l.getEllipsisCount(i)==0) { "Unexpected ellipsis ${v.text}" }
                        check(l.getLineMax(i)<=content+2) { "Horizontal text clip ${v.text}: ${l.getLineMax(i)} > $content" }
                    }
                }
                if(v is ViewGroup) {
                    val children=(0 until v.childCount).map(v::getChildAt).filter { it.visibility==View.VISIBLE }
                    if(v is LinearLayout) children.zipWithNext().forEach { (x,y) ->
                        check(if(v.orientation==LinearLayout.VERTICAL)x.bottom<=y.top+1 else x.right<=y.left+1) { "Sibling overlap in ${v.id}" }
                    }
                    children.forEach(::visit)
                }
            }
            visit(b.root)
            listOf(b.tvStatus,b.tvProfileName,b.serverCard,b.btnConnect,b.btnAutoSelect,b.tvDownSpeed,b.tvUpSpeed).forEach { full(it,viewport) }
            for((index,arrow) in listOf(0 to "↓",1 to "↑")) {
                val column=b.layoutStats.getChildAt(index) as ViewGroup
                val rate=if(index==0)b.tvDownSpeed else b.tvUpSpeed
                if(!column.getChildAt(0).isShown) check(rate.text.toString().startsWith(arrow)) {
                    "Compact rate lost direction: ${rate.text}; caption hidden"
                }
                val direction=a.getString(if(index==0)R.string.home_download else R.string.home_upload)
                check(rate.contentDescription?.toString()?.contains(direction)==true) { "Rate direction unavailable to accessibility" }
            }
            if(a.mainViewModel.connectionState.value is ConnectionState.Connected) full(b.btnSpeedTest,viewport)
            val nav=a.findViewById<View>(R.id.bottom_nav)
            check(viewport.bottom<=rect(nav).top+1) { "Floating navigation overlaps Home" }
        }
    }
    private fun clickSpeed(a: MainActivity) {
        onUi { val b=binding(a); full(b.btnSpeedTest,rect(b.root));check(b.btnSpeedTest.isEnabled && b.btnSpeedTest.performClick()) }
        waitUi("Actual test button did not open result sheet") { sheet(a).isShowing }
    }
    /** Exercise the real fitHome listener without recreating Activity or changing device settings. */
    private fun resizeSameInstance(a: MainActivity): String {
        // mapAndFonts re-decoration may request layout: baseline must be fully rendered too.
        geometry(a)
        val b=binding(a);val root=b.root;val density=a.resources.displayMetrics.density
        if(root.height/density<460 || a.resources.configuration.fontScale>1.6f)
            return "SKIP same-instance tall-to-compact-to-tall: original viewport already compact"
        val f=home(a);val width=root.layoutParams.width;val height=root.layoutParams.height
        fun state():List<Any> = listOf(b.tvStatus.autoSizeMinTextSize,b.tvStatus.autoSizeMaxTextSize,
            b.tvStatus.textSize,b.tvStatus.height,
            b.btnConnect.minHeight,(b.btnConnect.layoutParams as LinearLayout.LayoutParams).topMargin,
            b.btnAutoSelect.textSize,b.btnSpeedTest.textSize,b.btnDetails.textSize,
            b.layoutStats.paddingTop,b.layoutStats.paddingBottom,b.tvTimer.visibility,b.tvProfileProtocol.visibility,
            (b.serverCard.getChildAt(0) as View).paddingTop,
            (b.layoutStats.getChildAt(0) as ViewGroup).getChildAt(0).visibility,
            (b.tvDownSpeed.layoutParams as LinearLayout.LayoutParams).topMargin,
            root.orientation,root.childCount,root.paddingLeft,root.paddingTop,root.paddingRight,root.paddingBottom,
            b.homeMap.visibility,b.homeMap.height)
        fun dimensions():String = buildString {
            fun appendView(v:View,depth:Int) {
                val name=runCatching{v.resources.getResourceEntryName(v.id)}.getOrDefault(v.javaClass.simpleName)
                val p=v.layoutParams
                append("  ".repeat(depth)).append(name).append(" rect=").append(v.left).append(',').append(v.top)
                    .append(',').append(v.right).append(',').append(v.bottom)
                    .append(" measured=").append(v.measuredWidth).append('x').append(v.measuredHeight)
                    .append(" visibility=").append(v.visibility).append(" lp=").append(p?.width).append('x').append(p?.height)
                if(p is LinearLayout.LayoutParams)append(" weight=").append(p.weight).append(" margins=")
                    .append(p.leftMargin).append(',').append(p.topMargin).append(',').append(p.rightMargin).append(',').append(p.bottomMargin)
                append(" padding=").append(v.paddingLeft).append(',').append(v.paddingTop).append(',')
                    .append(v.paddingRight).append(',').append(v.paddingBottom)
                if(v is TextView)append(" textSizePx=").append(v.textSize).append(" autoMinMax=")
                    .append(v.autoSizeMinTextSize).append('/').append(v.autoSizeMaxTextSize)
                    .append(" textLayoutHeight=").append(v.layout?.height).append(" lines=").append(v.layout?.lineCount)
                    .append(" text=").append(v.text.toString().take(160))
                append('\n')
                if(v is ViewGroup)for(i in 0 until v.childCount)appendView(v.getChildAt(i),depth+1)
            }
            appendView(root,0)
        }
        var before:List<Any> = emptyList()
        var beforeDimensions=""
        onUi { before=state();beforeDimensions=dimensions();root.layoutParams=root.layoutParams.apply{this.height=(390*density).toInt()} }
        try {
            waitUi("Same-instance compact viewport not applied"){root.height/density<400 && b.tvTimer.visibility==View.GONE}
            geometry(a)
            onUi { check(home(a)===f) { "Resize unexpectedly recreated Home" }; check(b.homeMap.visibility==View.GONE) { "Compact resize retained overview" } }
            screenshot("same-instance-compact")
        } finally { onUi{root.layoutParams=root.layoutParams.apply{this.width=width;this.height=height}} }
        waitUi("Same-instance original viewport not restored"){root.height/density>=460 && b.tvTimer.visibility==View.VISIBLE}
        geometry(a)
        onUi {check(home(a)===f && state()==before) { "Resize did not restore all original layout defaults: before=$before after=${state()}\nBEFORE dimensions:\n$beforeDimensions\nAFTER dimensions:\n${dimensions()}" }}
        return "PASS same-instance tall-to-compact-to-tall: same Fragment, all mandatory controls fit, full defaults restored"
    }
    private fun sheetText(a: MainActivity, id: Int): TextView = sheet(a).findViewById(id)!!
    private fun assertSheet(a: MainActivity) {
        settled()
        onLaidOut({sheet(a).window!!.decorView}) {
            val dialog=sheet(a); check(dialog.isShowing)
            val viewport=rect(dialog.window!!.decorView)
            for(id in listOf(R.id.tv_speed_stage,R.id.tv_speed_result,R.id.tv_speed_error,R.id.btn_cancel_speed)) {
                val v=dialog.findViewById<View>(id)!!
                if(v.isShown) {
                    full(v,viewport)
                    if(v is TextView && v.text.isNotEmpty()) {
                        val layout=checkNotNull(v.layout)
                        check(layout.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+2) { "Sheet text vertically clipped: ${v.text}" }
                        for(line in 0 until layout.lineCount) check(layout.getLineMax(line)<=v.width-v.compoundPaddingLeft-v.compoundPaddingRight+2) { "Sheet text horizontally clipped: ${v.text}" }
                    }
                }
            }
        }
    }

    private fun mapAndFonts(a:MainActivity, connected:Boolean, name:String) {
        onUi {
            val b=binding(a);val m=b.homeMap
            check(!m.isClickable && !m.hasOnClickListeners()) { "Unwanted map opener retained" }
            check((b.serverCard.getChildAt(0) as ViewGroup).childCount==2) { "Unexpected globe control in server card" }
            fun field(key:String):Any?=m.javaClass.getDeclaredField(key).apply{isAccessible=true}.get(m)
            val expected=checkNotNull(GeoLookup.fromProfileName(name)) { "Fixture must have known location" }
            check(field("isConnected")==connected) { "Overview connection state stale" }
            check(field("serverLocation")==if(connected)expected else null) { "Overview actual server location stale" }
            check(field("serverLabel")==if(connected)name else null) { "Overview actual server label stale" }
            val d=a.resources.displayMetrics.density
            val expectedVisible=app.loadSettings().showConnectionMap && b.root.height/d>=580 && b.root.height/d>=540 && a.resources.configuration.fontScale<=1.25f
            check(m.isShown==expectedVisible) { "Overview visibility wrong for actual viewport" }
            val moving=connected && expectedVisible && android.animation.ValueAnimator.areAnimatorsEnabled()
            val dash=field("dashAnimator") as? android.animation.ValueAnimator
            check((dash?.isRunning==true)==moving) { "Map movement does not match connected/visible/motion preference" }
            if(!moving) for(key in listOf("lineAnimator","pulseAnimator","dashAnimator")) {
                check((field(key) as? android.animation.ValueAnimator)?.isStarted!=true) { "Hidden/disconnected map retains animator $key" }
            }
            val terminal=app.loadSettings().themeMode==ThemeMode.FSOCIETY
            if(terminal)assertTerminalRedraw(a)
            check(b.tvFsocHeader.isShown==(terminal && expectedVisible)) { "FSO greeting visibility incorrect for compact/theme state" }
            if(b.tvFsocHeader.isShown)check(b.tvFsocHeader.text.toString()=="hello, friend.")
            if(terminal && b.tvFsocPrompt.isShown)check(b.tvFsocPrompt.text.toString().startsWith("root@fsociety:~$ [ ")) { "FSO terminal prompt missing" }
            if(expectedVisible) { check(m.height/d>=64) { "Overview has no useful height: ${m.height/d}dp" };full(m,rect(b.root));assertCleanOcean(a,m) }
            val typography=com.smarttools.netguard.widget.AppTypography
            check(b.tvProfileName.typeface==expectedHeading(a)) { "Server heading not expected theme font" }
            check(b.tvDownSpeed.typeface==typography.mono(a)) { "Rate not bundled JetBrains Mono" }
            check(b.tvTimer.typeface==typography.mono(a)) { "Timer not bundled JetBrains Mono" }
            check(b.tvStatus.typeface==expectedHeading(a) && b.btnConnect.typeface==expectedHeading(a)) { "FSO/status/button font role mismatch" }
            val snapshots=listOf(b.tvStatus,b.tvProfileName,b.tvDownSpeed,b.tvTimer).map{it.typeface}
            repeat(3){a.decorateGlass(b.root)}
            check(listOf(b.tvStatus,b.tvProfileName,b.tvDownSpeed,b.tvTimer).map{it.typeface}==snapshots) { "Repeated decorate changed Home font roles" }
            // Anonymous bold headings are the regression case: semibold600 is not Typeface.BOLD.
            val heading=TextView(a).apply { text="ЩЁлкнуть № 123";setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD) }
            a.decorateGlass(heading)
            check(heading.typeface==expectedHeading(a))
            repeat(3){a.decorateGlass(heading)}
            check(heading.typeface==expectedHeading(a)) { "Anonymous heading degraded after repeated decorate" }
            for(font in listOf(typography.body(a),typography.heading(a),typography.mono(a))) {
                val paint=android.graphics.Paint().apply{typeface=font;textSize=32f}
                for(c in "ЁёЙйЩщЪъ№Мбит/с↓↑")check(paint.hasGlyph(c.toString())) { "Runtime font missing glyph $c" }
            }
        }
    }
    /** Semantic pixel oracle: four open-ocean regions and two continental interiors.
     * Uses actual Android View.draw, independent of the vector path/polygon generator.
     * The retained JPEG pipeline is a negative control, never the desired image oracle.
     */
    private fun assertCleanOcean(a:MainActivity,m:com.smarttools.netguard.widget.ConnectionMapView) {
        check(m.javaClass.getDeclaredField("mapBitmap").apply{isAccessible=true}.get(m)==null) { "Home still uses raster map" }
        check(m.javaClass.getDeclaredField("mapVector").apply{isAccessible=true}.get(m)!=null) { "Home vector absent" }
        fun render(v:View)=android.graphics.Bitmap.createBitmap(v.width,v.height,android.graphics.Bitmap.Config.ARGB_8888).also {
            v.draw(android.graphics.Canvas(it))
        }
        val pad=4*a.resources.displayMetrics.density
        val atlasHeight=minOf(m.height.toFloat(),m.width*.48f)
        val left=pad;val top=(m.height-atlasHeight)/2f+pad
        val width=m.width-2*pad;val height=atlasHeight-2*pad
        // Atlas fractions correspond to open North/South Pacific, South Atlantic, Indian Ocean.
        val oceans=listOf(floatArrayOf(.10f,.45f,.16f,.55f),floatArrayOf(.20f,.75f,.26f,.87f),
            floatArrayOf(.40f,.72f,.46f,.85f),floatArrayOf(.70f,.74f,.76f,.85f))
        fun nonzero(bitmap:android.graphics.Bitmap,area:FloatArray):Int {
            var count=0
            val x0=(left+width*area[0]).toInt();val x1=(left+width*area[2]).toInt()
            val y0=(top+height*area[1]).toInt();val y1=(top+height*area[3]).toInt()
            check(x1>x0 && y1>y0)
            for(y in y0 until y1)for(x in x0 until x1)
                if(android.graphics.Color.alpha(bitmap.getPixel(x,y))!=0)count++
            return count
        }
        val actual=render(m)
        try {
            oceans.forEachIndexed { i,area -> check(nonzero(actual,area)==0) { "Native ocean patch$i contains nontransparent pixels/noise" } }
            for(area in listOf(floatArrayOf(.54f,.60f,.56f,.62f),floatArrayOf(.32f,.70f,.34f,.72f)))
                check(nonzero(actual,area)>4) { "Clean-ocean result achieved by missing land" }
            // Run once per viewport/theme: regression must detect the replaced noisy pipeline.
            val key="ocean ${a.resources.configuration.uiMode} ${app.loadSettings().themeMode} ${m.width}x${m.height}"
            if(geometryNotes.add(key)) {
                val reference=com.smarttools.netguard.widget.ConnectionMapView(a).apply {
                    overview=true;setMapImage(R.drawable.world_map);layout(0,0,m.width,m.height)
                }
                val raster=render(reference)
                try {
                    val defects=oceans.sumOf{nonzero(raster,it)}
                    check(defects>0) { "Ocean regression negative control did not detect old JPEG noise" }
                    val note="PASS CLEAN_OCEAN ${app.loadSettings().themeMode}: actual View transparent in4patches, landpositive2, oldRasterDefectPixels=$defects\n"
                    File(test.targetContext.getExternalFilesDir(null),"glass-ocean-qa.txt").appendText(note)
                } finally {raster.recycle()}
            }
        } finally {actual.recycle()}
    }

    private fun settingsGlass(a:MainActivity,label:String) {
        val scroll=a.findViewById<android.widget.ScrollView>(R.id.settings_scroll)
        for(id in listOf(R.id.btn_health_services,R.id.btn_traffic_stats_mode)) {
            val button=a.findViewById<com.google.android.material.button.MaterialButton>(id)
            val name=a.resources.getResourceEntryName(id)
            var enabled=true;var pressed=false
            onUi {
                enabled=button.isEnabled;pressed=button.isPressed
                button.requestRectangleOnScreen(Rect(0,0,button.width,button.height),true)
            }
            settled()
            fun pixels():IntArray {
                val bitmap=android.graphics.Bitmap.createBitmap(button.width,button.height,android.graphics.Bitmap.Config.ARGB_8888)
                try { button.draw(android.graphics.Canvas(bitmap));return IntArray(bitmap.width*bitmap.height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)} }
                finally {bitmap.recycle()}
            }
            fun assertSingle() {
                check(button.foreground==null) { "$name foreground restores doubled contour" }
                val bg=button.background
                check(bg is android.graphics.drawable.RippleDrawable) { "$name lost bounded native ripple" }
                fun count(drawable:android.graphics.drawable.Drawable?):Int = when(drawable) {
                    is com.smarttools.netguard.widget.GlassDrawable -> 1
                    is android.graphics.drawable.LayerDrawable -> (0 until drawable.numberOfLayers).sumOf{count(drawable.getDrawable(it))}
                    is android.graphics.drawable.InsetDrawable -> count(drawable.drawable)
                    else -> 0
                }
                check(count(bg)==1) { "$name requires exactly one glass content layer" }
                val mask=bg.findDrawableByLayerId(android.R.id.mask)
                check(mask!=null) { "$name ripple not shape bounded" }
                check(button.height/a.resources.displayMetrics.density>=48) { "$name touch height below48dp" }
                full(button,rect(scroll))
                check(button.typeface==expectedHeading(a))
                check(button.text.any{it in 'А'..'я' || it=='Ё' || it=='ё'}) { "Settings fixture is not real Cyrillic UI" }
            }
            try {
                var normal=IntArray(0)
                onUi {
                    button.isEnabled=true;button.isPressed=false
                    repeat(3){a.decorateGlass(scroll)}
                    assertSingle();button.background.jumpToCurrentState();normal=pixels()
                }
                screenshot("$label-$name-normal")
                onUi { button.drawableHotspotChanged(button.width/2f,button.height/2f);button.isPressed=true;assertSingle() }
                screenshot("$label-$name-pressed")
                onUi {
                    button.isPressed=false;button.isEnabled=false
                    repeat(3){a.decorateGlass(scroll)}
                    assertSingle();button.background.jumpToCurrentState()
                    check(!pixels().contentEquals(normal)) { "$name disabled appearance identical to enabled" }
                }
                screenshot("$label-$name-disabled")
            } finally {onUi {button.isPressed=pressed;button.isEnabled=enabled;a.decorateGlass(scroll)}}
        }
    }

    /** Pixel oracle uses the actual scroll view, including cards, not a mirror of its XML. */
    private fun edgePixels(v:View,atEnd:Boolean,label:String):Boolean {
        check(v.isVerticalFadingEdgeEnabled && v.verticalFadingEdgeLength>=v.resources.displayMetrics.density*20) { "$label missing fade" }
        check(v.canScrollVertically(1)!=atEnd) { "$label scroll position mismatch" }
        fun draw()=android.graphics.Bitmap.createBitmap(v.width,v.height,android.graphics.Bitmap.Config.ARGB_8888).also {
            val c=android.graphics.Canvas(it);c.translate(-v.scrollX.toFloat(),-v.scrollY.toFloat());v.draw(c)
        }
        val faded=draw();lateinit var raw:android.graphics.Bitmap
        try {v.isVerticalFadingEdgeEnabled=false;raw=draw()}finally{v.isVerticalFadingEdgeEnabled=true}
        try {
            val bottom=v.height-v.paddingBottom
            val start=(bottom-v.verticalFadingEdgeLength/2).coerceAtLeast(0)
            var reference=0L;var actual=0L;var delta=0L
            for(y in start until bottom)for(x in v.width/4 until v.width*3/4) {
                val r=android.graphics.Color.alpha(raw.getPixel(x,y));val f=android.graphics.Color.alpha(faded.getPixel(x,y))
                reference+=r;actual+=f;delta+=kotlin.math.abs(r-f)
            }
            for(y in bottom until v.height)for(x in v.width/3 until v.width*2/3)
                check(android.graphics.Color.alpha(faded.getPixel(x,y))==0) { "$label bright tail below fade: $x,$y" }
            if(atEnd)check(delta==0L) { "$label end still fades actual last content: $delta" }
            else if(reference<1000)return false
            else check(actual<reference*.8) { "$label pixels not attenuated: $actual / $reference" }
            return true
        }finally{raw.recycle();faded.recycle()}
    }
    private fun mapMotionLifecycle(a:MainActivity,report:StringBuilder) {
        val m=binding(a).homeMap
        fun field(key:String)=m.javaClass.getDeclaredField(key).apply{isAccessible=true}.get(m)
        var visible=false
        onUi { visible=m.isShown }
        if(!visible || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            onUi { check(field("dashAnimator")==null) }
            report.append("PASS map motion disabled for hidden/reduced-motion viewport\n")
            return
        }
        var before=0f
        var animator:Any?=null
        onUi { before=field("dashPhase") as Float;animator=field("dashAnimator");check(animator!=null) }
        waitUi("Visible map dash phase never advanced") { (field("dashPhase") as Float)!=before }
        onUi {
            check((field("dashPhase") as Float)<=0f) { "Dash phase reversed user-to-server path" }
            repeat(3){m.setConnected(true)}
            check(field("dashAnimator")===animator) { "Repeated connected update restarts map animator" }
            m.visibility=View.INVISIBLE
            check(field("dashAnimator")==null && field("lineAnimator")==null) { "Hidden map retains animation" }
            before=field("dashPhase") as Float
        }
        Thread.sleep(180)
        onUi { check(field("dashPhase")==before) { "Hidden map still updates phase" };m.visibility=View.VISIBLE }
        waitUi("Reshown connected map did not restart movement") {
            (field("dashAnimator") as? android.animation.ValueAnimator)?.isRunning==true
        }
        nav(a,R.id.nav_settings)
        onUi { check(!m.isAttachedToWindow && field("dashAnimator")==null) { "Detached Home map still animates" } }
        nav(a,R.id.nav_home);geometry(a)
        report.append("PASS actual map phase advances, repeated connected reuses animator, hidden phase frozen, visible resumes, navigation detaches/stops\n")
    }
    private fun settingsReselect(a:MainActivity,report:StringBuilder) {
        val host=a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val fragment=host.childFragmentManager.primaryNavigationFragment
        val scroll=a.findViewById<android.widget.ScrollView>(R.id.settings_scroll)
        val original=scroll.scrollY
        onUi { scroll.scrollTo(0,((scroll.getChildAt(0).height-scroll.height)/2).coerceAtLeast(1)) }
        test.waitForIdleSync()
        var anchor=0
        onUi {
            anchor=scroll.scrollY;check(anchor>0) { "Settings fixture did not scroll" }
            val nav=a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            check(nav.selectedItemId==R.id.nav_settings)
            check(nav.findViewById<View>(R.id.nav_settings).performClick()) { "Actual Settings reselect click unavailable" }
        }
        test.waitForIdleSync();Thread.sleep(200)
        onUi {
            check(host.childFragmentManager.primaryNavigationFragment===fragment) { "Reselect recreated Settings root" }
            check(a.findViewById<View>(R.id.settings_scroll)===scroll && scroll.scrollY==anchor) { "Reselect reset Settings scroll" }
            scroll.scrollTo(0,original)
        }
        report.append("PASS Settings actual reselect preserves fragment, view and scrolled offset\n")
    }
    private fun outlineNavigation(a:MainActivity) {
        onUi {
            val nav=a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            val expected=mapOf(R.id.nav_home to R.drawable.ic_nav_home,R.id.nav_profiles to R.drawable.ic_nav_servers,
                R.id.nav_subscriptions to R.drawable.ic_nav_subscriptions,R.id.nav_settings to R.drawable.ic_nav_settings,
                R.id.nav_logs to R.drawable.ic_nav_logs)
            fun pixels(drawable:android.graphics.drawable.Drawable):IntArray {
                val copy=checkNotNull(drawable.constantState).newDrawable(a.resources).mutate()
                copy.setTint(android.graphics.Color.WHITE);copy.setBounds(0,0,96,96)
                val image=android.graphics.Bitmap.createBitmap(96,96,android.graphics.Bitmap.Config.ARGB_8888)
                return try {copy.draw(android.graphics.Canvas(image));IntArray(96*96).also{image.getPixels(it,0,96,0,0,96,96)}}
                    finally{image.recycle()}
            }
            val signatures=mutableSetOf<Int>()
            expected.forEach { (id,res)->
                val actual=pixels(checkNotNull(nav.menu.findItem(id).icon))
                check(actual.contentEquals(pixels(checkNotNull(androidx.appcompat.content.res.AppCompatResources.getDrawable(a,res))))) { "Menu still contains old icon $id" }
                val ink=actual.count{android.graphics.Color.alpha(it)>0}
                check(ink in 300..4608) { "Navigation outline became invisible/solid glyph: $id ink=$ink" }
                signatures.add(actual.contentHashCode())
            }
            check(signatures.size==5) { "Navigation icons duplicated" }
        }
    }

    private fun tabScrollEdges(a:MainActivity,label:String,report:StringBuilder) {
        fun screen(id:Int) {
            onUi {(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController.navigate(id)}
            settled()
        }
        fun exercise(v:View,page:String,middle:()->Unit,shift:()->Unit,end:()->Unit,last:()->Unit) {
            onUi {middle()};settled()
            var proved=false
            repeat(12) {
                if(!proved) {
                    onUi {proved=edgePixels(v,false,"$label/$page")}
                    if(!proved){onUi {shift()};settled()}
                }
            }
            check(proved) { "$label/$page no nonempty content across fade band" }
            screenshot("$label-$page-edge-middle")
            onUi{end()};settled()
            waitUi("$label/$page not at actual end") {!v.canScrollVertically(1)}
            onUi{check(edgePixels(v,true,"$label/$page"));last()}
            screenshot("$label-$page-edge-end")
            report.append("PASS TAB_EDGE $label $page: actual pixel fade, no bright padding tail, last content reachable and not faded at end\n")
        }
        fun listExercise(rv:androidx.recyclerview.widget.RecyclerView,page:String,count:Int) {
            val lm=rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
            exercise(rv,page,{lm.scrollToPositionWithOffset(count/2,0)},
                {rv.scrollBy(0,(16*a.resources.displayMetrics.density).toInt())},
                {lm.scrollToPositionWithOffset(count-1,0);rv.post{rv.scrollBy(0,rv.height*3)}},
                {val last=checkNotNull(rv.findViewHolderForAdapterPosition(count-1));check(last.itemView.bottom<=rv.height-rv.paddingBottom+1) {"$page last row clipped"}})
        }
        screen(R.id.nav_settings)
        val scroll=a.findViewById<android.widget.ScrollView>(R.id.settings_scroll);val oldY=scroll.scrollY
        try {exercise(scroll,"settings",
            {
                val content=scroll.getChildAt(0) as ViewGroup
                val cards=(0 until content.childCount).map{content.getChildAt(it)}
                    .filterIsInstance<com.google.android.material.card.MaterialCardView>()
                    .filter{it.top+it.height/2>scroll.height && it.height>scroll.verticalFadingEdgeLength*2}
                check(cards.isNotEmpty()) {"Settings has no real card for edge-cut fixture"}
                val card=cards[cards.size/2]
                scroll.scrollTo(0,card.top+card.height/2-scroll.height)
            },
            {scroll.scrollBy(0,(16*a.resources.displayMetrics.density).toInt())},
            {scroll.fullScroll(View.FOCUS_DOWN)},
            {check(scroll.getChildAt(0).bottom-scroll.scrollY<=scroll.height-scroll.paddingBottom+1) {"Settings final content clipped"}
                full(a.findViewById(R.id.tv_created_by),rect(scroll))})
        }finally{onUi{scroll.scrollTo(0,oldY)}}
        screen(R.id.nav_logs)
        val logs=a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_logs)
        val logAdapter=logs.adapter as com.smarttools.netguard.ui.logs.LogFragment.LogAdapter
        val oldLogs=logAdapter.currentList.toList()
        val fakeLogs=(0 until 96).map {com.smarttools.netguard.service.LogBuffer.LogEntry(1_000_000L+it,
            com.smarttools.netguard.service.LogBuffer.LogLevel.INFO,"QA local row $it · Проверка плавного края журнала без сети и без изменения реального буфера")}
        try {
            onUi{logAdapter.submitList(fakeLogs)}
            waitUi("Fake logs not committed") {logAdapter.currentList==fakeLogs}
            listExercise(logs,"logs",fakeLogs.size)
        }finally{onUi{logAdapter.submitList(oldLogs)}}
        screen(R.id.nav_subscriptions)
        val subs=a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_subs)
        val subAdapter=subs.adapter as com.smarttools.netguard.ui.subscription.SubAdapter
        val oldSubs=subAdapter.currentList.toList();val empty=a.findViewById<View>(R.id.tv_empty);val oldEmpty=empty.visibility
        val fakeSubs=(0 until 30).map {Subscription(id=9_400_000L+it,name="QA Подписка ${it+1} · Helsinki",url="https://qa.invalid/no-network",profileCount=12,lastUpdatedMs=1_000_000L,usedBytes=123_000_000,totalBytes=50_000_000_000)}
        try {
            onUi{subAdapter.submitList(fakeSubs);empty.visibility=View.GONE}
            waitUi("Fake subscriptions not committed") {subAdapter.currentList==fakeSubs}
            listExercise(subs,"subscriptions",fakeSubs.size)
        }finally{onUi{subAdapter.submitList(oldSubs);empty.visibility=oldEmpty}}
    }

    private fun removeOwnedFixtureProfiles() = runBlocking {
        val dao=app.database.profileDao()
        dao.getAll().filter { it.address=="qa-owned-no-network" && it.protocol==Protocol.WBSTREAM && it.name.startsWith("QA ") }
            .forEach { dao.deleteById(it.id) }
    }

    fun run(singleTheme: String? = null, quickOnly: Boolean = false, edgesOnly:Boolean=false, cancelOnly:Boolean=false) {
        // Instrumentation.onStart can race Application.onCreate after a fresh install. Wait on
        // the main thread before snapshotting settings or admitting any fixture mutation.
        try {
            test.waitForIdleSync()
            waitUi("Application bootstrap did not initialize the fixture dependencies") {
                runCatching { app.database;app.profileRepository;app.statsRepository;true }.getOrDefault(false)
            }
        } catch(t:Throwable) {
            test.finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream","FAIL fixture startup before admission: ${t.stackTraceToString()}\n")})
            return
        }
        val report=StringBuilder();var code=Activity.RESULT_CANCELED;var main:MainActivity?=null;var profileId=-1L
        val settings=app.loadSettings();val prefs=app.getPreferences();val savedPrefs=prefs.all.toMap()
        val connection=flow<ConnectionState>(null,TunnelVpnService::class.java,"_connectionState")
        val active=flow<Long>(null,TunnelVpnService::class.java,"_activeProfileId")
        val traffic=flow<TunnelVpnService.TrafficSnapshot>(null,TunnelVpnService::class.java,"_trafficStats")
        val oldConnection=connection.value;val oldActive=active.value;val oldTraffic=traffic.value
        val geo=GeoLookup::class.java.getDeclaredField("cachedUserLocation").apply{isAccessible=true}
        val geoTime=GeoLookup::class.java.getDeclaredField("cachedTimestamp").apply{isAccessible=true}
        val oldGeo=geo.get(null);val oldGeoTime=geoTime.getLong(null)
        val locales=if(Build.VERSION.SDK_INT>=33)app.getSystemService(android.app.LocaleManager::class.java) else null
        val oldLocales=locales?.applicationLocales;val oldCompat=AppCompatDelegate.getApplicationLocales()
        var admitted=false
        try {
            check(oldConnection is ConnectionState.Disconnected && CredentialManager.getPort()==null) { "Requires idle owned emulator; no real VPN/credentials may be replaced" }
            admitted=true
            File(test.targetContext.getExternalFilesDir(null),"glass-ocean-qa.txt").writeText("RUN native ocean QA; compact-only may skip pixel checks\n")
            // A force-stopped previous instrumentation cannot execute finally. Only remove this
            // probe's exact non-routable address/protocol/name marker, never arbitrary profiles.
            removeOwnedFixtureProfiles()
            onUi { if(Build.VERSION.SDK_INT>=33)locales!!.applicationLocales=LocaleList.forLanguageTags("ru") else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru")) }
            geo.set(null,GeoLookup.LatLon(55.8,37.6));geoTime.setLong(null,System.currentTimeMillis())
            prefs.edit().putBoolean("onboarding_done",true).commit()
            val longName="QA Helsinki · WB Stream — очень длинное настоящее имя сервера / резервный канал 1234567890"
            profileId=runBlocking{app.database.profileDao().insert(ServerProfile(name=longName,protocol=Protocol.WBSTREAM,address="qa-owned-no-network"))}
            active.value=profileId
            val alternateName="QA Tokyo · WB Stream"
            val alternateId=runBlocking{app.database.profileDao().insert(ServerProfile(name=alternateName,protocol=Protocol.WBSTREAM,address="qa-owned-no-network"))}
            val themes=if(!singleTheme.isNullOrBlank()) listOf(ThemeMode.entries.firstOrNull {
                it.name.equals(singleTheme,true)
            } ?: error("Unknown glass_theme: $singleTheme")) else if(quickOnly || cancelOnly)listOf(ThemeMode.FSOCIETY) else ThemeMode.entries
            report.append("SCOPE themes=${themes.joinToString()} quick=$quickOnly edgesOnly=$edgesOnly; edges-only excludes speed/Home lifecycle scenarios\n")
            val drawable = com.smarttools.netguard.widget.GlassDrawable(false, true, 24f)
            val clone = checkNotNull(drawable.constantState).newDrawable()
            check(clone !== drawable && clone is com.smarttools.netguard.widget.GlassDrawable)
            checkNotNull(android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3333ff33),
                drawable, null).constantState).newDrawable().mutate()
            report.append("PASS glass/ripple cloning creates independent stateful drawable children\n")
            for(theme in themes) {
                onUi{connection.value=ConnectionState.Disconnected;main?.finish()};test.waitForIdleSync()
                app.saveSettings(settings.copy(themeMode=theme,launcherIconTheme=settings.launcherIconTheme?:settings.themeMode,
                    showSpeedTest=true,showConnectionMap=true,trafficStatsMode=TrafficStatsMode.CHART,
                    expertMode=if(edgesOnly)true else settings.expertMode))
                main=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                currentActivity=main;val a=main!!;val vm=a.mainViewModel
                waitUi("Fixed Home not created"){a.findViewById<View>(R.id.home_scroll)?.isShown==true}
                check(a.resources.configuration.locales[0].language=="ru")
                outlineNavigation(a)
                report.append("CONFIG $theme ${a.resources.configuration.screenWidthDp}x${a.resources.configuration.screenHeightDp}dp font=${a.resources.configuration.fontScale}\n")
                if (cancelOnly) continue
                if(edgesOnly) {
                    for(connected in listOf(false,true)) {
                        onUi {connection.value=if(connected)ConnectionState.Connected() else ConnectionState.Disconnected}
                        tabScrollEdges(a,"${theme.name.lowercase()}-${if(connected)"on" else "off"}",report)
                    }
                    continue
                }
                if(theme==ThemeMode.FSOCIETY) {
                    geometry(a)
                    if(binding(a).root.height/a.resources.displayMetrics.density>=580) {
                        keyboardInsets(a)
                        report.append("PASS real IME: temporary editor, navigation above keyboard, content top safe, original bounds/font restored, no saved input\n")
                    } else report.append("SKIP real IME: FSO viewport is already compact\n")
                }
                for((label,status) in listOf("off" to ConnectionState.Disconnected,"waiting" to ConnectionState.Connecting,
                    "error" to ConnectionState.Error("QA long failure ".repeat(30)),"on" to ConnectionState.Connected(System.currentTimeMillis()-123_000))) {
                    onUi { connection.value=status; traffic.value=if(status is ConnectionState.Connected)TunnelVpnService.TrafficSnapshot(9_800_000,640_000,1_225_000,362_000) else TunnelVpnService.TrafficSnapshot(0,0,0,0) }
                    geometry(a)
                    mapAndFonts(a,status is ConnectionState.Connected,longName)
                    if(label=="off")onUi {
                        val root=binding(a).root;val density=a.resources.displayMetrics.density
                        report.append("VIEWPORT $theme Home=${root.width/density}x${root.height/density}dp mode=${if(root.orientation==LinearLayout.HORIZONTAL)"TWO_COLUMNS" else "ONE_COLUMN"} config=${a.resources.configuration.screenWidthDp}x${a.resources.configuration.screenHeightDp}dp\n")
                    }
                    onUi {
                        check((a.findViewById<com.smarttools.netguard.widget.LiquidBackdrop>(R.id.liquid_backdrop).connectionLight>.99f)==(status is ConnectionState.Connected))
                        if(status is ConnectionState.Connected) {
                            if(theme==ThemeMode.LIGHT) {
                                check(a.findViewById<com.smarttools.netguard.widget.LiquidBackdrop>(R.id.liquid_backdrop).light)
                                check(binding(a).tvProfileProtocol.currentTextColor==android.graphics.Color.BLACK) {
                                    "Installed LIGHT protocol color is not the final black candidate: #${Integer.toHexString(binding(a).tvProfileProtocol.currentTextColor)}"
                                }
                            }
                            check(binding(a).tvProfileName.text.toString()==longName)
                            check(binding(a).tvProfileProtocol.text.toString()=="WB Stream")
                            check(!binding(a).tvStatus.hasOnClickListeners()) { "Recovered status retains stale error action" }
                        }
                    }
                    screenshot("${theme.name.lowercase()}-$label")
                }
                // Synthetic formatting stress only: no screenshots or network throughput claim.
                val previewTraffic=traffic.value
                val rateCases=listOf(0L,1L,1023L,(1023.9*1024).toLong(),
                    (999.9*1024*1024).toLong(),(1023.9*1024*1024*1024).toLong())
                try {
                    for(rate in rateCases) {
                        onUi { traffic.value=previewTraffic.copy(rxSpeed=rate,txSpeed=rate) }
                        val formatted=TrafficFormatter.formatSpeed(rate)
                        waitUi("Boundary rate not collected: $formatted") {
                            binding(a).tvDownSpeed.text.toString().endsWith(formatted) &&
                                binding(a).tvUpSpeed.text.toString().endsWith(formatted)
                        }
                        geometry(a)
                        report.append("PASS SYNTHETIC_RATE $theme ${rate}B/s formatted=$formatted (layout fixture, not measured throughput)\n")
                    }
                } finally {
                    onUi { traffic.value=previewTraffic }
                    waitUi("Preview rates not restored after formatting fixture") {
                        binding(a).tvDownSpeed.text.toString().endsWith(TrafficFormatter.formatSpeed(previewTraffic.rxSpeed)) &&
                            binding(a).tvUpSpeed.text.toString().endsWith(TrafficFormatter.formatSpeed(previewTraffic.txSpeed))
                    }
                }
                // Change actual active profile without changing the saved selection or recreating Home.
                onUi{active.value=alternateId}
                waitUi("Actual active profile switch not reflected"){binding(a).tvProfileName.text.toString()==alternateName}
                geometry(a);mapAndFonts(a,true,alternateName)
                onUi{active.value=profileId}
                waitUi("Actual active profile restore not reflected"){binding(a).tvProfileName.text.toString()==longName}
                geometry(a);mapAndFonts(a,true,longName)
                mapMotionLifecycle(a,report)
                if(theme==themes.first() || theme==ThemeMode.FSOCIETY)report.append(resizeSameInstance(a)).append('\n')
                // Real click, credentials absent: no public endpoint can be contacted.
                clickSpeed(a)
                waitUi("NOT_READY missing/readability error") { !vm.speedTesting.value && vm.speedError.value==MainViewModel.SpeedError.NOT_READY && sheetText(a,R.id.tv_speed_error).isShown && sheetText(a,R.id.tv_speed_error).text.isNotBlank() }
                assertSheet(a);screenshot("${theme.name.lowercase()}-not-ready")
                for(stage in SpeedTester.Stage.entries) {
                    onUi { state<MainViewModel.SpeedError?>(vm,"_speedError",null);state(vm,"_speedTesting",true);state(vm,"_speedStage",stage) }
                    waitUi("Detached stage/cancel missing $stage"){sheetText(a,R.id.tv_speed_stage).isShown && sheetText(a,R.id.btn_cancel_speed).isShown}
                    assertSheet(a)
                }
                onUi { state(vm,"_speedTesting",false);state<SpeedTester.Stage?>(vm,"_speedStage",null);state(vm,"_speedResult",SpeedTester.SpeedResult(9.8,7.4,58)) }
                waitUi("Detached result not rendered"){sheetText(a,R.id.tv_speed_result).isShown}
                assertSheet(a);screenshot("${theme.name.lowercase()}-result")
                onUi{sheet(a).dismiss()};test.waitForIdleSync()
                for(connected in listOf(false,true)) {
                    onUi{connection.value=if(connected)ConnectionState.Connected() else ConnectionState.Disconnected}
                    for((page,id) in listOf("servers" to R.id.nav_profiles,"subscriptions" to R.id.nav_subscriptions,"settings" to R.id.nav_settings)) {
                        nav(a,id);settled()
                        onUi{check((a.findViewById<com.smarttools.netguard.widget.LiquidBackdrop>(R.id.liquid_backdrop).connectionLight>.99f)==connected)}
                        screenshot("${theme.name.lowercase()}-$page-${if(connected)"on" else "off"}")
                        if(page=="settings") {
                            settingsGlass(a,"${theme.name.lowercase()}-${if(connected)"on" else "off"}")
                            settingsReselect(a,report)
                        }
                        if(page=="servers" && connected) {
                            groupedSubscriptionList(a,theme.name.lowercase())
                            report.append("PASS grouped subscription $theme: top/middle/bottom, offscreen group bounds, transparent interior, native bottom fade and accessible final row\n")
                        }
                    }
                }
                nav(a,R.id.nav_home);waitUi("Home return failed"){a.findViewById<View>(R.id.home_scroll)?.isShown==true}
                // Recreate the Home view through real navigation with map setting disabled.
                app.saveSettings(app.loadSettings().copy(showConnectionMap=false))
                nav(a,R.id.nav_settings);nav(a,R.id.nav_home);geometry(a)
                onUi { check(binding(a).homeMap.visibility==View.GONE) { "Map setting ignored" } }
                onUi {
                    val b=binding(a);val tall=b.root.height/a.resources.displayMetrics.density>=580 && a.resources.configuration.fontScale<=1.25f
                    if(tall) {
                        val params=b.connectionCard.layoutParams as LinearLayout.LayoutParams
                        check(params.weight>0 && b.connectionCard.height>=b.tvStatus.height) { "Map-off Home did not expand usable status hero" }
                    }
                    check(b.tvStatus.typeface==expectedHeading(a)) { "Map-off changed theme font role" }
                    check(b.homeMap.javaClass.getDeclaredField("dashAnimator").apply{isAccessible=true}.get(b.homeMap)==null)
                }
                screenshot("${theme.name.lowercase()}-map-off")
                app.saveSettings(app.loadSettings().copy(showConnectionMap=true))
                nav(a,R.id.nav_settings);nav(a,R.id.nav_home);geometry(a)
                mapAndFonts(a,true,longName)
                val f=home(a);val refresh=f.javaClass.getDeclaredMethod("updateSessionStats").apply{isAccessible=true}
                for(mode in TrafficStatsMode.entries) {
                    app.saveSettings(app.loadSettings().copy(trafficStatsMode=mode));onUi{refresh.invoke(f)}
                    geometry(a);onUi{check((binding(a).historyCard.visibility==View.GONE)==(mode==TrafficStatsMode.HIDDEN))}
                }
                report.append("PASS $theme: fixed viewport all states/long active profile, detached stages/results/NOT_READY, four-screen ON/OFF, traffic settings, Settings single-rim/ripple/pressed/disabled after3decorates, bundled Cyrillic roles stable, active-profile map switch/OFF/setting restoration\n")
            }
            if(!edgesOnly) for(reason in listOf("button","dismiss","navigation","recreation")) {
                var a=main!!;currentActivity=a
                nav(a,R.id.nav_home);waitUi("Home unavailable for $reason"){a.findViewById<View>(R.id.home_scroll)?.isShown==true}
                onUi{connection.value=ConnectionState.Connected()};settled()
                val vm=a.mainViewModel;val port=CredentialManager.generate().third
                StalledSocks(port).use { socks ->
                    clickSpeed(a);socks.awaitGreeting()
                    waitUi("Actual running cancel missing"){vm.speedTesting.value && sheetText(a,R.id.btn_cancel_speed).isShown}
                    if(reason=="button")onUi{check(sheetText(a,R.id.btn_cancel_speed).performClick())}
                    if(reason=="dismiss") {
                        // A re-shown dialog can be visible before WindowManager restores its focus.
                        // BACK must exercise this dialog, not the activity or its previous window.
                        waitUi("Reopened speed sheet never received input focus") {
                            sheet(a).isShowing && sheet(a).window?.decorView?.hasWindowFocus()==true
                        }
                        // Exercise BACK while the request is still alive. Waiting for
                        // screenshot frames here can consume its 4s latency deadline.
                        onUi { check(sheet(a).window?.decorView?.hasWindowFocus()==true && vm.speedTesting.value) {
                            "Speed sheet lost focus or stopped before genuine BACK cancellation"
                        } }
                        check(socks.eof.count==1L && socks.failure.get()==null) {
                            "SOCKS peer closed before BACK; cancellation would be unproven: ${socks.failure.get()}"
                        }
                        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        waitUi("Focused speed sheet remained visible after BACK") { !sheet(a).isShowing }
                    }
                    if(reason=="navigation") { onUi{sheet(a).hide()};nav(a,R.id.nav_settings) }
                    if(reason=="recreation") {
                        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
                        try {
                            onUi{a.recreate()}
                            main=test.waitForMonitorWithTimeout(monitor,8_000) as? MainActivity ?: error("Recreation did not create Main")
                            a=main!!;currentActivity=a
                        } finally {test.removeMonitor(monitor)}
                    }
                    socks.awaitClosed();waitUi("$reason left speed busy/result"){!vm.speedTesting.value && vm.speedResult.value==null}
                    if(reason=="button")onUi{sheet(a).dismiss()}
                }
                CredentialManager.clear();report.append("PASS $reason: actual button -> no-auth local SOCKS -> peer EOF, no orphan job/result\n")
            }
            code=Activity.RESULT_OK
        }catch(t:Throwable){report.append("FAIL ${t.stackTraceToString()}\n");runCatching{screenshot("failure")}}
        finally {
            if(admitted) {
                onUi{main?.mainViewModel?.cancelSpeedTest();connection.value=ConnectionState.Disconnected;main?.finish()};test.waitForIdleSync()
                CredentialManager.clear();connection.value=oldConnection;active.value=oldActive;traffic.value=oldTraffic
                if(profileId>0)runBlocking{app.database.profileDao().deleteById(profileId)}
                removeOwnedFixtureProfiles()
                app.saveSettings(settings);restorePrefs(prefs,savedPrefs);geo.set(null,oldGeo);geoTime.setLong(null,oldGeoTime)
                onUi{if(Build.VERSION.SDK_INT>=33)locales!!.applicationLocales=oldLocales!! else AppCompatDelegate.setApplicationLocales(oldCompat)}
            }
        }
        File(test.targetContext.getExternalFilesDir(null),"glass-qa.txt").writeText(report.toString())
        test.finish(code,Bundle().apply{putString("stream",report.toString())})
    }
}

