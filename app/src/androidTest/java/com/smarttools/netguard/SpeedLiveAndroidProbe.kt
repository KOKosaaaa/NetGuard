package com.smarttools.netguard

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import com.google.gson.GsonBuilder
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.model.PerAppMode
import com.smarttools.netguard.model.RoutingMode
import com.smarttools.netguard.model.TransportType
import com.smarttools.netguard.service.LogBuffer
import com.smarttools.netguard.service.TunnelVpnService
import kotlinx.coroutines.runBlocking
import java.io.File

/** Explicit owned-emulator opt-in: real service/core/network, no injected state or credentials.
 * Supply subscription through run-as to files/speed-live-subscription.txt. Input is consumed.
 * Only sanitized result metadata is exported; never export native logs, configuration or profiles.
 */
internal class SpeedLiveAndroidProbe(private val test: Instrumentation) {
    private val app get() = test.targetContext.applicationContext as App
    private val notes = mutableListOf<String>()
    private val rows = mutableListOf<Map<String, Any?>>()
    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
    private fun await(ms: Long, description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (!predicate()) {
            check(System.nanoTime() < deadline) { description }
            Thread.sleep(40)
        }
    }
    private fun uiAwait(ms: Long, description: String, predicate: () -> Boolean) =
        await(ms, description) { var yes = false; onUi { yes = predicate() }; yes }
    private fun sheet(a: MainActivity): Dialog? {
        val host = a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val home = host.childFragmentManager.primaryNavigationFragment ?: return null
        return home.javaClass.getDeclaredField("speedSheet").apply { isAccessible = true }.get(home) as? Dialog
    }
    private fun restorePrefs(prefs: SharedPreferences, saved: Map<String, *>) {
        val edit = prefs.edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
        } }
        check(edit.commit()) { "Preference restoration failed" }
    }
    private fun stop(a: MainActivity?) {
        onUi { a?.mainViewModel?.cancelSpeedTest(); a?.let { sheet(it)?.dismiss() } }
        TunnelVpnService.stop(test.targetContext)
        await(15_000, "Owned VPN did not fully stop") {
            TunnelVpnService.connectionState.value is ConnectionState.Disconnected && CredentialManager.getPort() == null
        }
    }
    fun run() {
        var a: MainActivity? = null
        var prefs: SharedPreferences? = null
        var savedPrefs: Map<String, *>? = null
        var originalSelection: List<Long> = emptyList()
        val inserted = mutableListOf<Long>()
        var admitted = false
        var success = false
        var cleanupOk = true
        var phase = "bootstrap"
        val input = File(test.targetContext.filesDir, "speed-live-subscription.txt")
        try {
            test.waitForIdleSync()
            await(15_000, "Application initialization incomplete") {
                runCatching { app.database; app.profileRepository; true }.getOrDefault(false)
            }
            check(test.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Debug owned-emulator build required" }
            check(TunnelVpnService.connectionState.value is ConnectionState.Disconnected && CredentialManager.getPort() == null) { "Requires idle app; existing connection untouched" }
            check(VpnService.prepare(test.targetContext) == null) { "Parent must pre-authorize owned emulator VPN" }
            check(input.isFile && input.length() in 1..4_194_304) { "Private fixture missing or oversized" }
            val parsed = ProfileParser.parseSubscription(input.readText()).profiles
            val candidates = listOf(TransportType.TCP, TransportType.SPLIT_HTTP).map { transport ->
                parsed.firstOrNull { it.network == transport && !it.protocol.usesRelay }
                    ?: error("Required fixture transport missing")
            }
            prefs = app.getPreferences()
            savedPrefs = prefs!!.all.toMap()
            originalSelection = runBlocking { app.profileRepository.getAll().filter { it.isSelected }.map { it.id } }
            admitted = true
            onUi {
                app.saveSettings(app.loadSettings().copy(showSpeedTest = true, showConnectionMap = false,
                    autoConnectWifi = false, triggerEnabled = false, autoSwitchOnThrottle = false,
                    perAppMode = PerAppMode.DISABLED, perAppList = emptySet(), routingMode = RoutingMode.GLOBAL_PROXY))
                check(prefs!!.edit().putBoolean("onboarding_done", true).commit())
            }
            a = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity = a!!
            val vm = activity.mainViewModel
            candidates.forEachIndexed { index, profile ->
                val row = linkedMapOf<String, Any?>("index" to index, "transport" to profile.network.name)
                rows.add(row)
                var currentPhase = "insert"
                val start = System.nanoTime()
                try {
                    val id = runBlocking { app.profileRepository.insert(profile.copy(id = 0,
                        name = "QA live ${profile.network.name}", subscriptionId = 0, isSelected = false)) }
                    inserted.add(id)
                    runBlocking { app.profileRepository.selectProfile(id) }
                    uiAwait(8_000, "Home did not observe selected fixture") { vm.selectedProfile.value?.id == id }
                    currentPhase = "connect"
                    val logStart = System.currentTimeMillis()
                    onUi {
                        val button = activity.findViewById<View>(R.id.btn_connect)
                        check(button.isShown && button.isEnabled && button.performClick()) { "Actual connect button unavailable" }
                    }
                    var confirmationAt = 0L
                    await(50_000, "Actual tunnel connection deadline") {
                        confirmationAt = LogBuffer.snapshot().firstOrNull {
                            it.timestamp >= logStart && it.message.contains("[tunnel-check] roundtrip confirmed")
                        }?.timestamp ?: confirmationAt
                        when (TunnelVpnService.connectionState.value) {
                            is ConnectionState.Error -> error("Actual tunnel returned Error (private details withheld)")
                            is ConnectionState.Connected -> {
                                // Confirmation may have been emitted between the earlier snapshot and state read.
                                if (confirmationAt == 0L) confirmationAt = LogBuffer.snapshot().firstOrNull {
                                    it.timestamp >= logStart && it.message.contains("[tunnel-check] roundtrip confirmed")
                                }?.timestamp ?: 0L
                                check(confirmationAt != 0L) { "Connected appeared without roundtrip confirmation" }
                                check(TunnelVpnService.activeProfileId == id) { "Unexpected automatic profile replacement" }
                                true
                            }
                            else -> false
                        }
                    }
                    row["connectedAfterRoundtrip"] = true
                    row["connectionMs"] = (System.nanoTime() - start) / 1_000_000
                    val pinnedSession = TunnelVpnService.connectionState.value
                    val proxy = checkNotNull(CredentialManager.speedProxy(false)) { "Actual health credentials unavailable" }
                    currentPhase = "speed_button"
                    uiAwait(8_000, "Speed button unavailable after real connection") {
                        val button = activity.findViewById<View>(R.id.btn_speed_test)
                        button.isShown && button.isEnabled
                    }
                    onUi {
                        check(activity.findViewById<View>(R.id.btn_speed_test).performClick())
                        check(vm.speedTesting.value) { "Actual speed button did not start ViewModel" }
                        check(sheet(activity)?.isShowing == true) { "Actual speed button did not open result sheet" }
                    }
                    currentPhase = "measurement"
                    val stages = linkedSetOf<String>()
                    await(75_000, "Button-to-result deadline") {
                        check(CredentialManager.isCurrent(proxy) && TunnelVpnService.activeProfileId == id &&
                            TunnelVpnService.connectionState.value === pinnedSession) { "Actual session changed during test" }
                        vm.speedStage.value?.let { stages.add(it.name) }
                        !vm.speedTesting.value
                    }
                    row["stages"] = stages.toList()
                    val result = vm.speedResult.value
                    row["error"] = vm.speedError.value?.name
                    row["downloadMbps"] = result?.downloadMbps
                    row["uploadMbps"] = result?.uploadMbps
                    row["httpLatencyMs"] = result?.pingMs
                    row["timedOut"] = result?.timedOut
                    currentPhase = "rendered_result"
                    uiAwait(8_000, "Result/error never reached actual sheet") {
                        val dialog = sheet(activity)
                        dialog?.isShowing == true && listOf(R.id.tv_speed_result, R.id.tv_speed_error).any { viewId ->
                            dialog.findViewById<TextView>(viewId)?.let { it.isShown && it.text.isNotBlank() } == true
                        }
                    }
                    row["renderedResultOrError"] = true
                    check(result != null && result.downloadMbps > 0 && result.uploadMbps > 0 && result.pingMs >= 0 &&
                        !result.timedOut && vm.speedError.value == null) { "Incomplete live measurement" }
                    row["pass"] = true
                } catch (t: Throwable) {
                    row["pass"] = false
                    row["failurePhase"] = currentPhase
                    row["failureType"] = t.javaClass.simpleName
                    // Do not expose arbitrary exception text: TLS/core errors may contain private destinations.
                } finally {
                    row["elapsedMs"] = (System.nanoTime() - start) / 1_000_000
                    stop(activity)
                }
            }
            success = rows.size == 2 && rows.all { it["pass"] == true }
            phase = "complete"
        } catch (t: Throwable) {
            notes.add("Failure phase=$phase type=${t.javaClass.simpleName}; details withheld")
        } finally {
            if (admitted) {
                try { stop(a) } catch (t: Throwable) { cleanupOk = false; notes.add("Stop cleanup ${t.javaClass.simpleName}") }
                try { onUi { a?.finish() } } catch (_: Throwable) { cleanupOk = false }
                try {
                    runBlocking {
                        inserted.forEach { app.profileRepository.deleteById(it) }
                        app.database.profileDao().clearSelection()
                        originalSelection.forEach { app.database.profileDao().select(it) }
                    }
                    restorePrefs(prefs!!, savedPrefs!!)
                    check(runBlocking { inserted.none { app.profileRepository.getById(it) != null } })
                    check(prefs!!.all == savedPrefs)
                } catch (t: Throwable) { cleanupOk = false; notes.add("State cleanup ${t.javaClass.simpleName}") }
            }
            if (input.exists() && !input.delete()) { cleanupOk = false; notes.add("Private input cleanup failed") }
            val report = linkedMapOf<String, Any?>("pass" to (success && cleanupOk), "cleanup" to cleanupOk,
                "scope" to "Actual Android UI/ViewModel/VpnService/Xray on owned emulator; not user phone",
                "profiles" to rows, "notes" to notes)
            val json = GsonBuilder().setPrettyPrinting().create().toJson(report)
            File(test.targetContext.getExternalFilesDir(null), "speed-live-safe-results.json").writeText(json)
            test.finish(if (success && cleanupOk) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", "SPEED_LIVE_ANDROID $json\n") })
        }
    }
}
