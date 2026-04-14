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

    fun register() {
        if (networkCallback != null) return

        createNotificationChannel()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val (ssid, bssid) = extractWifiInfo(caps)
                if (ssid != null) {
                    onWifiConnected(ssid, bssid)
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "WiFi auto-connect registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register WiFi callback", e)
        }
    }

    fun unregister() {
        networkCallback?.let { cb ->
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
        Log.i(TAG, "WiFi auto-connect unregistered")
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
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return extractWifiInfo(caps).first
    }

    fun getCurrentBssid(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return extractWifiInfo(caps).second
    }
}
