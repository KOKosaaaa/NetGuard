package com.smarttools.netguard

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.smarttools.netguard.service.WifiAutoConnectManager

/** Outer runner grants/revokes location between invocations on an owned Wi-Fi AVD. */
internal class WifiIdentityAndroidProbe(private val test: Instrumentation) {
    fun run(granted: Boolean) {
        val result = Bundle()
        val manager = WifiAutoConnectManager(test.targetContext)
        var code = Activity.RESULT_CANCELED
        var activity: Activity? = null
        try {
            check(test.targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check((test.targetContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) == granted)
            // A while-in-use grant is not a background location grant. The trusted
            // Wi-Fi dialog reads identity while its Activity is actually visible.
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            test.waitForIdleSync()
            val ssid = manager.getCurrentSsid()
            val bssid = manager.getCurrentBssid()
            if (granted) {
                check(!ssid.isNullOrBlank() && ssid != "<unknown ssid>") { "Wi-Fi SSID still redacted after precise location grant" }
                check(!bssid.isNullOrBlank() && bssid != "02:00:00:00:00:00") { "Wi-Fi BSSID still redacted after precise location grant" }
                // Registration also exercises API31+'s location-aware callback constructor.
                manager.register()
                result.putString("stream", "PASS actual Wi-Fi SSID/BSSID available with precise location on API${android.os.Build.VERSION.SDK_INT}; callback registration succeeds\n")
            } else {
                check(ssid == null && bssid == null) { "Denied precise location exposed a Wi-Fi identity" }
                result.putString("stream", "PASS denied precise location returns no trusted Wi-Fi identity\n")
            }
            code = Activity.RESULT_OK
        } catch (t: Throwable) {
            result.putString("stream", "FAIL Wi-Fi identity: ${t.stackTraceToString()}\n")
        } finally { manager.unregister(); test.runOnMainSync { activity?.finish() } }
        test.finish(code, result)
    }
}
