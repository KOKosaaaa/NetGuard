package com.smarttools.netguard

import android.app.Application
import android.content.SharedPreferences
import com.smarttools.netguard.database.AppDatabase
import com.smarttools.netguard.model.*
import com.smarttools.netguard.repository.ProfileRepository
import com.smarttools.netguard.repository.StatsRepository
import com.smarttools.netguard.repository.SubscriptionRepository
import com.smarttools.netguard.service.NotificationHelper
import com.smarttools.netguard.worker.SubscriptionUpdateWorker
import androidx.work.*
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

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        profileRepository = ProfileRepository(database.profileDao())
        subscriptionRepository = SubscriptionRepository(
            database.subscriptionDao(),
            database.profileDao()
        )
        statsRepository = StatsRepository(this)
        NotificationHelper.createChannel(this)
        scheduleSubscriptionUpdates()
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
            showSpeedInNotification = prefs.getBoolean("show_speed_notification", true)
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
            apply()
        }
    }
}
