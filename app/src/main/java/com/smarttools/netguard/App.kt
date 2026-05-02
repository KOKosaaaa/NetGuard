package com.smarttools.netguard

import android.app.Application
import android.content.SharedPreferences
import com.smarttools.netguard.database.AppDatabase
import com.smarttools.netguard.model.*
import com.smarttools.netguard.repository.ProfileRepository
import com.smarttools.netguard.repository.StatsRepository
import com.smarttools.netguard.repository.SubscriptionRepository
import android.content.Intent
import android.content.IntentFilter
import com.smarttools.netguard.service.NotificationHelper
import com.smarttools.netguard.service.PackageInstallReceiver
import com.smarttools.netguard.service.WifiAutoConnectManager
import com.smarttools.netguard.worker.SubscriptionUpdateWorker
import androidx.work.*
import kotlinx.coroutines.launch
import com.google.android.material.color.DynamicColors
import java.util.concurrent.TimeUnit

class App : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var profileRepository: ProfileRepository
        private set

    lateinit var subscriptionRepository: SubscriptionRepository
        private set

    lateinit var statsRepository: StatsRepository
        private set

    var wifiAutoConnectManager: WifiAutoConnectManager? = null
        private set

    override fun onCreate() {
        super.onCreate()

        // Apply Dynamic Colors before any UI is created (Android 12+)
        val settings = loadSettings()
        if (settings.themeMode == ThemeMode.DYNAMIC) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        database = AppDatabase.getInstance(this)
        profileRepository = ProfileRepository(database.profileDao())
        subscriptionRepository = SubscriptionRepository(
            database.subscriptionDao(),
            database.profileDao()
        )
        statsRepository = StatsRepository(this)
        com.smarttools.netguard.util.GeoLookup.init(this)
        NotificationHelper.createChannel(this)
        scheduleSubscriptionUpdates()

        if (settings.autoConnectWifi) {
            wifiAutoConnectManager = WifiAutoConnectManager(this).also { it.register() }
        }

        if (settings.triggerEnabled && settings.triggerApps.isNotEmpty()) {
            triggerWatcher = com.smarttools.netguard.service.ForegroundAppWatcher(this).also { it.start() }
            // Strict mode: bring up quarantine TUN so trigger apps can never
            // hit the network without VPN. Flexible mode: no quarantine —
            // VPN simply turns on when a trigger app is opened.
            if (settings.triggerStrictMode) {
                val state = com.smarttools.netguard.service.TunnelVpnService.connectionState.value
                if (state is com.smarttools.netguard.model.ConnectionState.Disconnected) {
                    com.smarttools.netguard.service.TunnelVpnService.startQuarantine(this)
                }
            }
        }

        // Dynamic PACKAGE_ADDED listener for the "auto-bypass new ru.* apps"
        // feature. We register unconditionally and let the receiver itself
        // re-check the setting at delivery time — settings can be toggled
        // while the process is alive and we want the change to take effect
        // without a process restart.
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        val receiver = PackageInstallReceiver()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, pkgFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, pkgFilter)
        }
    }

    fun getPreferences(): SharedPreferences {
        return getSharedPreferences("netguard_prefs", MODE_PRIVATE)
    }

    fun loadSettings(): AppSettings {
        val prefs = getPreferences()
        return AppSettings(
            routingMode = safeEnum(
                prefs.getString("routing_mode", null), RoutingMode.GLOBAL_PROXY
            ),
            primaryDns = prefs.getString("primary_dns", "1.1.1.1") ?: "1.1.1.1",
            secondaryDns = prefs.getString("secondary_dns", "8.8.8.8") ?: "8.8.8.8",
            dohEnabled = prefs.getBoolean("doh_enabled", false),
            themeMode = safeEnum(
                prefs.getString("theme_mode", null), ThemeMode.DARK
            ),
            language = prefs.getString("language", "system") ?: "system",
            bypassLan = prefs.getBoolean("bypass_lan", true),
            enableIpv6 = prefs.getBoolean("enable_ipv6", true),
            perAppMode = safeEnum(
                prefs.getString("per_app_mode", null), PerAppMode.DISABLED
            ),
            perAppList = prefs.getStringSet("per_app_list", emptySet()) ?: emptySet(),
            showSpeedInNotification = prefs.getBoolean("show_speed_notification", false),
            showConnectionMap = prefs.getBoolean("show_connection_map", true),
            showSpeedTest = prefs.getBoolean("show_speed_test", true),
            autoConnectWifi = prefs.getBoolean("auto_connect_wifi", false),
            trustedWifiList = prefs.getStringSet("trusted_wifi_list", emptySet()) ?: emptySet(),
            tlsFragmentEnabled = prefs.getBoolean("tls_fragment_enabled", false),
            tlsFragmentPackets = prefs.getString("tls_fragment_packets", "tlshello") ?: "tlshello",
            tlsFragmentLength = prefs.getString("tls_fragment_length", "100-200") ?: "100-200",
            tlsFragmentInterval = prefs.getString("tls_fragment_interval", "10-20") ?: "10-20",
            bypassDomains = prefs.getString("bypass_domains", "") ?: "",
            bypassIps = prefs.getString("bypass_ips", "") ?: "",
            trafficStatsMode = safeEnum(
                prefs.getString("traffic_stats_mode", null), TrafficStatsMode.SIMPLE
            ),
            triggerEnabled = prefs.getBoolean("trigger_enabled", false),
            triggerApps = prefs.getStringSet("trigger_apps", emptySet()) ?: emptySet(),
            triggerAutoStop = prefs.getBoolean("trigger_auto_stop", false),
            triggerStrictMode = prefs.getBoolean("trigger_strict_mode", true),
            tlsFingerprintMode = safeEnum(
                prefs.getString("tls_fingerprint_mode", null), TlsFingerprintMode.CHROME
            ),
            autoBypassRuPackages = prefs.getBoolean("auto_bypass_ru_packages", false)
        )
    }

    private inline fun <reified T : Enum<T>> safeEnum(value: String?, default: T): T {
        if (value == null) return default
        return try {
            enumValueOf<T>(value)
        } catch (_: IllegalArgumentException) {
            android.util.Log.w("App", "Invalid enum value '$value' for ${T::class.simpleName}, using $default")
            default
        }
    }

    fun scheduleSubscriptionUpdates(intervalHours: Long = 24) {
        if (intervalHours <= 0) {
            WorkManager.getInstance(this).cancelUniqueWork(SubscriptionUpdateWorker.WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
            intervalHours, TimeUnit.HOURS
        ).setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SubscriptionUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun updateWifiAutoConnect(enabled: Boolean) {
        if (enabled) {
            if (wifiAutoConnectManager == null) {
                wifiAutoConnectManager = WifiAutoConnectManager(this).also { it.register() }
            }
        } else {
            wifiAutoConnectManager?.unregister()
            wifiAutoConnectManager = null
        }
    }

    fun saveSettings(settings: AppSettings) {
        getPreferences().edit().apply {
            putString("routing_mode", settings.routingMode.name)
            putString("primary_dns", settings.primaryDns)
            putString("secondary_dns", settings.secondaryDns)
            putBoolean("doh_enabled", settings.dohEnabled)
            putString("theme_mode", settings.themeMode.name)
            putBoolean("bypass_lan", settings.bypassLan)
            putBoolean("enable_ipv6", settings.enableIpv6)
            putString("per_app_mode", settings.perAppMode.name)
            putStringSet("per_app_list", settings.perAppList)
            putString("language", settings.language)
            putBoolean("show_speed_notification", settings.showSpeedInNotification)
            putBoolean("show_connection_map", settings.showConnectionMap)
            putBoolean("show_speed_test", settings.showSpeedTest)
            putBoolean("auto_connect_wifi", settings.autoConnectWifi)
            putStringSet("trusted_wifi_list", settings.trustedWifiList)
            putBoolean("tls_fragment_enabled", settings.tlsFragmentEnabled)
            putString("tls_fragment_packets", settings.tlsFragmentPackets)
            putString("tls_fragment_length", settings.tlsFragmentLength)
            putString("tls_fragment_interval", settings.tlsFragmentInterval)
            putString("bypass_domains", settings.bypassDomains)
            putString("bypass_ips", settings.bypassIps)
            putString("traffic_stats_mode", settings.trafficStatsMode.name)
            putBoolean("trigger_enabled", settings.triggerEnabled)
            putStringSet("trigger_apps", settings.triggerApps)
            putBoolean("trigger_auto_stop", settings.triggerAutoStop)
            putBoolean("trigger_strict_mode", settings.triggerStrictMode)
            putString("tls_fingerprint_mode", settings.tlsFingerprintMode.name)
            putBoolean("auto_bypass_ru_packages", settings.autoBypassRuPackages)
            apply()
        }
    }

    var triggerWatcher: com.smarttools.netguard.service.ForegroundAppWatcher? = null
        private set

    fun updateTriggerWatcher(enabled: Boolean) {
        if (enabled) {
            val s = loadSettings()
            if (s.triggerApps.isNotEmpty() && s.triggerStrictMode) {
                // Strict mode: pre-warm a FULL trigger tunnel (TUN + xray + tun2socks)
                // for the trigger apps. xray sits ready, so opening a trigger app
                // is instant — no 1-2s "Connecting…" delay.
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
                ).launch {
                    val profile = database.profileDao().getSelected()
                    if (profile != null) {
                        com.smarttools.netguard.service.TunnelVpnService.startTriggerPrewarm(
                            this@App, profile.id
                        )
                    } else {
                        // No server picked yet — fall back to quarantine so apps
                        // still can't leak; user will be prompted to pick a server.
                        com.smarttools.netguard.service.TunnelVpnService.startQuarantine(this@App)
                    }
                }
            }
            // Watcher always runs — it fires the tunnel start (strict or flexible)
            // when a trigger app opens.
            if (triggerWatcher == null) {
                triggerWatcher = com.smarttools.netguard.service.ForegroundAppWatcher(this).also { it.start() }
            }
        } else {
            triggerWatcher?.stop()
            triggerWatcher = null
            val state = com.smarttools.netguard.service.TunnelVpnService.connectionState.value
            // Tear down only if the running tunnel is ours (trigger/quarantine).
            // A user-initiated global VPN should keep working.
            if (state is com.smarttools.netguard.model.ConnectionState.Disconnected ||
                com.smarttools.netguard.service.TunnelVpnService.isTriggerTunnel.value) {
                com.smarttools.netguard.service.TunnelVpnService.stop(this)
            }
        }
    }

}
