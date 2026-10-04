package com.smarttools.netguard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
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
import com.smarttools.netguard.util.LocalizedResources

class WifiAutoConnectManager internal constructor(
    private val context: Context,
    private val startVpn: (Long) -> Unit = { TunnelVpnService.start(context, it) },
    private val hasVpnConsent: () -> Boolean = { VpnService.prepare(context) == null }
) {

    companion object {
        private const val TAG = "WifiAutoConnect"
        private const val SEPARATOR = "|"
        private const val CHANNEL_ID = "wifi_security"
        private const val EVIL_TWIN_NOTIF_ID = 9001

        /** Rename an existing channel without overriding any user channel preferences. */
        fun refreshChannel(context: Context, createIfMissing: Boolean = false) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val localized = LocalizedResources.context(context)
            val name = localized.getString(R.string.wifi_security_channel)
            val channel = nm.getNotificationChannel(CHANNEL_ID) ?: if (createIfMissing)
                NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH) else return
            channel.name = name
            channel.description = localized.getString(R.string.wifi_security_channel_desc)
            nm.createNotificationChannel(channel)
        }

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
    private var registration = 0L
    private var activeNetwork: Network? = null
    private val owner = WifiEventOwner()
    private val probeHandler = Handler(Looper.getMainLooper())
    private var pendingProbe: Runnable? = null

    @Synchronized fun register() {
        if (networkCallback != null) return
        createNotificationChannel()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val serial = ++registration
        val handler = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                probeHandler.post { handleWifiEvent(serial, network, caps) }
            }
            override fun onLost(network: Network) {
                probeHandler.post { handleLost(serial, network) }
            }
        }
        val callback = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = handler.onCapabilitiesChanged(network, caps)
                override fun onLost(network: Network) = handler.onLost(network)
            }
        } else handler
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            networkCallback = callback
            probeHandler.post { probeCurrentWifi(serial) }
            Log.i(TAG, "WiFi auto-connect registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register WiFi callback", e)
        }
    }

    @Synchronized fun unregister() {
        registration++
        cancelProbe()
        owner.reset()
        activeNetwork = null
        networkCallback?.let {
            try { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
            catch (_: Exception) { }
        }
        networkCallback = null
        Log.i(TAG, "WiFi auto-connect unregistered")
    }

    private fun cancelProbe() {
        pendingProbe?.let { probeHandler.removeCallbacks(it) }
        pendingProbe = null
    }

    private fun physicalCaps(network: Network): NetworkCapabilities? =
        context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)?.takeIf {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

    @Synchronized private fun handleLost(serial: Long, network: Network) {
        if (serial != registration || activeNetwork != network) return
        cancelProbe()
        owner.lost(network.toString())
        activeNetwork = null
    }

    @Synchronized private fun probeCurrentWifi(serial: Long) {
        if (serial != registration || networkCallback == null) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        for (network in cm.allNetworks) {
            val caps = physicalCaps(network) ?: continue
            handleWifiEvent(serial, network, caps)
            return
        }
    }

    @Synchronized private fun handleWifiEvent(serial: Long, network: Network, caps: NetworkCapabilities) {
        if (serial != registration || networkCallback == null || physicalCaps(network) == null) return
        if (activeNetwork != network) cancelProbe()
        activeNetwork = network
        val token = owner.observe(network.toString())
        val info = resolveInfo(caps)
        if (info.first != null && info.second != null) {
            cancelProbe()
            dispatchIfNew(network, token, info)
        } else if (pendingProbe == null) {
            scheduleProbe(network, token, 0)
        }
    }

    private fun isCurrent(network: Network, token: WifiEventOwner.Token): Boolean =
        networkCallback != null && activeNetwork == network && owner.current(token) && physicalCaps(network) != null

    /** Exactly one association-owned chain; unknown data eventually uses the conservative policy. */
    private fun scheduleProbe(network: Network, token: WifiEventOwner.Token, attempt: Int) {
        val task = Runnable {
            synchronized(this) {
                if (!isCurrent(network, token)) return@synchronized
                pendingProbe = null
                val info = resolveInfo(physicalCaps(network) ?: return@synchronized)
                if (info.first != null && info.second != null || attempt == PROBE_DELAYS_MS.lastIndex) {
                    dispatchIfNew(network, token, info)
                } else scheduleProbe(network, token, attempt + 1)
            }
        }
        pendingProbe = task
        probeHandler.postDelayed(task, PROBE_DELAYS_MS[attempt])
    }

    private fun resolveInfo(caps: NetworkCapabilities): Pair<String?, String?> {
        val primary = extractWifiInfo(caps)
        // Avoid combining the SSID of one AP with the BSSID of another AP.
        if (primary.first != null && primary.second != null) return primary
        val fallback = wifiInfoFromManager()
        return if (fallback.first != null && fallback.second != null) fallback else primary
    }

    private fun extractWifiInfo(caps: NetworkCapabilities): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return wifiInfoFromManager()
        val info = caps.transportInfo as? WifiInfo
        return WifiSecurityPolicy.ssid(info?.ssid) to WifiSecurityPolicy.bssid(info?.bssid)
    }

    private fun dispatchIfNew(network: Network, token: WifiEventOwner.Token, info: Pair<String?, String?>) {
        if (!isCurrent(network, token)) return
        val key = "${info.first ?: "?"}|${info.second ?: "?"}"
        val now = SystemClock.elapsedRealtime()
        if (!owner.canAttempt(token, key, now)) return
        val app = context.applicationContext as App
        val settings = app.loadSettings()
        if (!settings.autoConnectWifi) return
        val decision = WifiSecurityPolicy.classify(info.first, info.second, settings.trustedWifiList)
        if (decision == WifiSecurityPolicy.Decision.TRUSTED) {
            owner.record(token, key, true, now)
            return
        }
        if (TunnelVpnService.connectionState.value !is ConnectionState.Disconnected) return
        val profileId = app.getPreferences().getLong("last_profile_id", -1)
        if (profileId == -1L) return
        try {
            if (!hasVpnConsent()) {
                owner.record(token, key, false, now)
                Log.w(TAG, "WiFi auto-connect needs prior VPN permission in the app")
                return
            }
            // Recheck every admission constraint immediately before the foreground-service request.
            if (!isCurrent(network, token) || !app.loadSettings().autoConnectWifi ||
                TunnelVpnService.connectionState.value !is ConnectionState.Disconnected) return
            startVpn(profileId)
            owner.record(token, key, true, now)
            if (decision == WifiSecurityPolicy.Decision.DIFFERENT_BSSID) {
                try { showEvilTwinWarning(requireNotNull(info.first), info.second) }
                catch (_: Exception) { Log.w(TAG, "WiFi warning could not be shown") }
            }
            Log.i(TAG, "WiFi auto-connect admitted: $decision")
        } catch (e: Exception) {
            // Background-start rejection must neither crash the callback nor mark this Wi-Fi handled.
            owner.record(token, key, false, now)
            Log.w(TAG, "WiFi auto-connect not admitted: ${e.javaClass.simpleName}")
        }
    }

    private fun showEvilTwinWarning(ssid: String, bssid: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        refreshChannel(context, createIfMissing = true)
        val localized = LocalizedResources.context(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(localized.getString(R.string.evil_twin_warning_title))
            .setContentText(localized.getString(R.string.evil_twin_warning_text, ssid, bssid ?: "??:??:??:??:??:??"))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(localized.getString(R.string.evil_twin_warning_detail, ssid, bssid ?: "??:??:??:??:??:??")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(EVIL_TWIN_NOTIF_ID, notification)
    }

    private fun createNotificationChannel() {
        refreshChannel(context, createIfMissing = true)
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
        // Callers fall back to WifiManager on Android 8/9.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
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
            WifiSecurityPolicy.ssid(info.ssid) to WifiSecurityPolicy.bssid(info.bssid)
        } catch (_: Exception) {
            null to null
        }
    }
}
