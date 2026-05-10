package com.smarttools.netguard.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.smarttools.netguard.App
import com.smarttools.netguard.widget.VpnWidget
import com.smarttools.netguard.util.TrafficFormatter
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.core.ServerPreflight
import com.smarttools.netguard.core.XrayConfigGenerator
import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.model.PerAppMode
import com.smarttools.netguard.model.ServerProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

class TunnelVpnService : VpnService() {

    companion object {
        private const val TAG = "TunnelVpn"
        private const val NOTIFICATION_THROTTLE_MS = 3000L
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        const val ACTION_START = "com.smarttools.netguard.START"
        const val ACTION_STOP = "com.smarttools.netguard.STOP"
        const val ACTION_START_TRIGGER = "com.smarttools.netguard.START_TRIGGER"
        // Quarantine: keep TUN up with allowed=triggerApps but no xray.
        // Trigger apps have their network calls black-holed → no internet
        // until ACTIVATE switches xray+tun2socks on. No leak window.
        const val ACTION_START_QUARANTINE = "com.smarttools.netguard.START_QUARANTINE"
        const val ACTION_ACTIVATE_TRIGGER = "com.smarttools.netguard.ACTIVATE_TRIGGER"
        const val ACTION_DEACTIVATE_TRIGGER = "com.smarttools.netguard.DEACTIVATE_TRIGGER"
        // Pre-warm: full trigger tunnel (xray + tun2socks) running with
        // allowed=triggerApps. No "activate" delay when a trigger app opens.
        const val ACTION_START_TRIGGER_PREWARM = "com.smarttools.netguard.START_TRIGGER_PREWARM"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_TRIGGER_PACKAGE = "trigger_package"

        /** True while the running tunnel was started by trigger logic (vs user-initiated). */
        private val _isTriggerTunnel = MutableStateFlow(false)
        val isTriggerTunnel: StateFlow<Boolean> = _isTriggerTunnel.asStateFlow()

        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _trafficStats = MutableStateFlow(TrafficSnapshot(0L, 0L, 0L, 0L))
        val trafficStats: StateFlow<TrafficSnapshot> = _trafficStats.asStateFlow()

        fun start(context: Context, profileId: Long) {
            val intent = Intent(context, TunnelVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TunnelVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Start tunnel in trigger mode: VPN routes ONLY the specified package.
         * Until xray binds the SOCKS port, packets sit in TUN with no tun2socks
         * to drain them → effectively black-holed (no real-IP leak).
         */
        fun startTrigger(context: Context, profileId: Long, triggerPackage: String) {
            val intent = Intent(context, TunnelVpnService::class.java).apply {
                action = ACTION_START_TRIGGER
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_TRIGGER_PACKAGE, triggerPackage)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Start quarantine: TUN with allowed apps from settings.triggerApps,
         * NO xray, NO tun2socks → those apps have no internet at all.
         * Activate later with ACTION_ACTIVATE_TRIGGER to start the real tunnel.
         */
        fun startQuarantine(context: Context) {
            val intent = Intent(context, TunnelVpnService::class.java).apply {
                action = ACTION_START_QUARANTINE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Pre-warm: full tunnel up with allowed=triggerApps. No activation delay. */
        fun startTriggerPrewarm(context: Context, profileId: Long) {
            val intent = Intent(context, TunnelVpnService::class.java).apply {
                action = ACTION_START_TRIGGER_PREWARM
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun activateTrigger(context: Context) {
            val intent = Intent(context, TunnelVpnService::class.java).apply {
                action = ACTION_ACTIVATE_TRIGGER
            }
            context.startService(intent)
        }

        fun deactivateTrigger(context: Context) {
            val intent = Intent(context, TunnelVpnService::class.java).apply {
                action = ACTION_DEACTIVATE_TRIGGER
            }
            context.startService(intent)
        }
    }

    data class TrafficSnapshot(
        val rxBytes: Long,
        val txBytes: Long,
        val rxSpeed: Long,
        val txSpeed: Long
    )

    private val fdLock = Any()
    // All mutable references below cross thread boundaries: process watchdogs
    // run on Dispatchers.IO while start/stop/onDestroy run on Main. @Volatile
    // (or fdLock for vpnFd) guarantees the cross-thread visibility we need;
    // without it a watchdog could see a stale `null` and skip recovery.
    @Volatile
    private var vpnFd: ParcelFileDescriptor? = null
    @Volatile
    private var serviceScope: CoroutineScope? = null
    @Volatile
    private var trafficMonitor: TrafficMonitor? = null
    @Volatile
    private var xrayProcess: Process? = null
    @Volatile
    private var tun2socksProcess: Process? = null
    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // Secondary callback that fires for ANY non-VPN INTERNET network (not just
    // the system default). On Realme/OPPO/MIUI a VPN with setUnderlyingNetworks
    // pinned to cellular often inhibits the OS-level WiFi-promotion logic, so
    // the default-network callback never fires when WiFi connects. This second
    // callback notices new WiFi/Ethernet/etc. and lets us force a reconnect.
    @Volatile
    private var auxNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var reconnectJob: Job? = null
    // Target network of the in-progress reconnect. We refuse to cancel/restart
    // a reconnect that's already aiming at this same network — otherwise a
    // burst of OEM-spammed onCapabilitiesChanged events keeps cancelling us
    // before we finish the actual restart.
    @Volatile
    private var reconnectTargetNetwork: Network? = null
    @Volatile
    private var currentProfileId: Long = -1
    @Volatile
    private var isReconnecting = false
    @Volatile
    private var isStarting = false
    @Volatile
    private var triggerPackage: String? = null
    @Volatile
    private var quarantineMode: Boolean = false
    @Volatile
    private var triggerActive: Boolean = false
    @Volatile
    private var prewarmMode: Boolean = false

    // Auto-failover state. We keep track of which profile IDs we have already
    // tried during the current user-initiated connect, so a sequence of dead
    // servers from the same subscription does not loop forever and the user
    // sees a useful error after we exhaust the budget.
    private val failoverTried = java.util.concurrent.CopyOnWriteArraySet<Long>()
    @Volatile
    private var failoverInProgress = false
    // Bumped from 3 → 14: under whitelist-mode some profiles (CF tunnel IPs that
    // happen to overlap with whitelisted vk.com / yandex.ru ranges) may pass
    // even when most fail with ECONNREFUSED. Try the entire subscription before
    // surfacing an error.
    private val failoverMaxAttempts = 14
    @Volatile
    private var showSpeedNotification = false
    @Volatile
    private var xrayWatchdogJob: Job? = null
    @Volatile
    private var tun2socksWatchdogJob: Job? = null
    // Reference to the in-progress startTunnel coroutine so a network event
    // arriving mid-connect can cancel it and restart with the new network.
    // Without this, a doomed dial through a dying network keeps running until
    // CONNECTION_TIMEOUT_MS expires and the user stares at "Connecting…".
    @Volatile
    private var startTunnelJob: Job? = null
    // Set to true while we are intentionally killing xray/tun2socks (reconnect,
    // restart, stop). The watchdog checks this BEFORE treating a process exit
    // as a crash — without it, cancel() of the watchdog races with waitFor()
    // returning from our own destroy() and we falsely call recoverXrayCrash().
    @Volatile
    private var intentionalProcessKill = false
    @Volatile
    private var lastNotificationUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * Android 14 (API 34) enforces that every call to [startForeground] for a
     * service with a typed `foregroundServiceType` declaration specifies
     * matching bits at call-site. We use `connectedDevice` (the VPN tunnel
     * role) combined with `systemExempted` (so auto-connect from
     * [BootReceiver] is allowed before the user has foregrounded the app).
     * On API < 29 the typed overload does not exist; fall back to the 2-arg
     * form which the platform happily accepts.
     */
    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: FGS type is mandatory at call site and must match the
            // manifest declaration. `specialUse` requires the subtype property
            // that is declared inside the <service> element. `systemExempted`
            // is OR-ed in so BootReceiver auto-reconnect is permitted.
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // specialUse was added in API 30.
            startForeground(
                NotificationHelper.NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1)
                if (profileId == -1L) {
                    Log.e(TAG, "No profile ID provided")
                    stopSelf()
                    return START_NOT_STICKY
                }
                triggerPackage = null
                quarantineMode = false
                triggerActive = false
                prewarmMode = false
                _isTriggerTunnel.value = false
                // Show "Connecting..." notification immediately to avoid ANR
                val notification = NotificationHelper.createConnectingNotification(this@TunnelVpnService)
                startForegroundCompat(notification)
                startTunnel(profileId)
            }
            ACTION_START_TRIGGER -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1)
                val pkg = intent.getStringExtra(EXTRA_TRIGGER_PACKAGE)
                if (profileId == -1L || pkg.isNullOrBlank()) {
                    Log.e(TAG, "Trigger start: missing profileId or package")
                    stopSelf()
                    return START_NOT_STICKY
                }
                triggerPackage = pkg
                quarantineMode = false
                LogBuffer.add(LogBuffer.LogLevel.INFO, "Trigger start for $pkg (blackhole until xray ready)")
                val notification = NotificationHelper.createConnectingNotification(this@TunnelVpnService)
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
                startTunnel(profileId)
            }
            ACTION_START_QUARANTINE -> {
                quarantineMode = true
                triggerActive = false
                triggerPackage = null
                prewarmMode = false
                _isTriggerTunnel.value = true
                val notification = NotificationHelper.createConnectingNotification(this@TunnelVpnService)
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
                startQuarantineTun()
            }
            ACTION_START_TRIGGER_PREWARM -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1)
                if (profileId == -1L) {
                    Log.e(TAG, "Pre-warm: no profile id")
                    stopSelf()
                    return START_NOT_STICKY
                }
                quarantineMode = false
                triggerActive = false
                triggerPackage = null
                prewarmMode = true
                _isTriggerTunnel.value = true
                val notification = NotificationHelper.createConnectingNotification(this@TunnelVpnService)
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
                LogBuffer.add(LogBuffer.LogLevel.INFO, "Trigger pre-warm: bringing up full tunnel for trigger apps")
                startTunnel(profileId)
            }
            ACTION_ACTIVATE_TRIGGER -> {
                if (!quarantineMode) {
                    // Quarantine got torn down (probably by watchdog after a
                    // process kill). Rebuild it first, then activate.
                    Log.w(TAG, "Activate: quarantine missing, rebuilding then activating")
                    quarantineMode = true
                    triggerActive = false
                    val notification = NotificationHelper.createConnectingNotification(this@TunnelVpnService)
                    startForeground(NotificationHelper.NOTIFICATION_ID, notification)
                    serviceScope?.launch {
                        startQuarantineTun()
                        // Yield once so establishVpn finishes; then activate.
                        kotlinx.coroutines.delay(50)
                        activateTunnelOnQuarantineTun()
                    }
                } else {
                    activateTunnelOnQuarantineTun()
                }
            }
            ACTION_DEACTIVATE_TRIGGER -> {
                if (quarantineMode && triggerActive) {
                    deactivateTunnelKeepQuarantine()
                }
            }
            else -> {
                // Auto-start path: Android Always-on VPN, BootReceiver, or
                // system VpnService re-launch after our process was killed.
                // If trigger mode is enabled, ALWAYS come up in quarantine —
                // even if no profile is selected — so trigger apps remain
                // cut off from the network.
                val app = application as App
                val settings = app.loadSettings()
                if (settings.triggerEnabled && settings.triggerApps.isNotEmpty() &&
                    settings.triggerStrictMode) {
                    val lastProfileId = app.getPreferences().getLong("last_profile_id", -1)
                    val notification = NotificationHelper.createConnectingNotification(this@TunnelVpnService)
                    startForeground(NotificationHelper.NOTIFICATION_ID, notification)
                    if (lastProfileId != -1L) {
                        // Pre-warm full tunnel so trigger apps get instant connectivity.
                        prewarmMode = true
                        quarantineMode = false
                        triggerActive = false
                        triggerPackage = null
                        _isTriggerTunnel.value = true
                        startTunnel(lastProfileId)
                    } else {
                        quarantineMode = true
                        triggerActive = false
                        triggerPackage = null
                        prewarmMode = false
                        _isTriggerTunnel.value = true
                        startQuarantineTun()
                    }
                    TriggerWatcherService.start(this)
                } else if (settings.triggerEnabled && settings.triggerApps.isNotEmpty()) {
                    // Flexible mode: keep the watcher running so triggers still
                    // fire, but don't bring up any tunnel until they do.
                    TriggerWatcherService.start(this)
                    val lastProfileId = app.getPreferences().getLong("last_profile_id", -1)
                    if (lastProfileId != -1L) {
                        val notification = NotificationHelper.createConnectingNotification(this@TunnelVpnService)
                        startForeground(NotificationHelper.NOTIFICATION_ID, notification)
                        startTunnel(lastProfileId)
                    } else {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                } else {
                    val lastProfileId = app.getPreferences()
                        .getLong("last_profile_id", -1)
                    if (lastProfileId != -1L) {
                        val notification = NotificationHelper.createConnectingNotification(this@TunnelVpnService)
                        startForeground(NotificationHelper.NOTIFICATION_ID, notification)
                        startTunnel(lastProfileId)
                    } else {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startTunnel(profileId: Long, skipPreflight: Boolean = false) {
        if (isStarting) {
            Log.w(TAG, "startTunnel called while already starting, ignoring")
            return
        }
        // Reset failover ledger on any user-initiated start. The ledger is
        // additive only inside one auto-failover chain; once the user picks
        // a profile manually we forget the previous chain entirely.
        if (!failoverInProgress) {
            failoverTried.clear()
        }
        failoverTried.add(profileId)
        // Cancel old watchdogs BEFORE killing processes — otherwise they
        // detect the intentional kill, see Connecting state, and call stopTunnel()
        xrayWatchdogJob?.cancel()
        tun2socksWatchdogJob?.cancel()
        // Clean up any existing connection before starting new one
        unregisterNetworkCallback()
        stopTunnelProcesses()

        _connectionState.value = ConnectionState.Connecting
        LogBuffer.add(LogBuffer.LogLevel.INFO, "Starting tunnel...")
        currentProfileId = profileId
        isStarting = true
        // Fresh session — reset crash counter so a new connect attempt isn't
        // pre-loaded with attempts from a previous handover.
        xrayRestartAttempts = 0
        isReconnecting = false

        startTunnelJob?.cancel()
        startTunnelJob = serviceScope?.launch {
            try {
                withTimeout(CONNECTION_TIMEOUT_MS) {
                    val app = application as App
                    val profile = app.database.profileDao().getById(profileId) ?: run {
                        _connectionState.value = ConnectionState.Error("Profile not found")
                        stopSelf()
                        return@withTimeout
                    }

                    app.getPreferences().edit()
                        .putLong("last_profile_id", profileId)
                        .putString("last_profile_name", profile.name)
                        .apply()

                    val settings = app.loadSettings()

                    // 0. Pre-flight reachability check. Informational only — under
                    // RU whitelist mode (БС) plain TCP SYN to foreign IP gets
                    // RST-injected by ТСПУ even when the actual VLESS+Reality
                    // TLS handshake with SNI=max.ru would pass through. v2rayTUN
                    // doesn't preflight and connects fine in that scenario, so
                    // we no longer fail the tunnel on Dead — let xray attempt
                    // the real protocol; it handles its own retries / errors.
                    // DNS rebinding guard inside preflight still runs.
                    //
                    // Skipped on network-handover restarts: preflight burns
                    // ~1-2s of TCP SYN attempts, and the user is already
                    // staring at "ожидание сети" — let xray dial directly.
                    if (!skipPreflight) {
                        when (val pf = ServerPreflight.check(profile)) {
                            is ServerPreflight.Result.Dead -> {
                                LogBuffer.add(LogBuffer.LogLevel.WARN,
                                    "Preflight: ${pf.reason} (non-fatal — let xray try)")
                            }
                            is ServerPreflight.Result.Slow -> {
                                LogBuffer.add(LogBuffer.LogLevel.WARN, "Preflight slow: ${pf.rttMs} ms")
                            }
                            is ServerPreflight.Result.Ok -> {
                                if (pf.rttMs > 0) Log.i(TAG, "Preflight OK ${pf.rttMs} ms")
                            }
                        }
                    }

                    // 1. Establish VPN TUN interface FIRST so trigger apps
                    //    cannot leak their real IP while xray is starting.
                    //    Packets queued into TUN have nowhere to go until
                    //    tun2socks attaches → black-holed (desired for trigger).
                    val fd = establishVpn(profile, settings) ?: run {
                        _connectionState.value = ConnectionState.Error("VPN permission denied")
                        stopTunnel()
                        return@withTimeout
                    }
                    synchronized(fdLock) { vpnFd = fd }

                    // 2. Copy geodata files to xray's working directory
                    copyGeoFiles()

                    // 3. Generate xray config with authenticated SOCKS5 on random port
                    val config = XrayConfigGenerator.generate(
                        profile = profile,
                        settings = settings,
                        useSocksInbound = true
                    )

                    // 4. Write xray config atomically (temp + rename) to prevent corruption
                    val configFile = File(filesDir, "config.json")
                    val tempFile = File(filesDir, "config.json.tmp")
                    tempFile.writeText(config.json)
                    tempFile.renameTo(configFile)
                    Log.i(TAG, "Config written, SOCKS port=${config.socksPort}")

                    // 5. Start xray-core process, ensure config deleted even on failure
                    try {
                        startXrayProcess(configFile)

                        // 5a. Wait for xray to bind the SOCKS port
                        waitForPort(config.socksPort ?: throw IllegalStateException("SOCKS port not generated"), timeoutMs = 5000)
                        Log.i(TAG, "Xray is listening on port ${config.socksPort}")
                    } finally {
                        // 5b. Delete config from disk — xray already read it into memory
                        configFile.delete()
                        Log.i(TAG, "Config file deleted from disk")
                    }

                    // 6. Start tun2socks: TUN fd → authenticated SOCKS5
                    val socksPort = config.socksPort ?: throw IllegalStateException("SOCKS port not generated")
                    val socksUser = config.socksUser ?: throw IllegalStateException("SOCKS user not generated")
                    val socksPass = config.socksPass ?: throw IllegalStateException("SOCKS pass not generated")
                    startTun2socksProcess(fd, socksPort, socksUser, socksPass)

                    // 7a. Credentials kept alive for speed test/service tester
                    // They are cleared in stopTunnel() / stopTunnelProcesses()

                    // 8. Read speed notification preference
                    showSpeedNotification = settings.showSpeedInNotification

                    // 9. Start traffic monitor
                    trafficMonitor = TrafficMonitor().also { monitor ->
                        monitor.start(serviceScope!!) { snapshot ->
                            _trafficStats.value = snapshot
                            if (showSpeedNotification) {
                                val now = System.currentTimeMillis()
                                if (now - lastNotificationUpdate >= NOTIFICATION_THROTTLE_MS) {
                                    lastNotificationUpdate = now
                                    NotificationHelper.updateSpeedNotification(this@TunnelVpnService, snapshot.rxSpeed, snapshot.txSpeed)
                                }
                            }
                        }
                    }

                    // 10. Monitor processes for unexpected death
                    launchProcessWatchdog()

                    // 11. Register network change listener for auto-reconnect
                    registerNetworkCallback()

                    _connectionState.value = ConnectionState.Connected()
                    // Update notification from "Connecting..." to "Connected"
                    NotificationHelper.showConnectedNotification(this@TunnelVpnService)
                    VpnWidget.updateAllWidgets(applicationContext)
                    // Successful tunnel resets the failover ledger so a future
                    // unrelated failure doesn't reuse the in-progress chain.
                    failoverInProgress = false
                    failoverTried.clear()
                    failoverTried.add(profileId)
                    Log.i(TAG, "Tunnel started for ${profile.name}")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Connection timed out after ${CONNECTION_TIMEOUT_MS / 1000}s")
                handleConnectFailure("Connection timed out", profileId)
            } catch (e: CancellationException) {
                // Cancelled by scheduleReconnect during a network handover —
                // a new startTunnel is about to take over. Don't treat this
                // as a failure: don't change state, don't trigger failover.
                Log.i(TAG, "startTunnel cancelled (handover in progress)")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tunnel", e)
                handleConnectFailure(e.message ?: "Unknown error", profileId)
            } finally {
                isStarting = false
            }
        }
    }

    /**
     * Pick the next failover candidate and re-enter [startTunnel]. We prefer
     * untried profiles from the same subscription (so that the user stays on
     * the kind of server they explicitly chose); if none are left we fall
     * back to any other untried profile in the database. After
     * [failoverMaxAttempts] tries we give up and surface the original error.
     */
    /**
     * Translate the raw transport-layer error into a user-readable hint when
     * the pattern strongly suggests a Russian whitelist (БС) mode being active:
     * mass ECONNREFUSED on every server in a row means the operator is RST-ing
     * SYN packets at L3 before they reach our infrastructure, so no profile we
     * try will work — surfacing "Server unreachable" repeatedly is misleading.
     */
    private fun maybeWhitelistHint(reason: String): String {
        val lower = reason.lowercase()
        val refused = lower.contains("econnrefused") || lower.contains("connection refused")
        val timeout = lower.contains("timed out") || lower.contains("etimedout")
        val burned = failoverTried.size >= 3
        return if (burned && (refused || timeout)) {
            "БС-режим? Все серверы недоступны на L3 (RST/timeout). " +
                "VPN не пройдёт пока оператор не снимет белые списки. " +
                "Попробуй Wi-Fi. ($reason)"
        } else reason
    }

    private fun handleConnectFailure(reason: String, failedProfileId: Long) {
        VpnWidget.updateAllWidgets(applicationContext)
        if (failoverTried.size >= failoverMaxAttempts) {
            val finalMsg = maybeWhitelistHint(reason)
            Log.w(TAG, "Failover budget exhausted (${failoverTried.size}/$failoverMaxAttempts); giving up: $finalMsg")
            _connectionState.value = ConnectionState.Error(finalMsg)
            failoverInProgress = false
            failoverTried.clear()
            stopTunnel()
            return
        }
        serviceScope?.launch {
            try {
                val app = application as App
                val failed = app.database.profileDao().getById(failedProfileId)
                val candidates = if (failed != null && failed.subscriptionId > 0) {
                    app.database.profileDao().getBySubscription(failed.subscriptionId)
                } else {
                    app.database.profileDao().getAll()
                }
                val next = candidates.firstOrNull { it.id !in failoverTried }
                    ?: app.database.profileDao().getAll().firstOrNull { it.id !in failoverTried }
                if (next == null) {
                    val finalMsg = maybeWhitelistHint(reason)
                    Log.w(TAG, "No failover candidates left; surfacing error: $finalMsg")
                    _connectionState.value = ConnectionState.Error(finalMsg)
                    failoverInProgress = false
                    failoverTried.clear()
                    stopTunnel()
                    return@launch
                }
                LogBuffer.add(LogBuffer.LogLevel.WARN, "Failover → ${next.name} (after \"$reason\")")
                Log.i(TAG, "Auto-failover to profile ${next.id} (${next.name}); attempt ${failoverTried.size + 1}/$failoverMaxAttempts")
                failoverInProgress = true
                stopTunnelProcesses()
                startTunnel(next.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failover lookup failed", e)
                _connectionState.value = ConnectionState.Error(reason)
                failoverInProgress = false
                stopTunnel()
            }
        }
    }

    private fun copyGeoFiles() {
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val outFile = File(filesDir, name)
            if (outFile.exists() && outFile.length() > 0) continue
            val tempFile = File(filesDir, "$name.tmp")
            try {
                assets.open(name).use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.renameTo(outFile)
                Log.i(TAG, "Copied $name (${outFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Error copying $name", e)
                tempFile.delete()
            }
        }
    }

    private fun startXrayProcess(configFile: File) {
        val xrayBin = File(applicationInfo.nativeLibraryDir, "libxray.so")
        if (!xrayBin.exists()) throw IllegalStateException("xray binary not found at ${xrayBin.absolutePath}")

        val pb = ProcessBuilder(
            xrayBin.absolutePath,
            "run",
            "-config", configFile.absolutePath
        )
        pb.directory(filesDir)
        pb.environment()["XRAY_LOCATION_ASSET"] = filesDir.absolutePath
        pb.redirectErrorStream(true)

        xrayProcess = pb.start()
        Log.i(TAG, "Xray process started, pid=${getPid(xrayProcess!!)}")

        // Log xray stdout/stderr in background. Redact BEFORE Log.d so server
        // addresses / UUIDs / passwords don't end up in raw logcat.
        serviceScope?.launch(Dispatchers.IO) {
            try {
                xrayProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    val safe = LogBuffer.redactPublic(line)
                    Log.d("XrayCore", safe)
                    LogBuffer.add(LogBuffer.parseXrayLevel(line), line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading xray output: ${e.message}")
            }
        }
    }

    private suspend fun startTun2socksProcess(fd: ParcelFileDescriptor, port: Int, user: String, pass: String) {
        val tun2socksBin = File(applicationInfo.nativeLibraryDir, "libtun2socks.so")
        if (!tun2socksBin.exists()) throw IllegalStateException("tun2socks binary not found at ${tun2socksBin.absolutePath}")

        val sockPath = File(filesDir, "sock_path").absolutePath
        // tun2socks --tunmtu must match the TUN MTU set by VpnService.Builder,
        // otherwise the helper fragments above the link MTU and we lose the
        // benefit of probing the underlying network.
        val tunMtu = probeUnderlyingMtu()

        // badvpn-tun2socks: receives TUN fd via Unix domain socket.
        // FIXME(security): --username/--password are visible in /proc/<pid>/cmdline.
        // SELinux + hidepid hide this from other apps on stock Android, but same-uid
        // processes and root can read it. Migrate to stdin pipe or fd-based creds
        // once the tun2socks fork supports it (see README security-hardening section).
        val pb = ProcessBuilder(
            tun2socksBin.absolutePath,
            "--netif-ipaddr", "10.10.10.2",
            "--netif-netmask", "255.255.255.252",
            "--socks-server-addr", "127.0.0.1:$port",
            "--username", user,
            "--password", pass,
            "--tunmtu", tunMtu.toString(),
            "--sock-path", sockPath,
            "--enable-udprelay",
            "--loglevel", "notice"
        )
        pb.directory(filesDir)
        pb.redirectErrorStream(true)

        tun2socksProcess = pb.start()
        Log.i(TAG, "tun2socks started, sock=$sockPath, socks=127.0.0.1:$port, auth=yes")

        // Log output in background. Same redaction story as xray.
        serviceScope?.launch(Dispatchers.IO) {
            try {
                tun2socksProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    val safe = LogBuffer.redactPublic(line)
                    Log.d("tun2socks", safe)
                    LogBuffer.add(LogBuffer.LogLevel.INFO, "[tun2socks] $line")
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "Error reading tun2socks output: ${e.message}")
                }
            }
        }

        // Send TUN fd to tun2socks via Unix socket
        sendTunFd(sockPath, fd)
    }

    private suspend fun sendTunFd(sockPath: String, fd: ParcelFileDescriptor) {
        // Tight poll: tun2socks usually creates the socket within ~50ms.
        // Use suspend delay() so we don't block a Dispatchers.IO worker.
        var connected = false
        for (attempt in 1..40) {
            delay(50)
            val sockFile = File(sockPath)
            if (sockFile.exists()) {
                connected = true
                break
            }
        }
        if (!connected) {
            Log.w(TAG, "tun2socks socket not found after 2s, trying anyway")
        }

        val sock = android.net.LocalSocket()
        try {
            sock.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
            sock.setFileDescriptorsForSend(arrayOf(fd.fileDescriptor))
            sock.outputStream.write(42) // dummy byte triggers SCM_RIGHTS
            sock.outputStream.flush()
            Log.i(TAG, "TUN fd sent to tun2socks via Unix socket")
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private suspend fun waitForPort(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 50)
                }
                return // port is open
            } catch (_: Exception) {
                delay(30)
            }
        }
        Log.w(TAG, "Timeout waiting for port $port, proceeding anyway")
    }

    private fun launchProcessWatchdog() {
        xrayWatchdogJob?.cancel()
        tun2socksWatchdogJob?.cancel()
        // We're about to take responsibility for the new processes — clear the
        // intentional-kill shield so a real crash IS observed.
        intentionalProcessKill = false

        xrayWatchdogJob = serviceScope?.launch(Dispatchers.IO) {
            try {
                val exitCode = xrayProcess?.waitFor() ?: return@launch
                if (intentionalProcessKill || isReconnecting) return@launch
                val state = _connectionState.value
                if (isActive && (state is ConnectionState.Connected || state is ConnectionState.Connecting)) {
                    Log.e(TAG, "Xray died (exit $exitCode); attempting recover")
                    withContext(Dispatchers.Main) { recoverXrayCrash(exitCode) }
                }
            } catch (_: Exception) {}
        }
        tun2socksWatchdogJob = serviceScope?.launch(Dispatchers.IO) {
            try {
                val exitCode = tun2socksProcess?.waitFor() ?: return@launch
                if (intentionalProcessKill || isReconnecting) return@launch
                val state = _connectionState.value
                if (isActive && (state is ConnectionState.Connected || state is ConnectionState.Connecting)) {
                    Log.e(TAG, "tun2socks died (exit $exitCode)")
                    withContext(Dispatchers.Main) {
                        _connectionState.value = ConnectionState.Error("tun2socks exited ($exitCode)")
                        stopTunnel()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    @Volatile private var xrayRestartAttempts = 0

    /**
     * Try to restart xray (up to 3 times) instead of tearing the whole tunnel
     * down. The TUN stays up; if xray comes back, traffic resumes seamlessly.
     */
    private fun recoverXrayCrash(exitCode: Int) {
        // If a network-change reconnect is already pending, defer to it.
        // Trying to revive xray on a network that's about to be replaced is
        // pointless (the new dial will fail too) and burns through our 3
        // attempts in seconds — after which we call stopTunnel() and tear
        // the whole tunnel down right when the user expected a graceful
        // handover.
        if (isReconnecting || intentionalProcessKill) {
            Log.i(TAG, "Xray exited ($exitCode) but reconnect is pending — skipping recover")
            return
        }
        xrayRestartAttempts++
        if (xrayRestartAttempts > 3) {
            Log.e(TAG, "Xray crashed $xrayRestartAttempts times, giving up")
            _connectionState.value = ConnectionState.Error("Xray crashed repeatedly")
            // In trigger mode, NEVER drop the TUN — that would let trigger apps
            // leak their real IP. Fall back to quarantine (TUN up, no xray) so
            // their packets stay black-holed until the user reconnects.
            if (prewarmMode || quarantineMode || triggerPackage != null) {
                Log.w(TAG, "Trigger mode: keeping TUN up as blackhole quarantine")
                triggerActive = false
                prewarmMode = false
                quarantineMode = true
                xrayWatchdogJob?.cancel()
                tun2socksWatchdogJob?.cancel()
                trafficMonitor?.stop(); trafficMonitor = null
                tun2socksProcess?.let { p -> try { p.destroy() } catch (_: Exception) {}; try { p.destroyForcibly() } catch (_: Exception) {} }
                tun2socksProcess = null
                xrayProcess?.let { p -> try { p.destroy() } catch (_: Exception) {}; try { p.destroyForcibly() } catch (_: Exception) {} }
                xrayProcess = null
                CredentialManager.clear()
                NotificationHelper.showQuarantineNotification(this)
            } else {
                stopTunnel()
            }
            return
        }
        if (currentProfileId == -1L) {
            stopTunnel()
            return
        }
        Log.i(TAG, "Restarting xray (attempt $xrayRestartAttempts/3)")
        LogBuffer.add(LogBuffer.LogLevel.INFO, "Xray died ($exitCode), restarting…")
        serviceScope?.launch {
            try {
                kotlinx.coroutines.delay(500L * xrayRestartAttempts)
                val app = application as App
                val profile = app.database.profileDao().getById(currentProfileId) ?: run {
                    stopTunnel(); return@launch
                }
                val settings = app.loadSettings()

                // Kill tun2socks too — it'll need new SOCKS creds.
                tun2socksWatchdogJob?.cancel()
                tun2socksProcess?.let { p -> try { p.destroy() } catch (_: Exception) {} ; try { p.destroyForcibly() } catch (_: Exception) {} }
                tun2socksProcess = null
                CredentialManager.clear()

                val config = XrayConfigGenerator.generate(profile, settings, useSocksInbound = true)
                val configFile = File(filesDir, "config.json")
                File(filesDir, "config.json.tmp").apply {
                    writeText(config.json)
                    renameTo(configFile)
                }
                try {
                    startXrayProcess(configFile)
                    waitForPort(config.socksPort ?: throw IllegalStateException("port"), 5000)
                } finally { configFile.delete() }

                val fd = synchronized(fdLock) { vpnFd?.takeIf { it.fileDescriptor.valid() } } ?: run {
                    stopTunnel(); return@launch
                }
                startTun2socksProcess(
                    fd,
                    config.socksPort ?: return@launch,
                    config.socksUser ?: return@launch,
                    config.socksPass ?: return@launch
                )
                launchProcessWatchdog()
                _connectionState.value = ConnectionState.Connected()
                NotificationHelper.showConnectedNotification(this@TunnelVpnService)
                // Decay restart counter after a successful run
                kotlinx.coroutines.delay(60_000)
                xrayRestartAttempts = 0
            } catch (e: Exception) {
                Log.e(TAG, "Xray restart failed", e)
                stopTunnel()
            }
        }
    }

    /**
     * Probe the active non-VPN underlying network for its link MTU. On many
     * mobile carriers the LTE/5G link MTU is 1428 or 1450, not 1500;
     * forcing 1500 on the TUN causes silent IP fragmentation and a 5–15 %
     * throughput drop. We always cap at 1500 (TUN payload max) and floor
     * at 1280 (smallest IPv6 MTU; below that we'd black-hole v6 outright).
     */
    private fun probeUnderlyingMtu(): Int {
        val standard = 1500
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return standard
            val lp = cm.getLinkProperties(net) ?: return standard
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) lp.mtu else 0
            if (raw <= 0) standard else raw.coerceIn(1280, standard)
        } catch (e: Exception) {
            Log.w(TAG, "MTU probe failed: ${e.message}")
            standard
        }
    }

    private fun establishVpn(profile: ServerProfile, settings: AppSettings): ParcelFileDescriptor? {
        val primaryDns = profile.dns.ifEmpty { settings.primaryDns }
        val secondaryDns = settings.secondaryDns
        val probedMtu = probeUnderlyingMtu()
        Log.i(TAG, "TUN MTU = $probedMtu (probed)")

        val builder = Builder()
            .addAddress("10.10.10.1", 30)
            .addRoute("0.0.0.0", 0)
            .apply {
                if (settings.enableIpv6) {
                    addAddress("fd00::1", 126)
                    addRoute("::", 0)
                }
            }
            .setMtu(probedMtu)      // probed from underlying link, capped 1280..1500
            .setSession("")         // CRITICAL: empty session — not "VPN"
            .setBlocking(false)
            // CRITICAL: mark as not metered so Android doesn't prefer non-VPN routes
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setMetered(false)
                }
            }

        // In quarantine (no xray running) we deliberately omit DNS servers
        // so trigger apps' DNS resolver fails fast (EAI_AGAIN). With DNS
        // servers set, packets just sit in TUN for ~30s until kernel timeout
        // — that's why apps say "Connecting…" instead of "No network".
        // When the tunnel becomes active, xray ignores DNS-server hints
        // anyway (we route through SOCKS5).
        // Add DNS unless we're in quarantine without an active tunnel.
        // Prewarm runs xray, so DNS is needed.
        //
        // VpnService.Builder.addDnsServer applies SYSTEM-WIDE — including
        // to apps that are in the disallowed-app list. So we must pick DNS
        // servers that are reachable from BOTH the VPN exit AND the user's
        // local ISP (otherwise excluded apps lose DNS). 1.1.1.1 (Cloudflare)
        // is blocked by some RU ISPs → blacklisted apps fail to resolve.
        //
        // Strategy: pick the user-configured DNS first, then add 77.88.8.8
        // (Yandex Public DNS) as a guaranteed-RU-reachable fallback.
        // Whether to set Builder DNS at all. Skipping it lets blacklisted
        // apps fall back to their own network's DNS (router/ISP), which
        // always works locally. xray inbound has DNS sniffing enabled, so
        // in-VPN apps still resolve through the tunnel even without
        // Builder DNS.
        val hasBlacklist = triggerPackage == null && !quarantineMode && !prewarmMode &&
            settings.perAppMode == PerAppMode.BLACKLIST &&
            settings.perAppList.isNotEmpty()
        if ((!quarantineMode || triggerActive || prewarmMode) && !hasBlacklist) {
            try { builder.addDnsServer("77.88.8.8") } catch (_: Exception) {}
            try { builder.addDnsServer("8.8.8.8") } catch (_: Exception) {}
            Log.i(TAG, "DNS list: 77.88.8.8, 8.8.8.8")
        } else if (hasBlacklist) {
            Log.i(TAG, "BLACKLIST mode: no Builder DNS — apps use their own network DNS")
        }

        val trigger = triggerPackage
        if (quarantineMode || prewarmMode || trigger != null) {
            // Trigger / quarantine / prewarm: route ONLY trigger apps through TUN.
            val pkgs: Collection<String> = when {
                trigger != null -> listOf(trigger)
                else -> settings.triggerApps
            }
            if (pkgs.isEmpty()) {
                Log.e(TAG, "Trigger/quarantine mode with no apps")
                return null
            }
            var added = 0
            pkgs.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                    added++
                } catch (e: Exception) {
                    Log.w(TAG, "Trigger pkg not found: $pkg")
                }
            }
            if (added == 0) {
                Log.e(TAG, "No trigger packages installed on this device")
                return null
            }
            Log.i(TAG, "Trigger mode: $added apps routed through VPN (quarantine=$quarantineMode)")
        } else if (settings.perAppMode == PerAppMode.BLACKLIST && settings.perAppList.isNotEmpty()) {
            // Classic blacklist: just addDisallowedApplication. Android's
            // standard per-app VPN routes those UIDs around the tunnel via
            // the system default route. No allowBypass — it conflicted on
            // some OEM builds and made things worse.
            settings.perAppList.forEach { pkg ->
                try { builder.addDisallowedApplication(pkg) } catch (e: Exception) {
                    Log.w(TAG, "Package not found for blacklist: $pkg")
                }
            }
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            Log.i(TAG, "BLACKLIST: ${settings.perAppList.size} apps bypassing VPN")
        } else {
            when (settings.perAppMode) {
                PerAppMode.WHITELIST -> {
                    settings.perAppList.forEach { pkg ->
                        try { builder.addAllowedApplication(pkg) } catch (e: Exception) {
                            Log.w(TAG, "Package not found for whitelist: $pkg")
                        }
                    }
                }
                PerAppMode.BLACKLIST -> {
                    // Handled above (with allowBypass) when list is non-empty.
                    // Empty BLACKLIST = same as DISABLED, fall through.
                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                }
                PerAppMode.DISABLED -> {
                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                }
            }
        }

        val fd = builder.establish() ?: return null

        publishUnderlyingNetworks()
        return fd
    }

    /**
     * Publish the underlying network that actually carries VPN traffic.
     *
     * We intentionally pass ONLY ONE network (the one xray's protected socket
     * is using) — not all non-VPN networks. The reason: Android merges the
     * NetworkCapabilities of every underlying network into the VPN's own
     * capabilities. If we publish [WiFi, cellular], the VPN inherits
     * CELLULAR|WIFI transports AND becomes METERED (because cellular is
     * metered and METERED is the conservative merge). Apps then think
     * "you're on mobile data" and refuse to stream / show warnings, even
     * though packets actually ride WiFi.
     *
     * Selection priority:
     *   1. Ethernet (validated > unvalidated)
     *   2. WiFi (validated > unvalidated)
     *   3. Cellular (validated > unvalidated)
     *
     * This matches Android's own default-network selection, so the network
     * we publish is the same one xray's protect()'d sockets follow.
     */
    /**
     * @param preferred Network the caller is sure is current (from a callback
     *   parameter). When set, we trust it over [ConnectivityManager.activeNetwork],
     *   which can return a lingering handle for ~1s after a default switch on
     *   some OEMs. Pass null when you don't have a callback-fresh handle.
     * @param avoid Network we KNOW is dead/no-longer-default (typically the
     *   onLost arg). We never pick it even if cm.activeNetwork still returns
     *   it from a stale cache.
     */
    /**
     * Last non-VPN network we successfully published. Used as a fallback when a
     * fresh `publishUnderlyingNetworks` call momentarily can't find any candidate
     * (e.g., right after a TUN re-establish, when the system briefly reports our
     * own VPN as the default and `cm.allNetworks` hasn't re-indexed yet). Better
     * to keep the previously-known underlying than to leave VPN sockets bound to
     * nothing — the alternative is the routing-tar-pit we saw in v1.2.16 logs.
     */
    @Volatile
    private var lastPublishedNetwork: Network? = null

    private fun pickUnderlyingNetwork(
        cm: ConnectivityManager,
        preferred: Network?,
        avoid: Network?
    ): Network? {
        // If `preferred` is our own VPN, ignore it — that's a callback echo,
        // not a real underlying network.
        val cleanedPreferred = preferred?.let { net ->
            val caps = cm.getNetworkCapabilities(net)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) null
            else net
        }
        val candidate: Network? = cleanedPreferred ?: cm.activeNetwork
        candidate?.let { net ->
            if (net == avoid) return@let
            val caps = cm.getNetworkCapabilities(net) ?: return@let
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@let
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@let
            return net
        }
        return cm.allNetworks.mapNotNull { net ->
            if (net == avoid) return@mapNotNull null
            val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            net to caps
        }.maxByOrNull { (_, caps) ->
            var score = 0
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 300
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 200
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 100
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 10
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) score += 5
            score
        }?.first
    }

    private fun publishUnderlyingNetworks(preferred: Network? = null, avoid: Network? = null) {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val chosen = pickUnderlyingNetwork(cm, preferred, avoid)
            if (chosen == null) {
                // No candidate right now. Two paths:
                // 1) We have a memo of the last-published non-VPN network and it's
                //    still valid (not equal to `avoid`, still has INTERNET) — keep
                //    it published rather than leaving the VPN bound to nothing.
                //    This covers the post-TUN-restart hole where the default
                //    callback briefly reports our own VPN.
                // 2) Otherwise schedule a short async retry: capabilities lag the
                //    network appearing by 50–300ms on most OEMs.
                val memo = lastPublishedNetwork
                val memoUsable = memo != null && memo != avoid && run {
                    val caps = cm.getNetworkCapabilities(memo)
                    caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                if (memoUsable && memo != null) {
                    Log.w(TAG, "publishUnderlyingNetworks: no fresh candidate, retaining $memo")
                    setUnderlyingNetworks(arrayOf(memo))
                    return
                }
                Log.w(TAG, "publishUnderlyingNetworks: no usable network — scheduling retry (avoid=$avoid)")
                serviceScope?.launch {
                    repeat(8) { i ->
                        delay(150)
                        val retry = pickUnderlyingNetwork(cm, null, avoid)
                        if (retry != null) {
                            try {
                                setUnderlyingNetworks(arrayOf(retry))
                                lastPublishedNetwork = retry
                                Log.i(TAG, "Underlying network (retry #${i + 1}): $retry")
                            } catch (_: Exception) {}
                            return@launch
                        }
                    }
                    Log.w(TAG, "publishUnderlyingNetworks: retry exhausted, still no usable network")
                }
                return
            }
            setUnderlyingNetworks(arrayOf(chosen))
            lastPublishedNetwork = chosen
            Log.i(TAG, "Underlying network: $chosen (preferred=${preferred != null}, avoid=$avoid)")
        } catch (e: Exception) {
            Log.w(TAG, "setUnderlyingNetworks failed: ${e.message}")
        }
    }

    /**
     * Network handover detection — v1.2.18 redesign.
     *
     * Why the v1.2.16/v1.2.17 design was broken:
     * `registerDefaultNetworkCallback` reports the default network for THIS
     * UID. For a running VpnService, that's the VPN itself — so the default
     * callback only ever fires with our own VPN network, which is useless for
     * tracking the underlying transport (WiFi/cellular) carrying VPN packets.
     * The previous code's SIM-swap, validated-flip, transport-changed logic
     * was guarded by `if (network != currentNetwork) return` — and
     * currentNetwork was either VPN-self (poisoned) or null after Fix #4 —
     * so none of those events ever ran in practice.
     *
     * v1.2.18: aux callback (filtering out VPN) is the SOLE source of truth
     * for what's underneath us. It tracks per-network state, detects the
     * three real handover events (better network appeared, our underlying
     * lost, cellular regained validation after tower loss), and drives
     * publishUnderlyingNetworks + scheduleReconnect directly.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Default callback kept alive only for diagnostic logging — the
        // events here are dominated by VPN-self echoes and aren't actionable.
        // We do NOT make handover decisions from this callback.
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                Log.i(TAG, "default onAvailable: $network (vpn=$isVpn)")
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "default onLost: $network")
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback!!)
        registerAuxNetworkCallback(cm)
    }

    private fun scoreCaps(caps: NetworkCapabilities): Int {
        var s = 0
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s += 300
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s += 200
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s += 100
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 10
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) s += 5
        return s
    }

    /**
     * Watch every non-VPN INTERNET network. This is the real handover detector.
     *
     * Triggers a reconnect when:
     *  - A network appears whose score beats `lastPublishedNetwork`
     *    (e.g., WiFi connects while we're on cellular).
     *  - Our current `lastPublishedNetwork` is lost (e.g., WiFi turned off).
     *  - The cellular underlying loses then regains validation
     *    (e.g., tower hiccup; same Network object survives but TCP died).
     *  - SIM subscription id changes on the cellular underlying.
     */
    private fun registerAuxNetworkCallback(cm: ConnectivityManager) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        auxNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            // Per-network state cache. We compare each onCapabilitiesChanged
            // against the prior snapshot for that exact Network to decide
            // what (if anything) actually changed.
            private val knownNet =
                java.util.concurrent.ConcurrentHashMap<Network, NetState>()

            override fun onAvailable(network: Network) {
                Log.i(TAG, "aux onAvailable: $network")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return

                val newTransports = encodeTransports(caps)
                val newSubId = extractSubId(caps)
                val nowValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val prev = knownNet[network]
                knownNet[network] = NetState(newSubId, newTransports, nowValidated)

                val current = lastPublishedNetwork
                val isOurUnderlying = (network == current)

                // 1) Cellular tower-loss recovery: same Network, regained
                //    validation. xray's TCP sockets through this transport
                //    died during the gap and won't recover on their own.
                if (isOurUnderlying
                    && prev != null
                    && !prev.validated
                    && nowValidated
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                ) {
                    Log.i(TAG, "aux: cellular underlying $network regained validation — reconnecting")
                    LogBuffer.add(LogBuffer.LogLevel.INFO, "Сеть восстановлена — переподключение…")
                    publishUnderlyingNetworks(preferred = network)
                    scheduleReconnect("cellular re-validated $network", network, cm)
                    return
                }

                // 2) SIM swap on the underlying.
                if (isOurUnderlying
                    && prev != null
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    && newSubId != prev.subId
                    && (prev.subId != -1 || newSubId != -1)
                ) {
                    Log.i(TAG, "aux: SIM swap on underlying ${prev.subId} → $newSubId")
                    scheduleReconnect("SIM swap on underlying", network, cm)
                    return
                }

                // 3) Transports changed on the underlying (rare but possible).
                if (isOurUnderlying
                    && prev != null
                    && newTransports != prev.transports
                ) {
                    Log.i(TAG, "aux: transports changed on underlying $network")
                    scheduleReconnect("transports changed on underlying", network, cm)
                    return
                }

                // 4) A different network is now strictly better than what we
                //    publish. Switch to it. Compare scores using current caps
                //    (the aux update we just got) vs. capabilities of
                //    lastPublishedNetwork (re-fetched, since they may have
                //    drifted since we published).
                if (!isOurUnderlying && nowValidated) {
                    val newScore = scoreCaps(caps)
                    val curScore = current
                        ?.let { cm.getNetworkCapabilities(it) }
                        ?.let(::scoreCaps)
                        ?: -1
                    if (newScore > curScore) {
                        Log.i(TAG, "aux: switching underlying $current ($curScore) → $network ($newScore)")
                        LogBuffer.add(LogBuffer.LogLevel.INFO, "Найдена лучшая сеть — переключаемся…")
                        publishUnderlyingNetworks(preferred = network)
                        scheduleReconnect("aux promotion to $network", network, cm)
                        return
                    }
                }

                // 5) First time we see this network as our underlying (e.g., chosen by
                //    pickUnderlyingNetwork at establishVpn) — record state and move on.
                //    No action; we already published it.
            }

            override fun onLost(network: Network) {
                knownNet.remove(network)
                val wasOurUnderlying = (network == lastPublishedNetwork)
                Log.i(TAG, "aux onLost: $network (was-underlying=$wasOurUnderlying)")
                if (!wasOurUnderlying) return

                // Our underlying just died. Find a replacement. Skip the lost
                // network in the picker (avoid=network) so cm.activeNetwork's
                // stale cache doesn't hand it back.
                val replacement = pickUnderlyingNetwork(cm, null, network)
                if (replacement != null) {
                    try { setUnderlyingNetworks(arrayOf(replacement)) } catch (_: Exception) {}
                    lastPublishedNetwork = replacement
                    Log.i(TAG, "aux: underlying $network lost → switching to $replacement")
                    LogBuffer.add(LogBuffer.LogLevel.INFO, "Сеть пропала — переключаемся…")
                    scheduleReconnect("underlying lost, switching to $replacement", replacement, cm)
                } else {
                    // No replacement yet. Don't publish anything (keeping the
                    // dead handle is worse than nothing — VPN-protected sockets
                    // already can't reach it). When a new aux network appears
                    // and validates, the score-based switch above picks it up.
                    Log.w(TAG, "aux: underlying $network lost, no replacement yet")
                    LogBuffer.add(LogBuffer.LogLevel.WARN, "Сеть пропала, ожидание замены…")
                    // Mark lastPublished as null so the score comparison treats
                    // any new validated network as a strict improvement.
                    lastPublishedNetwork = null
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, auxNetworkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "aux registerNetworkCallback failed: ${e.message}")
            auxNetworkCallback = null
        }
    }

