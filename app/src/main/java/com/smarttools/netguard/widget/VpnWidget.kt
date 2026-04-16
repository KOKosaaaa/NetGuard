package com.smarttools.netguard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.smarttools.netguard.MainActivity
import com.smarttools.netguard.R
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.service.TunnelVpnService
import com.smarttools.netguard.App

class VpnWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.smarttools.netguard.WIDGET_TOGGLE"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, VpnWidget::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                val intent = Intent(context, VpnWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            handleToggle(context)
        }
    }

    private fun handleToggle(context: Context) {
        val state = TunnelVpnService.connectionState.value
        when (state) {
            is ConnectionState.Connected -> {
                TunnelVpnService.stop(context)
            }
            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                // Check VPN permission
                val prepareIntent = android.net.VpnService.prepare(context)
                if (prepareIntent != null) {
                    // Need permission — open app
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("auto_connect", true)
                    }
                    context.startActivity(activityIntent)
                } else {
                    val app = context.applicationContext as App
                    val profileId = app.getPreferences().getLong("last_profile_id", -1)
                    if (profileId != -1L) {
                        TunnelVpnService.start(context, profileId)
                    } else {
                        // No profile — open app
                        val activityIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(activityIntent)
                    }
                }
            }
            is ConnectionState.Connecting -> { /* ignore during connecting */ }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_vpn)

        // Set click action on the whole widget
        val toggleIntent = Intent(context, VpnWidget::class.java).apply {
            action = ACTION_TOGGLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        // Connection state
        val state = TunnelVpnService.connectionState.value
        val bgRes = when (state) {
            is ConnectionState.Connected -> R.drawable.bg_button_connected
            is ConnectionState.Connecting -> R.drawable.bg_button_connecting
            else -> R.drawable.bg_button_disconnected
        }
        views.setInt(R.id.iv_widget_icon, "setBackgroundResource", bgRes)

        // Status text
        val statusText = when (state) {
            is ConnectionState.Connected -> context.getString(R.string.status_connected)
            is ConnectionState.Connecting -> context.getString(R.string.status_connecting)
            is ConnectionState.Error -> context.getString(R.string.status_error)
            else -> context.getString(R.string.status_disconnected)
        }
        views.setTextViewText(R.id.tv_widget_status, statusText)

        // Server name — read from cached SharedPreferences (updated on profile select
        // & VPN start). No blocking DB query on the main broadcast thread (ANR risk).
        val app = context.applicationContext as App
        val cachedName = app.getPreferences().getString("last_profile_name", null)
        views.setTextViewText(
            R.id.tv_widget_server,
            cachedName?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.no_profile_selected)
        )

        manager.updateAppWidget(widgetId, views)
    }
}
