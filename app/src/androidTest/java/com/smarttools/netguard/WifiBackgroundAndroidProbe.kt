package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.service.TunnelVpnService
import com.smarttools.netguard.service.WifiAutoConnectManager
import java.util.concurrent.atomic.AtomicInteger

/** Actual background Wi-Fi callbacks; injected start admission, never starts a VPN. */
internal class WifiBackgroundAndroidProbe(private val test: Instrumentation) {
    private fun ui(action: () -> Unit) {
        var error: Throwable? = null
        test.runOnMainSync { try { action() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
    }
    private fun await(ms: Long, label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!condition()) { check(System.nanoTime() < end) { label }; Thread.sleep(25) }
    }
    fun run() {
        val app = test.targetContext.applicationContext as App
        val prefs = app.getPreferences()
        val saved = prefs.all.toMap()
        val calls = AtomicInteger()
        var manager: WifiAutoConnectManager? = null
        var ok = false
        val report = StringBuilder("SCOPE actual API35 background Wi-Fi callback; precise location denied; injected VPN-start admission only\n")
        try {
            check(app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(TunnelVpnService.connectionState.value is ConnectionState.Disconnected)
            check(app.wifiAutoConnectManager == null) { "Requires idle owned fixture with auto-connect off" }
            val cm = app.getSystemService(ConnectivityManager::class.java)
            check(cm.allNetworks.any { cm.getNetworkCapabilities(it)?.let { caps -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true })
            check(prefs.edit().putBoolean("auto_connect_wifi", true).putLong("last_profile_id", 424242).commit())
            val testedManager = WifiAutoConnectManager(app, startVpn = { id -> check(id == 424242L); calls.incrementAndGet() }, hasVpnConsent = { true })
            manager = testedManager
            check(testedManager.getCurrentSsid() == null && testedManager.getCurrentBssid() == null) { "Fixture must have redacted Wi-Fi identity" }
            ui { testedManager.register() }
            await(23000, "Redacted background Wi-Fi never admitted auto-connect") { calls.get() == 1 }
            Thread.sleep(1500)
            check(calls.get() == 1) { "Repeated Wi-Fi callback duplicated start admission" }
            ui { testedManager.unregister() }
            report.append("PASS redacted physical Wi-Fi treated as untrusted after bounded retries; exactly one admission, no real VPN launch\n")
            calls.set(0)
            ui { testedManager.register(); testedManager.unregister() }
            Thread.sleep(18500)
            check(calls.get() == 0) { "Unregistered manager launched a stale delayed retry" }
            report.append("PASS unregister cancels actual delayed retries; no start after 18.5 seconds\n")
            ui { testedManager.register() }
            check(prefs.edit().putBoolean("auto_connect_wifi", false).commit())
            Thread.sleep(18500)
            check(calls.get() == 0) { "Disabled setting did not stop delayed admission" }
            report.append("PASS setting disabled during probing prevents start admission\n")
            ok = true
        } catch (t: Throwable) { report.append("FAIL ${t.stackTraceToString()}\n") }
        finally {
            ui { manager?.unregister() }
            val edit = prefs.edit().clear()
            saved.forEach { (k,v) -> when(v) { is String -> edit.putString(k,v); is Boolean -> edit.putBoolean(k,v); is Int -> edit.putInt(k,v); is Long -> edit.putLong(k,v); is Float -> edit.putFloat(k,v); is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(k,v as Set<String>) } } }
            check(edit.commit())
        }
        test.finish(if(ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString()) })
    }
}
