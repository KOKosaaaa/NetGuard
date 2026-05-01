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

object NotificationHelper {

    const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "net_service"
    private const val CHANNEL_NAME = "Network Service"

    private var channelCreated = false
    private var cachedContentIntent: PendingIntent? = null
    private var cachedStopIntent: PendingIntent? = null
    private var cachedTitle: String? = null
    private var cachedStopLabel: String? = null
    private var lastSpeedText: String? = null

    fun createChannel(context: Context) {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service status"
                setShowBadge(false)
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        channelCreated = true
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
        cachedTitle?.let { return it }
        return context.getString(R.string.notif_title).also { cachedTitle = it }
    }

    private fun getStopLabel(context: Context): String {
        cachedStopLabel?.let { return it }
        return context.getString(R.string.notif_stop).also { cachedStopLabel = it }
    }

    fun createConnectingNotification(context: Context): Notification {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_title_connecting))
            .setContentText(context.getString(R.string.notif_text_connecting))
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
            .setContentText(context.getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(getContentIntent(context))
            .addAction(R.drawable.ic_stop, getStopLabel(context), getStopIntent(context))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showConnectedNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createConnectedNotification(context))
    }

    fun createQuarantineNotification(context: Context): Notification {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_title_quarantine))
            .setContentText(context.getString(R.string.notif_text_quarantine))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(getContentIntent(context))
            .addAction(R.drawable.ic_stop, getStopLabel(context), getStopIntent(context))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showQuarantineNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createQuarantineNotification(context))
    }

    fun updateSpeedNotification(context: Context, rxSpeed: Long, txSpeed: Long) {
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
        cachedTitle = null
        cachedStopLabel = null
        lastSpeedText = null
        channelCreated = false
    }
}
