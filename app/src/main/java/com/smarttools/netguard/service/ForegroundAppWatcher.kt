package com.smarttools.netguard.service

import android.app.AppOpsManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smarttools.netguard.App
import com.smarttools.netguard.MainActivity
import com.smarttools.netguard.R
import com.smarttools.netguard.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that polls UsageStatsManager every POLL_MS to detect
 * when a "trigger app" comes to the foreground. When detected, kicks off
 * TunnelVpnService in trigger mode (whitelist=this package). Until xray
 * is ready, packets sit in TUN with no tun2socks → black-holed.
 *
 * Requires PACKAGE_USAGE_STATS, granted by the user via system Settings.
 *
 * The thin wrapper class ForegroundAppWatcher (kept for App.kt API)
 * just starts/stops this service.
 */
class TriggerWatcherService : Service() {

    companion object {
        private const val TAG = "TriggerWatcher"
        private const val POLL_MS = 100L
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "trigger_watcher"

        const val ACTION_START = "com.smarttools.netguard.TRIGGER_WATCH_START"
        const val ACTION_STOP = "com.smarttools.netguard.TRIGGER_WATCH_STOP"

        fun hasUsageStatsPermission(ctx: Context): Boolean {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun start(ctx: Context) {
            val intent = Intent(ctx, TriggerWatcherService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, TriggerWatcherService::class.java).apply { action = ACTION_STOP }
            ctx.startService(intent)
        }
    }

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    private var lastTriggeredPkg: String? = null
    private var lastTriggeredAt: Long = 0L
    private var lastForegroundPkg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasUsageStatsPermission(this)) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted, exiting")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        if (loopJob?.isActive != true) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = s
            loopJob = s.launch { loop() }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        // Service type is declared in the manifest (specialUse with property);
        // the 2-arg startForeground picks it up on all SDK levels.
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = android.app.NotificationChannel(
            CHANNEL_ID,
            getString(R.string.trigger_watcher_channel),
            android.app.NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.trigger_watcher_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, TriggerWatcherService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.trigger_watcher_title))
            .setContentText(getString(R.string.trigger_watcher_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tap)
            .addAction(R.drawable.ic_stop, getString(R.string.trigger_watcher_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private suspend fun loop() {
        val app = application as App
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // Initial check — handle the case when watcher starts while a trigger
        // app is already in the foreground (e.g. user toggled trigger mode
        // from inside Telegram). queryEvents only catches transitions, not
        // the current state, so probe the last few seconds explicitly.
        val initialFg = currentForegroundPackage(usm, lookbackMs = 10_000L)
        if (initialFg != null) {
            lastForegroundPkg = initialFg
            onForegroundChanged(app, null, initialFg)
        }

        var lastCheck = System.currentTimeMillis()
        while (scope?.isActive == true) {
            val now = System.currentTimeMillis()
            try {
                val events = usm.queryEvents(lastCheck, now + 1)
                val ev = UsageEvents.Event()
                var latestFgPkg: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        latestFgPkg = ev.packageName
                    }
                }
                lastCheck = now

                if (latestFgPkg != null && latestFgPkg != lastForegroundPkg) {
                    val previousFg = lastForegroundPkg
                    lastForegroundPkg = latestFgPkg
                    val s = app.loadSettings()
                    Log.i(TAG, "FG change: $previousFg → $latestFgPkg (in list=${latestFgPkg in s.triggerApps}, list size=${s.triggerApps.size}, enabled=${s.triggerEnabled})")
                    onForegroundChanged(app, previousFg, latestFgPkg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryEvents failed: ${e.message}")
            }
            delay(POLL_MS)
        }
    }

    /** Find the most recent foreground package within the last [lookbackMs]. */
    private fun currentForegroundPackage(usm: UsageStatsManager, lookbackMs: Long): String? {
        val now = System.currentTimeMillis()
        return try {
            val events = usm.queryEvents(now - lookbackMs, now + 1)
            val ev = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    latest = ev.packageName
                }
            }
            latest
        } catch (e: Exception) {
            Log.w(TAG, "currentForegroundPackage failed: ${e.message}")
            null
        }
    }

    private fun onForegroundChanged(app: App, previous: String?, current: String) {
        val settings = app.loadSettings()
        if (!settings.triggerEnabled) return

        val isTrigger = current in settings.triggerApps
        val wasTrigger = previous != null && previous in settings.triggerApps

        if (isTrigger) {
            val state = TunnelVpnService.connectionState.value
            if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                lastTriggeredPkg = current
                return
            }
            lastTriggeredPkg = current
            lastTriggeredAt = System.currentTimeMillis()
            if (settings.triggerStrictMode) {
                Log.i(TAG, "Trigger '$current' → activate (strict)")
                TunnelVpnService.activateTrigger(app)
            } else {
                // Flexible mode: bring up the regular tunnel respecting
                // perAppMode/perAppList. The trigger list only chooses WHEN
                // the tunnel comes up, not WHO routes through it.
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
                ).launch {
                    val profileId = app.database.profileDao().getSelected()?.id
                        ?: app.getPreferences().getLong("last_profile_id", -1)
                    if (profileId != -1L) {
                        Log.i(TAG, "Trigger '$current' → start global VPN (flexible)")
                        TunnelVpnService.start(app, profileId)
                    } else {
                        Log.w(TAG, "Flexible trigger fired but no profile selected")
                    }
                }
            }
        } else if (wasTrigger && settings.triggerAutoStop) {
            if (settings.triggerStrictMode) {
                Log.i(TAG, "Trigger '$previous' left fg → deactivate (strict)")
                TunnelVpnService.deactivateTrigger(app)
            } else {
                Log.i(TAG, "Trigger '$previous' left fg → stop tunnel (flexible)")
                TunnelVpnService.stop(app)
            }
            lastTriggeredPkg = null
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}

/** Thin wrapper kept for App.kt's existing API surface. */
class ForegroundAppWatcher(private val app: App) {
    fun start() = TriggerWatcherService.start(app)
    fun stop() = TriggerWatcherService.stop(app)
}
