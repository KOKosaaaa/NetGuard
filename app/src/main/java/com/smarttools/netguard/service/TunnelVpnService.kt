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
    private val failoverMaxAttempts = 3
    @Volatile
    private var showSpeedNotification = false
    @Volatile
    private var xrayWatchdogJob: Job? = null
    @Volatile
    private var tun2socksWatchdogJob: Job? = null
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

    private fun startTunnel(profileId: Long) {
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

        serviceScope?.launch {
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

                    // 0. Pre-flight reachability check. Cheap (DNS + 2s TCP)
                    // and short-circuits a hard-down server before we burn the
                    // 30s tunnel-establishment budget on it.
                    when (val pf = ServerPreflight.check(profile)) {
                        is ServerPreflight.Result.Dead -> {
                            LogBuffer.add(LogBuffer.LogLevel.WARN, "Preflight dead: ${pf.reason}")
                            throw IllegalStateException("Server unreachable: ${pf.reason}")
                        }
                        is ServerPreflight.Result.Slow -> {
                            LogBuffer.add(LogBuffer.LogLevel.WARN, "Preflight slow: ${pf.rttMs} ms")
                        }
                        is ServerPreflight.Result.Ok -> {
                            if (pf.rttMs > 0) Log.i(TAG, "Preflight OK ${pf.rttMs} ms")
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
    private fun handleConnectFailure(reason: String, failedProfileId: Long) {
        VpnWidget.updateAllWidgets(applicationContext)
        if (failoverTried.size >= failoverMaxAttempts) {
            Log.w(TAG, "Failover budget exhausted (${failoverTried.size}/$failoverMaxAttempts); giving up: $reason")
            _connectionState.value = ConnectionState.Error(reason)
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
                    Log.w(TAG, "No failover candidates left; surfacing error: $reason")
                    _connectionState.value = ConnectionState.Error(reason)
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

        // Log xray stdout/stderr in background
        serviceScope?.launch(Dispatchers.IO) {
            try {
                xrayProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d("XrayCore", line)
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

        // Log output in background
        serviceScope?.launch(Dispatchers.IO) {
            try {
                tun2socksProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d("tun2socks", line)
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

        xrayWatchdogJob = serviceScope?.launch(Dispatchers.IO) {
            try {
                val exitCode = xrayProcess?.waitFor() ?: return@launch
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

        // Tell Android about ALL underlying non-VPN networks so blacklisted
        // apps can use any of them (cellular AND wifi if both available).
        // Picking just one would force their bypass traffic to that one only.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nonVpn = cm.allNetworks.filter { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@filter false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }.toTypedArray()
            if (nonVpn.isNotEmpty()) {
                setUnderlyingNetworks(nonVpn)
                Log.i(TAG, "Underlying networks: ${nonVpn.size} non-VPN networks set")
            }
        } catch (e: Exception) {
            Log.w(TAG, "setUnderlyingNetworks failed: ${e.message}")
        }
        return fd
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            private var currentNetwork: Network? = null

            override fun onAvailable(network: Network) {
                val prev = currentNetwork
                currentNetwork = network
                // Pin VpnService to the new underlying network so outbound sockets
                // from xray route through it immediately instead of Android's
                // default, which can briefly resolve to the old (possibly gone)
                // network during a WiFi↔LTE switch.
                try {
                    setUnderlyingNetworks(arrayOf(network))
                } catch (e: Exception) {
                    Log.w(TAG, "setUnderlyingNetworks(onAvailable) failed: ${e.message}")
                }
                // Only reconnect if we had a DIFFERENT underlying network before (WiFi↔LTE switch)
                if (prev != null && prev != network &&
                    _connectionState.value is ConnectionState.Connected &&
                    !isReconnecting && currentProfileId != -1L) {
                    Log.i(TAG, "Underlying network changed, reconnecting (TUN kept alive)...")
                    isReconnecting = true
                    serviceScope?.launch {
                        try {
                            // Short debounce — long enough to coalesce multiple
                            // onAvailable bursts during a single network swap,
                            // short enough that the TUN black-hole window stays
                            // sub-second on a clean handover.
                            delay(500)
                            if (currentProfileId != -1L) {
                                // CRITICAL: only restart xray+tun2socks, keep TUN fd alive
                                // This prevents ANY traffic from leaking during reconnect
                                restartTunnelProcessesKeepTun()
                            }
                        } finally {
                            isReconnecting = false
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                if (network == currentNetwork) {
                    currentNetwork = null
                }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    /**
     * Restart xray + tun2socks while keeping the TUN fd alive.
     * This prevents IP leaks during network switches (WiFi↔LTE).
     * The TUN interface stays up so Android keeps routing all traffic into it —
     * packets are just black-holed until the new tunnel is ready.
     */
    private suspend fun restartTunnelProcessesKeepTun() {
        try {
            // Cancel old watchdogs BEFORE killing processes — otherwise they
            // detect the intentional kill, see Connecting state, and call stopTunnel()
            xrayWatchdogJob?.cancel()
            tun2socksWatchdogJob?.cancel()

            _connectionState.value = ConnectionState.Connecting
            LogBuffer.add(LogBuffer.LogLevel.INFO, "Reconnecting (network change)...")

            withTimeout(CONNECTION_TIMEOUT_MS) {
                // Kill old processes (but DON'T close vpnFd!)
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

                val app = application as App
                val profile = app.database.profileDao().getById(currentProfileId) ?: return@withTimeout
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

                // Restart xray, ensure config deleted even on failure
                try {
                    startXrayProcess(configFile)
                    waitForPort(config.socksPort ?: return@withTimeout, timeoutMs = 5000)
                } finally {
                    configFile.delete()
                }

                // Reuse existing TUN fd for tun2socks (validate it's still open)
                val fd = synchronized(fdLock) {
                    vpnFd?.takeIf { it.fileDescriptor.valid() }
                } ?: return@withTimeout
                val socksPort = config.socksPort ?: return@withTimeout
                val socksUser = config.socksUser ?: return@withTimeout
                val socksPass = config.socksPass ?: return@withTimeout
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

                _connectionState.value = ConnectionState.Connected()
                NotificationHelper.showConnectedNotification(this@TunnelVpnService)
                Log.i(TAG, "Tunnel reconnected without TUN restart (no IP leak)")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Reconnect timed out, doing full restart")
            stopTunnelProcesses()
            startTunnel(currentProfileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconnect, doing full restart", e)
            stopTunnelProcesses()
            startTunnel(currentProfileId)
        }
    }

    private fun stopTunnelProcesses() {
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
