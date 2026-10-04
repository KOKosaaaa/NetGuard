package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.navigation.fragment.NavHostFragment
import com.smarttools.netguard.model.ThemeMode
import com.smarttools.netguard.ui.onboarding.OnboardingActivity
import kotlinx.coroutines.runBlocking

/** Runs only in the owned test emulator. Exercises public UI; no VPN/network operation. */
internal class OnboardingGuideAndroidProbe(private val test: Instrumentation) {
    private val app get() = test.targetContext.applicationContext as App
    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
    private fun waitUi(message: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + 8_000_000_000L
        var good = false
        while (!good && System.nanoTime() < until) {
            onUi { good = predicate() }
            if (!good) Thread.sleep(50)
        }
        check(good) { message }
    }
    private fun id(name: String) = test.targetContext.resources.getIdentifier(name, "id", test.targetContext.packageName)
        .also { check(it != 0) { "Missing UI id $name" } }
    private fun view(a: Activity, name: String): View = a.findViewById(id(name))
    private fun click(a: Activity, name: String) {
        onUi { check(view(a, name).isShown) { "$name not shown" }; check(view(a, name).performClick()) }
        test.waitForIdleSync()
        // Wizard animations are 180 + 220 ms; wait past the real transition guard.
        Thread.sleep(500)
    }
    private fun title(a: Activity): String = (view(a, "tv_welcome_title") as TextView).text.toString()
    private fun assertFooter(a: Activity) {
        onUi {
            val boxes = listOf("btn_back", "btn_next").map { name ->
                val v = view(a, name)
                check(v.isShown && v.isEnabled) { "$name unavailable" }
                val rect = Rect()
                check(v.getGlobalVisibleRect(rect) && rect.width() == v.width && rect.height() == v.height) {
                    "$name clipped: visible=$rect measured=${v.width}x${v.height}"
                }
                check(v.height >= (40 * v.resources.displayMetrics.density).toInt()) { "$name too small" }
                if (v is TextView) {
                    val layout = v.layout
                    check(layout != null && (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 }) {
                        "$name label ellipsized"
                    }
                }
                rect
            }
            check(!Rect.intersects(boxes[0], boxes[1])) { "Footer buttons overlap: $boxes" }
        }
    }
    private fun screenshot(name: String) {
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(test.targetContext.getExternalFilesDir(null), name).outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        } ?: error("Screenshot unavailable")
    }
    private fun assertOwnActiveWindow() {
        val expected = test.targetContext.packageName
        val until = System.nanoTime() + 8_000_000_000L
        var observed: String? = null
        // Accessibility is read on the instrumentation thread, never on the UI thread.
        while (System.nanoTime() < until) {
            observed = test.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (observed == expected) return
            Thread.sleep(50)
        }
        error("Guide is obscured or not foreground: active window=$observed, expected=$expected")
    }
    private fun assertGuideReadable(a: Activity) {
        assertOwnActiveWindow()
        assertFooter(a)
        onUi {
            val scroll = view(a, "step_welcome") as ScrollView
            check(scroll.isShown && scroll.height > 0)
            scroll.scrollTo(0, scroll.getChildAt(0).height)
        }
        test.waitForIdleSync()
        onUi {
            val body = view(a, "tv_welcome_body") as TextView
            val scroll = view(a, "step_welcome") as ScrollView
            val bodyRect = Rect(); val scrollRect = Rect()
            check(body.getGlobalVisibleRect(bodyRect) && scroll.getGlobalVisibleRect(scrollRect))
            val position = IntArray(2); body.getLocationOnScreen(position)
            check(position[1] + body.height <= scrollRect.bottom + 2) { "Last guide paragraph cannot be scrolled into view" }
            check(body.text.isNotBlank() && body.layout != null)
            check((0 until body.layout.lineCount).all { body.layout.getEllipsisCount(it) == 0 }) { "Guide text ellipsized" }
        }
    }
    private fun enabledLaunchers(): List<android.content.pm.ResolveInfo> {
        val pm = test.targetContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(test.targetContext.packageName)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS).filter { row ->
            val ai = row.activityInfo
            when (pm.getComponentEnabledSetting(ComponentName(ai.packageName, ai.name))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> ai.enabled
                else -> false
            }
        }
    }
    private fun assertCanonicalMainTask(main: MainActivity) {
        onUi { check(!main.isFinishing && !main.isDestroyed) { "Icon change closed the running Main activity" } }
        val manager = test.targetContext.getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val info = manager.appTasks.map { it.taskInfo }.firstOrNull { it.id == main.taskId }
            ?: error("Running Main task disappeared")
        check(info.baseIntent.component?.className == MainActivity::class.java.name) {
            "Main task is still rooted in a replaceable launcher: ${info.baseIntent.component}"
        }
    }
    fun run(language: String = "ru") {
        val report = StringBuilder()
        val prefs = app.getPreferences()
        val originalSettings = app.loadSettings()
        val originalPrefs = prefs.all.toMap()
        val originalLocales = AppCompatDelegate.getApplicationLocales()
        val localeManager = if (Build.VERSION.SDK_INT >= 33)
            test.targetContext.getSystemService(android.app.LocaleManager::class.java) else null
        val originalSystemLocales = if (Build.VERSION.SDK_INT >= 33) localeManager?.applicationLocales else null
        var main: MainActivity? = null
        var guide: OnboardingActivity? = null
        var code = Activity.RESULT_CANCELED
        try {
            onUi {
                if (Build.VERSION.SDK_INT >= 33) localeManager!!.applicationLocales = LocaleList.forLanguageTags(language)
                else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
            }
            prefs.edit().putBoolean(OnboardingActivity.PREF_ONBOARDING_DONE, true).commit()
            main = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            test.waitForIdleSync()
            onUi {
                val host = main!!.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                host.navController.navigate(R.id.nav_settings)
            }
            test.waitForIdleSync()
            val before = prefs.all.toMap()
            val profilesBefore = runBlocking { app.profileRepository.getAll() }
            val monitor = test.addMonitor(OnboardingActivity::class.java.name, null, false)
            onUi {
                val host = main!!.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val settingsView = host.childFragmentManager.primaryNavigationFragment!!.requireView()
                val row = settingsView.findViewById<View>(id("btn_about_app"))
                check(row != null && row.performClick()) { "Settings About row missing" }
            }
            guide = test.waitForMonitorWithTimeout(monitor, 10_000) as? OnboardingActivity
                ?: error("Settings did not open OnboardingActivity")
            test.removeMonitor(monitor)
            check(guide!!.intent.getBooleanExtra("info_only", false))
            test.waitForIdleSync()
            check(guide!!.resources.configuration.locales[0].language == language.substringBefore('-')) {
                "Requested guide locale $language was not applied"
            }
            val titles = mutableListOf<String>()
            repeat(4) { page ->
                val current = guide!!
                assertGuideReadable(current)
                titles += title(current)
                if (page == 2) {
                    val previousTitle = title(current)
                    val locale = current.resources.configuration.locales.toLanguageTags()
                    val recreation = test.addMonitor(OnboardingActivity::class.java.name, null, false)
                    onUi { current.recreate() }
                    guide = test.waitForMonitorWithTimeout(recreation, 10_000) as? OnboardingActivity
                        ?: error("Guide recreation did not resume")
                    test.removeMonitor(recreation)
                    test.waitForIdleSync()
                    check(title(guide!!) == previousTitle) { "Recreation reset guide page" }
                    check(guide!!.resources.configuration.locales.toLanguageTags() == locale) { "Recreation changed locale" }
                    check(guide!!.intent.getBooleanExtra("info_only", false))
                    onUi { guide!!.onBackPressedDispatcher.onBackPressed() }
                    Thread.sleep(500)
                    check(title(guide!!) == titles[1]) { "System Back did not return to guide page 2" }
                    click(guide!!, "btn_next")
                    check(title(guide!!) == previousTitle)
                }
                if (page < 3) click(guide!!, "btn_next")
            }
            check(titles.toSet().size == 4) { "Not four distinct guide pages: $titles" }
            assertOwnActiveWindow()
            screenshot("onboarding-guide-final.png")
            click(guide!!, "btn_next")
            waitUi("Info final action did not close") { guide!!.isFinishing || guide!!.isDestroyed }
            check(prefs.all == before) { "Information walkthrough changed preferences" }
            check(runBlocking { app.profileRepository.getAll() } == profilesBefore) { "Information walkthrough changed profiles" }
            onUi {
                val host = main!!.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                check(host.navController.currentDestination?.id == R.id.nav_settings) { "Info guide did not return to Settings" }
            }
            report.append("Settings→four guide pages; footer/scroll; page+locale recreation; system Back; final Close returns without preference/profile writes: PASS\n")
            guide = test.startActivitySync(Intent(test.targetContext, OnboardingActivity::class.java)
                .putExtra("info_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OnboardingActivity
            test.waitForIdleSync()
            onUi { guide!!.onBackPressedDispatcher.onBackPressed() }
            waitUi("First-page Back did not close") { guide!!.isFinishing || guide!!.isDestroyed }
            check(prefs.all == before)
            report.append("First informational page system Back closes without saving: PASS\n")
            guide = test.startActivitySync(Intent(test.targetContext, OnboardingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OnboardingActivity
            test.waitForIdleSync()
            click(guide!!, "btn_next") // Language → guide.
            assertGuideReadable(guide!!)
            click(guide!!, "btn_guide_skip")
            waitUi("Setup Skip did not reach mode") { view(guide!!, "step_mode").isShown }
            click(guide!!, "btn_back")
            waitUi("Mode Back did not return to guide") { view(guide!!, "step_welcome").isShown }
            // Back from mode may intentionally show first or last guide page; walk to final.
            repeat(4) { if (view(guide!!, "step_welcome").isShown) click(guide!!, "btn_next") }
            check(view(guide!!, "step_mode").isShown) { "Guide final action did not reach setup mode" }
            onUi { guide!!.finish() }
            check(prefs.all == before) { "Unfinished setup changed preferences" }
            report.append("Setup guide Skip/Back/final Next route to mode, without prematurely saving: PASS\n")
            onUi { main!!.finish() }
            test.waitForIdleSync()
            // Exact prefs produced by Application's two migrations on a clean installation.
            // These housekeeping defaults must not be mistaken for an existing user's setup.
            prefs.edit().clear().putBoolean("telemost_striping", false)
                .putBoolean("striping_migration_v2", true)
                .putString("routing_mode", "AUTO")
                .putBoolean("adaptive_routing_default_v1", true).commit()
            val firstRun = test.addMonitor(OnboardingActivity::class.java.name, null, false)
            test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            guide = test.waitForMonitorWithTimeout(firstRun, 10_000) as? OnboardingActivity
            test.removeMonitor(firstRun)
            check(guide != null) { "Clean startup migration keys wrongly suppress first-run wizard" }
            test.waitForIdleSync()
            check(!guide!!.intent.getBooleanExtra("info_only", false))
            check(view(guide!!, "step_lang").isShown)
            check(!prefs.getBoolean(OnboardingActivity.PREF_ONBOARDING_DONE, false))
            onUi { guide!!.finish() }
            report.append("Clean install bootstrap defaults do not suppress first-run setup: PASS\n")
            test.waitForIdleSync()
            prefs.edit().putBoolean(OnboardingActivity.PREF_ONBOARDING_DONE, true).commit()
            val launcherIntent = test.targetContext.packageManager.getLaunchIntentForPackage(test.targetContext.packageName)
                ?: error("No launch intent before icon changes")
            val mainLaunch = test.addMonitor(MainActivity::class.java.name, null, false)
            test.targetContext.startActivity(launcherIntent)
            main = test.waitForMonitorWithTimeout(mainLaunch, 10_000) as? MainActivity
                ?: error("Launcher entry did not forward to MainActivity")
            test.removeMonitor(mainLaunch)
            test.waitForIdleSync()
            val runningMain = main!!
            assertCanonicalMainTask(runningMain)
            val aliases = mutableSetOf<String>()
            for (theme in ThemeMode.values()) {
                onUi { app.saveSettings(originalSettings.copy(themeMode = theme, launcherIconTheme = null)) }
                val enabled = enabledLaunchers()
                check(enabled.size == 1) { "$theme has ${enabled.size} enabled launchers" }
                val ai = enabled.single().activityInfo
                check(ai.targetActivity == "com.smarttools.netguard.LauncherActivity") { "$theme alias target=${ai.targetActivity}" }
                check(ai.exported && ai.icon != 0)
                aliases += ai.name
                val launch = test.targetContext.packageManager.getLaunchIntentForPackage(test.targetContext.packageName)
                check(launch?.component?.className == ai.name) { "$theme resolver points elsewhere" }
                test.waitForIdleSync()
                Thread.sleep(700) // Package-change task cleanup is asynchronous even with DONT_KILL_APP.
                assertCanonicalMainTask(runningMain)
                assertOwnActiveWindow()
            }
            check(aliases.size == ThemeMode.values().size) { "Themes do not have distinct aliases: $aliases" }
            report.append("All six aliases resolve to launcher entry; canonical Main task and foreground survive delayed package changes: PASS\n")
            onUi { app.saveSettings(originalSettings.copy(themeMode = ThemeMode.LIGHT, launcherIconTheme = ThemeMode.FSOCIETY)) }
            val fixed = enabledLaunchers().single().activityInfo.name
            check(app.loadSettings().launcherIconTheme == ThemeMode.FSOCIETY)
            onUi { app.saveSettings(app.loadSettings().copy(themeMode = ThemeMode.OCEAN)) }
            check(enabledLaunchers().single().activityInfo.name == fixed) { "Theme change replaced fixed icon" }
            check(app.loadSettings().launcherIconTheme == ThemeMode.FSOCIETY) { "Fixed choice not persisted" }
            onUi { app.saveSettings(app.loadSettings().copy(launcherIconTheme = null)) }
            check(app.loadSettings().launcherIconTheme == null)
            check(enabledLaunchers().single().activityInfo.name != fixed) { "Returning to automatic did not follow current theme" }
            report.append("Fixed icon survives app theme changes and reload; automatic choice follows current theme again: PASS\n")
            onUi {
                val host = main!!.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                host.navController.navigate(R.id.nav_settings)
            }
            test.waitForIdleSync()
            fun pickIcon(resourceName: String, expected: ThemeMode?) {
                onUi {
                    val host = main!!.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    check(host.childFragmentManager.primaryNavigationFragment!!.requireView()
                        .findViewById<View>(id("btn_launcher_icon")).performClick())
                }
                test.waitForIdleSync()
                val labelId = test.targetContext.resources.getIdentifier(resourceName, "string", test.targetContext.packageName)
                val label = main!!.getString(labelId)
                val until = System.nanoTime() + 5_000_000_000L
                var clicked = false
                while (!clicked && System.nanoTime() < until) {
                    val root = test.uiAutomation.rootInActiveWindow
                    val nodes = root?.findAccessibilityNodeInfosByText(label).orEmpty()
                    for (node in nodes) {
                        if (node.text?.toString() == label) {
                            clicked = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            if (!clicked) clicked = node.parent?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                            if (clicked) break
                        }
                    }
                    if (!clicked) {
                        fun scrollable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                            if (node == null) return null
                            if (node.isScrollable) return node
                            for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
                            return null
                        }
                        scrollable(root)?.performAction(if (expected == null)
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        Thread.sleep(100)
                    }
                }
                check(clicked) { "Actual icon dialog choice unavailable: $label" }
                waitUi("Icon dialog did not persist selection $expected") { app.loadSettings().launcherIconTheme == expected }
                check(enabledLaunchers().size == 1)
                Thread.sleep(700)
                assertCanonicalMainTask(main!!)
                assertOwnActiveWindow()
            }
            pickIcon("theme_fsociety", ThemeMode.FSOCIETY)
            pickIcon("launcher_icon_follow", null)
            report.append("Actual Settings icon chooser persists fixed and automatic selections: PASS\n")
            LauncherArtworkAndroidProbe(test).run()
            report.append("Installed adaptive artwork/monochrome rendered at 128/48 px; raw foreground safe-circle bounds checked: PASS\n")
            code = Activity.RESULT_OK
        } catch (t: Throwable) {
            runCatching { screenshot("onboarding-guide-failure.png") }
            report.append("FAIL: ${t.stackTraceToString()}\n")
        } finally {
            onUi {
                guide?.finish(); main?.finish(); app.saveSettings(originalSettings)
                if (Build.VERSION.SDK_INT >= 33) localeManager!!.applicationLocales = originalSystemLocales!!
                else AppCompatDelegate.setApplicationLocales(originalLocales)
            }
            val edit = prefs.edit().clear()
            originalPrefs.forEach { (key, value) ->
                when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>)
                }
            }
            edit.commit()
        }
        test.finish(code, Bundle().apply { putString("stream", report.toString()) })
    }
}
