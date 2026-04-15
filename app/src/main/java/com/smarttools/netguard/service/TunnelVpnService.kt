package com.smarttools.netguard.service

import android.content.Context
import android.content.Intent
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
        const val EXTRA_PROFILE_ID = "profile_id"

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
    }

    data class TrafficSnapshot(
        val rxBytes: Long,
        val txBytes: Long,
        val rxSpeed: Long,
        val txSpeed: Long
    )

    private val fdLock = Any()
    private var vpnFd: ParcelFileDescriptor? = null
    private var serviceScope: CoroutineScope? = null
    private var trafficMonitor: TrafficMonitor? = null
    private var xrayProcess: Process? = null
    private var tun2socksProcess: Process? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentProfileId: Long = -1
    @Volatile
    private var isReconnecting = false
    @Volatile
    private var isStarting = false
    private var showSpeedNotification = false
    private var xrayWatchdogJob: Job? = null
    private var tun2socksWatchdogJob: Job? = null
    private var lastNotificationUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                // Show "Connecting..." notification immediately to avoid ANR
                val notification = NotificationHelper.createConnectingNotification(this@TunnelVpnService)
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
                startTunnel(profileId)
            }
            else -> {
                val app = application as App
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
        return START_STICKY
    }

    private fun startTunnel(profileId: Long) {
        if (isStarting) {
            Log.w(TAG, "startTunnel called while already starting, ignoring")
            return
        }
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
                        .apply()

                    val settings = app.loadSettings()

                    // 1. Copy geodata files to xray's working directory
                    copyGeoFiles()

                    // 2. Generate xray config with authenticated SOCKS5 on random port
                    val config = XrayConfigGenerator.generate(
                        profile = profile,
                        settings = settings,
                        useSocksInbound = true
                    )

                    // 3. Write xray config atomically (temp + rename) to prevent corruption
                    val configFile = File(filesDir, "config.json")
                    val tempFile = File(filesDir, "config.json.tmp")
                    tempFile.writeText(config.json)
                    tempFile.renameTo(configFile)
                    Log.i(TAG, "Config written, SOCKS port=${config.socksPort}")

                    // 4. Start xray-core process, ensure config deleted even on failure
                    try {
                        startXrayProcess(configFile)

                        // 5. Wait for xray to bind the SOCKS port
                        waitForPort(config.socksPort ?: throw IllegalStateException("SOCKS port not generated"), timeoutMs = 5000)
                        Log.i(TAG, "Xray is listening on port ${config.socksPort}")
                    } finally {
                        // 5a. Delete config from disk — xray already read it into memory
                        configFile.delete()
                        Log.i(TAG, "Config file deleted from disk")
                    }

                    // 6. Establish VPN TUN interface
                    val fd = establishVpn(profile, settings) ?: run {
                        _connectionState.value = ConnectionState.Error("VPN permission denied")
                        stopTunnel()
                        return@withTimeout
                    }
                    synchronized(fdLock) { vpnFd = fd }

                    // 7. Start tun2socks: TUN fd → authenticated SOCKS5
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
                    Log.i(TAG, "Tunnel started for ${profile.name}")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Connection timed out after ${CONNECTION_TIMEOUT_MS / 1000}s")
                _connectionState.value = ConnectionState.Error("Connection timed out")
                VpnWidget.updateAllWidgets(applicationContext)
                stopTunnel()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tunnel", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
                VpnWidget.updateAllWidgets(applicationContext)
                stopTunnel()
            } finally {
                isStarting = false
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

    private fun startTun2socksProcess(fd: ParcelFileDescriptor, port: Int, user: String, pass: String) {
        val tun2socksBin = File(applicationInfo.nativeLibraryDir, "libtun2socks.so")
        if (!tun2socksBin.exists()) throw IllegalStateException("tun2socks binary not found at ${tun2socksBin.absolutePath}")

        val sockPath = File(filesDir, "sock_path").absolutePath

        // badvpn-tun2socks: receives TUN fd via Unix domain socket
        val pb = ProcessBuilder(
            tun2socksBin.absolutePath,
            "--netif-ipaddr", "10.10.10.2",
            "--netif-netmask", "255.255.255.252",
            "--socks-server-addr", "127.0.0.1:$port",
            "--username", user,
            "--password", pass,
            "--tunmtu", "1500",
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

    private fun sendTunFd(sockPath: String, fd: ParcelFileDescriptor) {
        // Retry loop: wait for tun2socks to create the Unix socket
        var connected = false
        for (attempt in 1..10) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            val sockFile = File(sockPath)
            if (sockFile.exists()) {
                connected = true
                break
            }
            Log.d(TAG, "Waiting for tun2socks socket (attempt $attempt/10)")
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
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                }
                return // port is open
            } catch (_: Exception) {
                delay(200)
            }
        }
        Log.w(TAG, "Timeout waiting for port $port, proceeding anyway")
    }

    private fun launchProcessWatchdog() {
        // Cancel old watchdogs to prevent stale ones from firing after reconnect
        xrayWatchdogJob?.cancel()
        tun2socksWatchdogJob?.cancel()

        xrayWatchdogJob = serviceScope?.launch(Dispatchers.IO) {
            try {
                val exitCode = xrayProcess?.waitFor() ?: return@launch
                val state = _connectionState.value
                if (isActive && (state is ConnectionState.Connected || state is ConnectionState.Connecting)) {
                    Log.e(TAG, "Xray process died with exit code $exitCode")
                    withContext(Dispatchers.Main) {
                        _connectionState.value = ConnectionState.Error("Xray exited ($exitCode)")
                        stopTunnel()
                    }
                }
            } catch (_: Exception) {}
        }
        tun2socksWatchdogJob = serviceScope?.launch(Dispatchers.IO) {
            try {
                val exitCode = tun2socksProcess?.waitFor() ?: return@launch
                val state = _connectionState.value
                if (isActive && (state is ConnectionState.Connected || state is ConnectionState.Connecting)) {
                    Log.e(TAG, "tun2socks process died with exit code $exitCode")
                    withContext(Dispatchers.Main) {
                        _connectionState.value = ConnectionState.Error("tun2socks exited ($exitCode)")
                        stopTunnel()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun establishVpn(profile: ServerProfile, settings: AppSettings): ParcelFileDescriptor? {
        val primaryDns = profile.dns.ifEmpty { settings.primaryDns }
        val secondaryDns = settings.secondaryDns

        val builder = Builder()
            .addAddress("10.10.10.1", 30)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            // CRITICAL: two DNS servers — if primary is unreachable, secondary
            // prevents Android from falling back to system DNS (which leaks IP)
            .addDnsServer(primaryDns)
            .addDnsServer(secondaryDns)
            .setMtu(1500)           // CRITICAL: standard MTU — no anomaly detection
            .setSession("")         // CRITICAL: empty session — not "VPN"
            .setBlocking(false)
            // CRITICAL: mark as not metered so Android doesn't prefer non-VPN routes
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setMetered(false)
                }
            }

        when (settings.perAppMode) {
            PerAppMode.WHITELIST -> {
                settings.perAppList.forEach { pkg ->
                    try { builder.addAllowedApplication(pkg) } catch (e: Exception) {
                        Log.w(TAG, "Package not found for whitelist: $pkg")
                    }
                }
            }
            PerAppMode.BLACKLIST -> {
                settings.perAppList.forEach { pkg ->
                    try { builder.addDisallowedApplication(pkg) } catch (e: Exception) {
                        Log.w(TAG, "Package not found for blacklist: $pkg")
                    }
                }
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
            PerAppMode.DISABLED -> {
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
        }

        return builder.establish()
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
                // Only reconnect if we had a DIFFERENT underlying network before (WiFi↔LTE switch)
                if (prev != null && prev != network &&
                    _connectionState.value is ConnectionState.Connected &&
                    !isReconnecting && currentProfileId != -1L) {
                    Log.i(TAG, "Underlying network changed, reconnecting (TUN kept alive)...")
                    isReconnecting = true
                    serviceScope?.launch {
                        try {
                            delay(2000)
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

    private fun stopTunnel() {
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
