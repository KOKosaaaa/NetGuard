package com.smarttools.netguard

import android.app.Application
import android.content.SharedPreferences
import com.smarttools.netguard.database.AppDatabase
import com.smarttools.netguard.model.*
import com.smarttools.netguard.repository.ProfileRepository
import com.smarttools.netguard.repository.StatsRepository
import com.smarttools.netguard.repository.SubscriptionRepository
import com.smarttools.netguard.service.NotificationHelper
import com.smarttools.netguard.service.WifiAutoConnectManager
import com.smarttools.netguard.worker.SubscriptionUpdateWorker
import androidx.work.*
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
            )
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
            apply()
        }
    }
}
