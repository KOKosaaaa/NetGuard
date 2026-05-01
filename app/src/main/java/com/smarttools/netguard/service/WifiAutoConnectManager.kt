package com.smarttools.netguard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smarttools.netguard.App
import com.smarttools.netguard.R
import com.smarttools.netguard.model.ConnectionState

class WifiAutoConnectManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiAutoConnect"
        private const val SEPARATOR = "|"
        private const val CHANNEL_ID = "wifi_security"
        private const val EVIL_TWIN_NOTIF_ID = 9001

        /** Retry schedule for probing SSID when Android gives `<unknown ssid>`. */
        private val PROBE_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)

        /** Encode SSID+BSSID into a single string for storage */
        fun encode(ssid: String, bssid: String): String = "$ssid$SEPARATOR$bssid"

        /** Decode stored entry into (SSID, BSSID?) pair */
        fun decode(entry: String): Pair<String, String?> {
            val idx = entry.indexOf(SEPARATOR)
            return if (idx >= 0) {
                entry.substring(0, idx) to entry.substring(idx + 1)
            } else {
                // Legacy entry — SSID only, no BSSID
                entry to null
            }
        }

        /** Get display name for a trusted entry */
        fun displayName(entry: String): String {
            val (ssid, bssid) = decode(entry)
            return if (bssid != null) "$ssid ($bssid)" else ssid
        }

        /** Get just the SSID from a stored entry */
        fun ssidOf(entry: String): String = decode(entry).first
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Last (SSID, BSSID) pair we acted on. Multiple `onCapabilitiesChanged`
     * events fire throughout a single WiFi association (validation, RSSI,
     * link-speed updates), but our business logic only needs to run when the
     * network identity actually changes. Resetting to null on unregister so a
     * fresh session re-triggers.
     */
    @Volatile
    private var lastHandledKey: String? = null

    /** Main-thread handler used for delayed SSID re-probes. */
    private val probeHandler = Handler(Looper.getMainLooper())

    fun register() {
        if (networkCallback != null) return

        createNotificationChannel()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                handleWifiEvent(caps)
            }

            override fun onLost(network: Network) {
                // Clear the dedup key so that re-associating to the same WiFi
                // after a real disconnect is treated as a fresh event.
                lastHandledKey = null
                probeHandler.removeCallbacksAndMessages(null)
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "WiFi auto-connect registered")
            // Android's registerNetworkCallback does not always replay the
            // current state to a freshly-registered callback. Probe once so a
            // user opening the app while already on WiFi still gets handled.
            probeHandler.post { probeCurrentWifi() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register WiFi callback", e)
        }
    }

    fun unregister() {
        probeHandler.removeCallbacksAndMessages(null)
        networkCallback?.let { cb ->
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
        lastHandledKey = null
        Log.i(TAG, "WiFi auto-connect unregistered")
    }

    /**
     * Resolve SSID/BSSID from the given caps (plus WifiManager fallback for
     * the Android-14 redaction case), then either dispatch to
     * [onWifiConnected] or schedule a delayed re-probe if SSID is still
     * unresolved. The retry avoids losing events when the first
     * `onCapabilitiesChanged` fires before Wi-Fi finishes associating and the
     * subsequent capability updates never arrive (weak-signal roaming).
     */
    private fun handleWifiEvent(caps: NetworkCapabilities) {
        val (capsSsid, capsBssid) = extractWifiInfo(caps)
        val ssid = capsSsid ?: wifiInfoFromManager().first
        val bssid = capsBssid ?: wifiInfoFromManager().second
        if (ssid != null) {
            dispatchIfNew(ssid, bssid)
        } else {
            scheduleProbe(0)
        }
    }

    private fun probeCurrentWifi() {
        val caps = findCurrentWifiCaps()
        val capsInfo = caps?.let { extractWifiInfo(it) }
        val ssid = capsInfo?.first ?: wifiInfoFromManager().first
        val bssid = capsInfo?.second ?: wifiInfoFromManager().second
        if (ssid != null) {
            dispatchIfNew(ssid, bssid)
        }
    }

    private fun scheduleProbe(attempt: Int) {
        if (attempt >= PROBE_DELAYS_MS.size) return
        probeHandler.postDelayed({
            val (ssid, bssid) = wifiInfoFromManager()
            if (ssid != null) {
                dispatchIfNew(ssid, bssid)
            } else {
                scheduleProbe(attempt + 1)
            }
        }, PROBE_DELAYS_MS[attempt])
    }

    private fun dispatchIfNew(ssid: String, bssid: String?) {
        val key = "$ssid${SEPARATOR}${bssid ?: "?"}"
        if (key == lastHandledKey) return
        lastHandledKey = key
        onWifiConnected(ssid, bssid)
    }

    private fun extractWifiInfo(caps: NetworkCapabilities): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps.transportInfo as? WifiInfo
            val ssid = wifiInfo?.ssid?.removeSurrounding("\"")
            val bssid = wifiInfo?.bssid
            if (ssid == null || ssid == "<unknown ssid>" || ssid == "0x") return null to null
            return ssid to bssid
        }
        // Pre-Q fallback
        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo
        val ssid = info?.ssid?.removeSurrounding("\"")
        val bssid = info?.bssid
        if (ssid == null || ssid == "<unknown ssid>" || ssid == "0x") return null to null
        return ssid to bssid
    }

    private fun onWifiConnected(ssid: String, bssid: String?) {
        val app = context.applicationContext as App
        val settings = app.loadSettings()

        if (!settings.autoConnectWifi) return

        // Check against trusted list (SSID+BSSID pairs)
        val trustedEntries = settings.trustedWifiList
        var ssidKnown = false
        var fullyTrusted = false

        for (entry in trustedEntries) {
            val (trustedSsid, trustedBssid) = decode(entry)
            if (trustedSsid == ssid) {
                ssidKnown = true
                if (trustedBssid == null) {
                    // Legacy entry without BSSID — trust by SSID alone
                    fullyTrusted = true
                    break
                }
                if (bssid != null && trustedBssid.equals(bssid, ignoreCase = true)) {
                    fullyTrusted = true
                    break
                }
            }
        }

        if (fullyTrusted) {
            Log.d(TAG, "Trusted WiFi: $ssid ($bssid)")
            return
        }

        // SSID matches a trusted network but BSSID is different → Evil Twin!
        val isEvilTwin = ssidKnown && !fullyTrusted
        if (isEvilTwin) {
            Log.w(TAG, "EVIL TWIN DETECTED: SSID '$ssid' with unknown BSSID $bssid")
            showEvilTwinWarning(ssid, bssid)
        }

        val state = TunnelVpnService.connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
            Log.d(TAG, "VPN already active, skipping")
            return
        }

        val profileId = app.getPreferences().getLong("last_profile_id", -1)
        if (profileId == -1L) {
            Log.w(TAG, "No last profile, cannot auto-connect")
            return
        }

        val reason = if (isEvilTwin) "Evil Twin detected" else "Untrusted WiFi"
        Log.i(TAG, "$reason '$ssid' ($bssid), auto-connecting VPN")
        TunnelVpnService.start(context, profileId)
    }

    private fun showEvilTwinWarning(ssid: String, bssid: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.evil_twin_warning_title))
            .setContentText(context.getString(R.string.evil_twin_warning_text, ssid, bssid ?: "??:??:??:??:??:??"))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.evil_twin_warning_detail, ssid, bssid ?: "??:??:??:??:??:??")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(EVIL_TWIN_NOTIF_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.wifi_security_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.wifi_security_channel_desc)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun getCurrentSsid(): String? {
        findCurrentWifiCaps()?.let {
            val (ssid, _) = extractWifiInfo(it)
            if (ssid != null) return ssid
        }
        return wifiInfoFromManager().first
    }

    fun getCurrentBssid(): String? {
        findCurrentWifiCaps()?.let {
            val (_, bssid) = extractWifiInfo(it)
            if (bssid != null) return bssid
        }
        return wifiInfoFromManager().second
    }

    /**
     * Scan every network the system knows about and return the capabilities
     * of the one backed by WiFi. We explicitly do **not** use
     * `cm.activeNetwork` here: while the NetGuard VPN is running, the active
     * network is the TUN interface, which has no TRANSPORT_WIFI flag, so the
     * old single-lookup path returned null and the "Add current WiFi" entry
     * never appeared in the Trusted WiFi dialog.
     */
    private fun findCurrentWifiCaps(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.transportInfo != null
            ) {
                return caps
            }
        }
        return null
    }

    /**
     * Fallback when the redacted NetworkCapabilities from `allNetworks()` drop
     * `transportInfo`. WifiManager.connectionInfo still works with
     * ACCESS_FINE_LOCATION + NEARBY_WIFI_DEVICES granted, at the cost of a
     * deprecation warning.
     */
    @Suppress("DEPRECATION")
    private fun wifiInfoFromManager(): Pair<String?, String?> {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return null to null
            val rawSsid = info.ssid?.removeSurrounding("\"")
            val bssid = info.bssid
            if (rawSsid.isNullOrEmpty() || rawSsid == "<unknown ssid>" || rawSsid == "0x" ||
                bssid == null || bssid == "02:00:00:00:00:00"
            ) null to null else rawSsid to bssid
        } catch (_: Exception) {
            null to null
        }
    }
}