    private data class NetState(
        val subId: Int,
        val transports: Int,
        val validated: Boolean
    )

    private fun encodeTransports(caps: NetworkCapabilities): Int {
        var bits = 0
        val all = intArrayOf(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_WIFI_AWARE,
            NetworkCapabilities.TRANSPORT_LOWPAN
        )
        for (t in all) if (caps.hasTransport(t)) bits = bits or (1 shl t)
        return bits
    }

    private fun extractSubId(caps: NetworkCapabilities): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1
        val spec = caps.networkSpecifier ?: return -1
        return try {
            val cls = Class.forName("android.net.TelephonyNetworkSpecifier")
            if (!cls.isInstance(spec)) return -1
            (cls.getMethod("getSubscriptionId").invoke(spec) as? Int) ?: -1
        } catch (_: Throwable) { -1 }
    }

    private fun scheduleReconnect(reason: String, network: Network, cm: ConnectivityManager) {
        if (currentProfileId == -1L) return
        val st = _connectionState.value
        if (st !is ConnectionState.Connected && st !is ConnectionState.Connecting) return

        // If we're stuck in Connecting (a previous dial through a now-dead
        // network is still grinding), abort it immediately and restart on
        // the new network. The keep-tun reconnect path expects a Connected
        // tunnel to mutate; in Connecting there's nothing to keep alive,
        // we just need to retarget startTunnel.
        if (st is ConnectionState.Connecting) {
            Log.i(TAG, "Network change during Connecting — restarting dial: $reason")
            LogBuffer.add(LogBuffer.LogLevel.INFO, "Сеть сменилась — повторный dial…")
            // Tell the OS the new transport ASAP so xray's protect()'d socket
            // binds to it, not the old network.
            publishUnderlyingNetworks(preferred = network)
            startTunnelJob?.cancel()
            isStarting = false
            isReconnecting = false
            reconnectJob?.cancel()
            stopTunnelProcesses()
            startTunnel(currentProfileId, skipPreflight = true)
            return
        }
        // CRITICAL: set isReconnecting BEFORE launching the coroutine. The
        // watchdog reads this flag to skip recoverXrayCrash, and xray often
        // dies right at the network-change boundary — before our coroutine
        // even gets to run. If we set the flag inside the coroutine the
        // watchdog races us, recoverXrayCrash burns through 4 attempts and
        // then calls stopTunnel(), tearing down the tunnel we were about to
        // reconnect.
        //
        // We deliberately do NOT clear isReconnecting in a finally block of
        // the coroutine. If event A's coroutine is cancelled by event B,
        // event B has already set the flag back to true synchronously above —
        // but A's finally would race with B and clear it again, opening a
        // window where the watchdog tears down the tunnel between events.
        // Instead the flag is cleared on the success path
        // (restartTunnelProcessesKeepTun → state Connected) or in the
        // catch path that calls stopTunnel/startTunnel.
        isReconnecting = true
        Log.i(TAG, "Reconnect scheduled — $reason")
        // Republish underlying network IMMEDIATELY (synchronously, before any
        // suspension). Android rebinds VPN-protected sockets to the new
        // interface so xray's outbound TCP can recover.
        publishUnderlyingNetworks(preferred = network)

        // Don't cancel an in-progress reconnect just because a new event
        // arrived for the SAME network — Realme spams onCapabilitiesChanged
        // events during a handover (signal strength, NOT_SUSPENDED toggles).
        // If we cancel on every burst we never finish a reconnect cycle.
        // Only cancel if the target network actually changed.
        val existing = reconnectJob
        if (existing != null && existing.isActive && reconnectTargetNetwork == network) {
            Log.i(TAG, "Reconnect already running for $network; not cancelling")
            return
        }
        existing?.cancel()
        reconnectTargetNetwork = network
        reconnectJob = serviceScope?.launch {
            try {
                LogBuffer.add(LogBuffer.LogLevel.INFO, "Сеть сменилась — переподключение…")
                // Active-poll for VALIDATED rather than a fixed delay. On a
                // healthy WiFi attach this returns in 100-300ms; on slow
                // cellular it caps at 2000ms. Either way we proceed with the
                // freshest possible underlying-network state and avoid the
                // user-perceived "ожидание сети" of a hardcoded 1.5s wait.
                waitForValidated(cm, network, 2_000L)
                if (!isActive || currentProfileId == -1L) return@launch
                // Republish AFTER validation — caps may have flipped from
                // unvalidated → validated → metered changes during the wait.
                publishUnderlyingNetworks(preferred = network)
                restartTunnelProcessesKeepTun()
                // One more nudge for OEM smart-switch logic. Some Realme/OPPO
                // builds latch the VPN's underlying-transport icon and only
                // refresh on a second setUnderlyingNetworks() call.
                delay(500L)
                if (isActive) publishUnderlyingNetworks(preferred = network)
            } catch (_: CancellationException) {
                // Superseded — new event already re-set isReconnecting=true.
            } finally {
                // Always release the target lock, even if cancellation killed us
                // before restartTunnelProcessesKeepTun() ran (e.g., during
                // waitForValidated). Otherwise this network stays "locked" and
                // future scheduleReconnect calls for it skip with "already
                // running, not cancelling" — exactly the freeze we hit in 1.2.16.
                reconnectTargetNetwork = null
            }
        }
    }

    private suspend fun waitForValidated(cm: ConnectivityManager, network: Network, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawCaps = false
        while (System.currentTimeMillis() < deadline) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null) {
                // The network is gone (or hasn't published caps yet on a brand
                // new cellular activation). If we already saw caps once and
                // they vanished, the network died — bail and let the caller
                // act on whatever the new default is.
                if (sawCaps) return
                delay(150)
                continue
            }
            sawCaps = true
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            delay(150)
        }
        Log.w(TAG, "waitForValidated timed out after ${timeoutMs}ms; proceeding anyway")
    }

    private fun unregisterNetworkCallback() {
        reconnectJob?.cancel()
        reconnectJob = null
        val cm = try {
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        } catch (_: Exception) { null }
        networkCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        networkCallback = null
        auxNetworkCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        auxNetworkCallback = null
    }

    /**
     * Restart xray + tun2socks while keeping the TUN fd alive.
     * This prevents IP leaks during network switches (WiFi↔LTE).
     * The TUN interface stays up so Android keeps routing all traffic into it —
     * packets are just black-holed until the new tunnel is ready.
     */
    private suspend fun restartTunnelProcessesKeepTun() {
        try {
            // CRITICAL ORDER: kill processes BEFORE cancelAndJoin'ing watchdogs.
            // The watchdog body suspends in `xrayProcess.waitFor()` — that's a
            // *blocking* IO call on Dispatchers.IO, NOT a cancellable suspension.
            // If we cancelAndJoin first while the process is alive, the watchdog
            // can't observe cancellation, waitFor() never returns, and join()
            // hangs forever. v1.2.16-v1.2.18 had this in the wrong order, which
            // is why "WiFi off → cellular fallback" never recovered: the old
            // xray bound to the dead WiFi stayed alive, watchdog stayed stuck,
            // keep-tun reconnect deadlocked, user's TG sat at "ожидание сети"
            // until they manually toggled the VPN.
            //
            // New order:
            //   1. Set intentionalProcessKill=true so the watchdog, when its
            //      waitFor() returns, sees the flag and exits cleanly without
            //      firing recoverXrayCrash.
            //   2. Destroy processes — waitFor() returns immediately, watchdog
            //      body completes.
            //   3. cancelAndJoin watchdogs — completes instantly because the
            //      watchdog coroutine already finished.
            intentionalProcessKill = true

            trafficMonitor?.stop()
            trafficMonitor = null
            tun2socksProcess?.let { p ->
                try { p.destroy() } catch (_: Exception) {}
                try { p.destroyForcibly() } catch (_: Exception) {}
            }
            tun2socksProcess = null
            xrayProcess?.let { p ->
                try { p.destroy() } catch (_: Exception) {}
                try { p.destroyForcibly() } catch (_: Exception) {}
            }
            xrayProcess = null
            CredentialManager.clear()

            // Now safe to join — processes are dead, watchdogs have already
            // returned (or are about to within microseconds).
            xrayWatchdogJob?.cancelAndJoin()
            tun2socksWatchdogJob?.cancelAndJoin()
            xrayWatchdogJob = null
            tun2socksWatchdogJob = null

            _connectionState.value = ConnectionState.Connecting
            LogBuffer.add(LogBuffer.LogLevel.INFO, "Reconnecting (network change)...")

            // Tighter timeout for keep-tun reconnect (12s vs 30s for cold
            // start). If we can't bring xray+tun2socks up in 12s, the new
            // network is broken anyway — fall through to full restart sooner
            // so the user isn't staring at "ожидание сети" for 30 seconds.
            withTimeout(12_000L) {
                yield()
                val app = application as App
                val profile = app.database.profileDao().getById(currentProfileId)
                    ?: throw IllegalStateException("profile $currentProfileId gone")
                val settings = app.loadSettings()

                // Generate new config
                val config = XrayConfigGenerator.generate(
                    profile = profile,
                    settings = settings,
                    useSocksInbound = true
                )

                val configFile = File(filesDir, "config.json")
                val tempFile = File(filesDir, "config.json.tmp")
                tempFile.writeText(config.json)
                tempFile.renameTo(configFile)

                // Restart xray, ensure config deleted even on failure. From this
                // point onwards new processes are ours — drop the intentional-kill
                // shield so a real crash of the new process is still observable.
                intentionalProcessKill = false
                try {
                    startXrayProcess(configFile)
                    waitForPort(
                        config.socksPort ?: throw IllegalStateException("socksPort null"),
                        timeoutMs = 5000
                    )
                } finally {
                    configFile.delete()
                }

                yield()
                // Reuse existing TUN fd for tun2socks (validate it's still open).
                // If invalid we MUST throw so the catch block triggers a full
                // restart — silent return would leave xray running, no tun2socks,
                // state=Connecting forever and the user staring at a spinner.
                val fd = synchronized(fdLock) {
                    vpnFd?.takeIf { it.fileDescriptor.valid() }
                } ?: throw IllegalStateException("TUN fd invalid after network change")
                val socksPort = config.socksPort
                    ?: throw IllegalStateException("socksPort null")
                val socksUser = config.socksUser
                    ?: throw IllegalStateException("socksUser null")
                val socksPass = config.socksPass
                    ?: throw IllegalStateException("socksPass null")
                startTun2socksProcess(fd, socksPort, socksUser, socksPass)

                // Restart monitors
                trafficMonitor = TrafficMonitor().also { monitor ->
                    monitor.start(serviceScope!!) { snapshot ->
                        _trafficStats.value = snapshot
                        if (showSpeedNotification) {
                            NotificationHelper.updateSpeedNotification(this@TunnelVpnService, snapshot.rxSpeed, snapshot.txSpeed)
                        }
                    }
                }
                launchProcessWatchdog()

                // Republish underlying network now that tun2socks is wired
                // up to the new xray. Without this the VPN keeps inheriting
                // the old (dead) network's capabilities and the OS status
                // bar shows the wrong transport icon (or none at all).
                publishUnderlyingNetworks()
                _connectionState.value = ConnectionState.Connected()
                NotificationHelper.showConnectedNotification(this@TunnelVpnService)
                xrayRestartAttempts = 0
                isReconnecting = false
                reconnectTargetNetwork = null
                Log.i(TAG, "Tunnel reconnected without TUN restart (no IP leak)")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Reconnect timed out, doing full restart")
            LogBuffer.add(LogBuffer.LogLevel.WARN, "Reconnect timed out — full restart")
            isReconnecting = false
            stopTunnelProcesses()
            startTunnel(currentProfileId, skipPreflight = true)
        } catch (e: CancellationException) {
            Log.i(TAG, "Reconnect cancelled (handover superseded)")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconnect, doing full restart", e)
            LogBuffer.add(LogBuffer.LogLevel.WARN,
                "Reconnect failed: ${e.javaClass.simpleName}: ${e.message ?: "?"} → full restart")
            isReconnecting = false
            stopTunnelProcesses()
            startTunnel(currentProfileId, skipPreflight = true)
        } finally {
            // Ensure the shield is dropped no matter how we exit. Leaving it
            // true would silently neuter the watchdog for the next session.
            intentionalProcessKill = false
            // Clear the reconnect target on EVERY exit path (success, cancel,
            // timeout, error). v1.2.16 only cleared it on success — so a job
            // that died via cancel/timeout left target=<dead network> forever,
            // which made every subsequent scheduleReconnect for the same Network
            // see "already running, not cancelling" and silently skip. The result
            // was a frozen VPN that ignored further handover events.
            // Worst case: a superseding job already set target=newNet and our
            // clear races it. Then the next event's "already running for X"
            // check sees target=null and falls through to cancel+relaunch the
            // superseder. One extra restart, no broken state.
            reconnectTargetNetwork = null
        }
    }

    private fun stopTunnelProcesses() {
        intentionalProcessKill = true
        trafficMonitor?.stop()
        trafficMonitor = null
        tun2socksProcess?.let { p ->
            try { p.destroy() } catch (_: Exception) {}
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
        tun2socksProcess = null
        xrayProcess?.let { p ->
            try { p.destroy() } catch (_: Exception) {}
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
        xrayProcess = null
        CredentialManager.clear()
        synchronized(fdLock) {
            vpnFd?.close()
            vpnFd = null
        }
        File(filesDir, "config.json").delete()
    }

    /**
     * Bring up the TUN with allowed=triggerApps and NO xray/tun2socks.
     * The kernel routes those apps' packets into TUN; nothing drains them →
     * no internet at all. This is the always-on guard for trigger apps.
     */
    private fun startQuarantineTun() {
        // Cancel any prior watchdogs
        xrayWatchdogJob?.cancel()
        tun2socksWatchdogJob?.cancel()
        unregisterNetworkCallback()
        // Don't kill an existing TUN if we already have one — only kill processes.
        trafficMonitor?.stop(); trafficMonitor = null
        tun2socksProcess?.let { p -> try { p.destroy() } catch (_: Exception) {}; try { p.destroyForcibly() } catch (_: Exception) {} }
        tun2socksProcess = null
        xrayProcess?.let { p -> try { p.destroy() } catch (_: Exception) {}; try { p.destroyForcibly() } catch (_: Exception) {} }
        xrayProcess = null
        CredentialManager.clear()

        serviceScope?.launch {
            try {
                val app = application as App
                val settings = app.loadSettings()
                if (settings.triggerApps.isEmpty()) {
                    Log.w(TAG, "Quarantine: no trigger apps, stopping")
                    stopTunnel()
                    return@launch
                }
                // Reuse a real ServerProfile if available so DNS/IPv6 settings are sane,
                // otherwise build a synthetic one. We don't run xray, so address fields
                // don't matter.
                val profile = app.database.profileDao().getSelected()
                    ?: ServerProfile(name = "quarantine")

                synchronized(fdLock) {
                    vpnFd?.close()
                    vpnFd = null
                }
                val fd = establishVpn(profile, settings) ?: run {
                    _connectionState.value = ConnectionState.Error("VPN permission denied")
                    stopTunnel()
                    return@launch
                }
                synchronized(fdLock) { vpnFd = fd }
                _connectionState.value = ConnectionState.Disconnected
                NotificationHelper.invalidateCache()
                NotificationHelper.showQuarantineNotification(this@TunnelVpnService)
                LogBuffer.add(LogBuffer.LogLevel.INFO, "Quarantine TUN up: ${settings.triggerApps.size} apps blocked")
                VpnWidget.updateAllWidgets(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "startQuarantineTun failed", e)
                stopTunnel()
            }
        }
    }

    /**
     * On the existing quarantine TUN, start xray+tun2socks so trigger apps
     * actually get internet through the VPN.
     */
    private fun hasUnderlyingNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true
            }
        }
        return false
    }

    private fun activateTunnelOnQuarantineTun() {
        if (triggerActive) return
        if (!hasUnderlyingNetwork()) {
            Log.w(TAG, "Activate aborted — no underlying network")
            _connectionState.value = ConnectionState.Error("Нет сети")
            // Stay in quarantine; the watcher will retry when user re-opens app.
            return
        }
        triggerActive = true
        _connectionState.value = ConnectionState.Connecting
        NotificationHelper.invalidateCache()
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.createConnectingNotification(this)
        )
        serviceScope?.launch {
            try {
                withTimeout(CONNECTION_TIMEOUT_MS) {
                    val app = application as App
                    val profile = app.database.profileDao().getSelected() ?: run {
                        _connectionState.value = ConnectionState.Error("No selected server")
                        triggerActive = false
                        return@withTimeout
                    }
                    currentProfileId = profile.id
                    app.getPreferences().edit()
                        .putLong("last_profile_id", profile.id)
                        .putString("last_profile_name", profile.name)
                        .apply()

                    val settings = app.loadSettings()

                    // Rebuild TUN with DNS servers — quarantine TUN had none,
                    // so trigger apps couldn't resolve hostnames. We're still
                    // in quarantineMode (per-app whitelist preserved), only
                    // triggerActive=true now flips the DNS branch in establishVpn.
                    synchronized(fdLock) {
                        vpnFd?.close()
                        vpnFd = null
                    }
                    val newFd = establishVpn(profile, settings) ?: run {
                        _connectionState.value = ConnectionState.Error("VPN rebuild failed")
                        triggerActive = false
                        return@withTimeout
                    }
                    synchronized(fdLock) { vpnFd = newFd }

                    copyGeoFiles()
                    val config = XrayConfigGenerator.generate(profile, settings, useSocksInbound = true)
                    val configFile = File(filesDir, "config.json")
                    val tempFile = File(filesDir, "config.json.tmp")
                    tempFile.writeText(config.json)
                    tempFile.renameTo(configFile)
                    try {
                        startXrayProcess(configFile)
                        waitForPort(config.socksPort ?: throw IllegalStateException("SOCKS port"), 5000)
                    } finally { configFile.delete() }

                    val socksPort = config.socksPort ?: throw IllegalStateException("SOCKS port")
                    val socksUser = config.socksUser ?: throw IllegalStateException("SOCKS user")
                    val socksPass = config.socksPass ?: throw IllegalStateException("SOCKS pass")
                    startTun2socksProcess(newFd, socksPort, socksUser, socksPass)

                    showSpeedNotification = settings.showSpeedInNotification
                    trafficMonitor = TrafficMonitor().also { m ->
                        m.start(serviceScope!!) { snap ->
                            _trafficStats.value = snap
                            if (showSpeedNotification) {
                                NotificationHelper.updateSpeedNotification(this@TunnelVpnService, snap.rxSpeed, snap.txSpeed)
                            }
                        }
                    }
                    launchProcessWatchdog()
                    registerNetworkCallback()

                    _connectionState.value = ConnectionState.Connected()
                    NotificationHelper.showConnectedNotification(this@TunnelVpnService)
                    VpnWidget.updateAllWidgets(applicationContext)
                    Log.i(TAG, "Trigger activated on quarantine TUN")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Activate trigger failed", e)
                triggerActive = false
                // Set Disconnected BEFORE killing processes — same watchdog race
                // as in deactivateTunnelKeepQuarantine.
                _connectionState.value = ConnectionState.Disconnected
                xrayWatchdogJob?.cancel()
                tun2socksWatchdogJob?.cancel()
                trafficMonitor?.stop(); trafficMonitor = null
                tun2socksProcess?.destroyForcibly(); tun2socksProcess = null
                xrayProcess?.destroyForcibly(); xrayProcess = null
                CredentialManager.clear()
                // Make sure quarantineMode stays true so next activate works.
                quarantineMode = true
                NotificationHelper.showQuarantineNotification(this@TunnelVpnService)
            }
        }
    }

    /**
     * Kill xray+tun2socks and rebuild TUN without DNS so trigger apps see
     * "no network" instead of "connecting…" timeout.
     */
    private fun deactivateTunnelKeepQuarantine() {
        if (!triggerActive) return
        triggerActive = false
        // CRITICAL: set Disconnected BEFORE killing xray. The process watchdog
        // checks `state is Connected || Connecting` and if so calls stopTunnel(),
        // which resets quarantineMode → next activate fails. Disconnected makes
        // watchdog skip the failure path.
        _connectionState.value = ConnectionState.Disconnected
        unregisterNetworkCallback()
        xrayWatchdogJob?.cancel()
        tun2socksWatchdogJob?.cancel()
        trafficMonitor?.stop(); trafficMonitor = null
        tun2socksProcess?.let { p -> try { p.destroy() } catch (_: Exception) {}; try { p.destroyForcibly() } catch (_: Exception) {} }
        tun2socksProcess = null
        xrayProcess?.let { p -> try { p.destroy() } catch (_: Exception) {}; try { p.destroyForcibly() } catch (_: Exception) {} }
        xrayProcess = null
        CredentialManager.clear()
        File(filesDir, "config.json").delete()
        _trafficStats.value = TrafficSnapshot(0L, 0L, 0L, 0L)

        // Rebuild quarantine TUN (no DNS) so trigger apps start failing fast
        // instead of waiting for a TCP timeout.
        serviceScope?.launch {
            try {
                val app = application as App
                val settings = app.loadSettings()
                if (settings.triggerApps.isEmpty()) {
                    stopTunnel()
                    return@launch
                }
                val profile = app.database.profileDao().getSelected()
                    ?: ServerProfile(name = "quarantine")
                synchronized(fdLock) {
                    vpnFd?.close()
                    vpnFd = null
                }
                val fd = establishVpn(profile, settings) ?: run {
                    stopTunnel()
                    return@launch
                }
                synchronized(fdLock) { vpnFd = fd }
                _connectionState.value = ConnectionState.Disconnected
                NotificationHelper.invalidateCache()
                NotificationHelper.showQuarantineNotification(this@TunnelVpnService)
                VpnWidget.updateAllWidgets(applicationContext)
                LogBuffer.add(LogBuffer.LogLevel.INFO, "Trigger deactivated, quarantine restored")
            } catch (e: Exception) {
                Log.e(TAG, "deactivate rebuild failed", e)
                stopTunnel()
            }
        }
    }

    private fun stopTunnel() {
        // User (or onRevoke / onDestroy) asked us to fully stop. Drop any
        // in-progress failover state — if they reconnect later we want a
        // fresh budget, not stale ledger entries from the previous chain.
        failoverInProgress = false
        failoverTried.clear()
        isReconnecting = false
        reconnectTargetNetwork = null
        unregisterNetworkCallback()
        trafficMonitor?.stop()
        trafficMonitor = null

        // Kill tun2socks
        tun2socksProcess?.let { p ->
            try { p.destroy() } catch (_: Exception) {}
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
        tun2socksProcess = null

        // Kill xray
        xrayProcess?.let { p ->
            try { p.destroy() } catch (_: Exception) {}
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
        xrayProcess = null

        CredentialManager.clear()

        synchronized(fdLock) {
            vpnFd?.close()
            vpnFd = null
        }

        // Clean up config (contains no secrets on disk after stop)
        File(filesDir, "config.json").delete()

        // Save session traffic before resetting
        val snapshot = _trafficStats.value
        val app = application as App
        app.statsRepository.addTraffic(snapshot.rxBytes, snapshot.txBytes)

        triggerPackage = null
        quarantineMode = false
        triggerActive = false
        prewarmMode = false
        _isTriggerTunnel.value = false
        _connectionState.value = ConnectionState.Disconnected
        _trafficStats.value = TrafficSnapshot(0L, 0L, 0L, 0L)
        VpnWidget.updateAllWidgets(applicationContext)

        NotificationHelper.invalidateCache()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LogBuffer.add(LogBuffer.LogLevel.INFO, "Tunnel stopped")
        Log.i(TAG, "Tunnel stopped")
    }

    private fun getPid(process: Process): Long {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process).toLong()
        } catch (_: Exception) {
            -1L
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }
}
