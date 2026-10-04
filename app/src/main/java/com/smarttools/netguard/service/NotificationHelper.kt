package com.smarttools.netguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.smarttools.netguard.MainActivity
import com.smarttools.netguard.R
import com.smarttools.netguard.util.TrafficFormatter
import com.smarttools.netguard.util.LocalizedResources

object NotificationHelper {

    const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "net_service"
    private var channelTextKey: String? = null
    private var cachedContentIntent: PendingIntent? = null
    private var cachedStopIntent: PendingIntent? = null
    private var lastSpeedText: String? = null

    /** Permission, app-wide switch and the actual service channel must all allow posting. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.areNotificationsEnabled() &&
            nm.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun settingsIntent(context: Context): Intent {
        createChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val channelBlocked = nm.areNotificationsEnabled() &&
            nm.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        return Intent(if (channelBlocked) android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (channelBlocked) putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        }
    }

    @Synchronized fun createChannel(context: Context) {
        val localized = LocalizedResources.context(context)
        val name = localized.getString(R.string.notification_channel_name)
        val descriptionText = localized.getString(R.string.notification_channel_description)
        val key = "${localized.resources.configuration.locales.toLanguageTags()}|$name|$descriptionText"
        if (channelTextKey == key) return
        // minSdk 26 (O): notification channels always exist.
        val nm = context.getSystemService(NotificationManager::class.java)
        // Reuse the channel: preserve its user-selected importance, sound and badge.
        val channel = nm.getNotificationChannel(CHANNEL_ID) ?: NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        channel.name = name
        channel.description = descriptionText
        nm.createNotificationChannel(channel)
        channelTextKey = key
        lastSpeedText = null // Same rates must still refresh localized title/action.
    }

    private fun getContentIntent(context: Context): PendingIntent {
        cachedContentIntent?.let { return it }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ).also { cachedContentIntent = it }
    }

    private fun getStopIntent(context: Context): PendingIntent {
        cachedStopIntent?.let { return it }
        val stopIntent = Intent(context, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_STOP
        }
        return PendingIntent.getService(
            context, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ).also { cachedStopIntent = it }
    }

    private fun getTitle(context: Context): String {
        return LocalizedResources.context(context).getString(R.string.notif_title)
    }

    private fun getStopLabel(context: Context): String {
        return LocalizedResources.context(context).getString(R.string.notif_stop)
    }

    fun createConnectingNotification(context: Context): Notification {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(LocalizedResources.context(context).getString(R.string.notif_title_connecting))
            .setContentText(LocalizedResources.context(context).getString(R.string.notif_text_connecting))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(getContentIntent(context))
            .addAction(R.drawable.ic_stop, getStopLabel(context), getStopIntent(context))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun createConnectedNotification(context: Context): Notification {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(getTitle(context))
            .setContentText(LocalizedResources.context(context).getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(getContentIntent(context))
            .addAction(R.drawable.ic_stop, getStopLabel(context), getStopIntent(context))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showConnectedNotification(context: Context) {
        if (!canPost(context)) return
        // Re-enabling speed with the same idle rates must replace this plain text.
        lastSpeedText = null
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createConnectedNotification(context))
    }

    fun createQuarantineNotification(context: Context): Notification {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(LocalizedResources.context(context).getString(R.string.notif_title_quarantine))
            .setContentText(LocalizedResources.context(context).getString(R.string.notif_text_quarantine))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(getContentIntent(context))
            .addAction(R.drawable.ic_stop, getStopLabel(context), getStopIntent(context))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showQuarantineNotification(context: Context) {
        if (!canPost(context)) return
        lastSpeedText = null
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createQuarantineNotification(context))
    }

    fun updateSpeedNotification(context: Context, rxSpeed: Long, txSpeed: Long) {
        if (!canPost(context)) return
        createChannel(context)
        val speedText = "\u2193 ${TrafficFormatter.formatSpeed(rxSpeed)}  \u2191 ${TrafficFormatter.formatSpeed(txSpeed)}"
        // Skip if text hasn't changed
        if (speedText == lastSpeedText) return
        lastSpeedText = speedText

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(getTitle(context))
            .setContentText(speedText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(getContentIntent(context))
            .addAction(R.drawable.ic_stop, getStopLabel(context), getStopIntent(context))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    /** Clear cached state when service stops */
    fun invalidateCache() {
        cachedContentIntent = null
        cachedStopIntent = null
        lastSpeedText = null
        channelTextKey = null
    }
}
