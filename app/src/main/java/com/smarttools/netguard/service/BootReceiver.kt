package com.smarttools.netguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smarttools.netguard.App

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val app = context.applicationContext as App
            val prefs = app.getPreferences()
            val lastProfileId = prefs.getLong("last_profile_id", -1)
            val autoConnect = prefs.getBoolean("auto_connect_on_boot", false)
            if (autoConnect && lastProfileId != -1L) {
                TunnelVpnService.start(context, lastProfileId)
            }
            // Restore trigger watcher if it was enabled
            val settings = app.loadSettings()
            if (settings.triggerEnabled && settings.triggerApps.isNotEmpty()) {
                TriggerWatcherService.start(context)
            }
        }
    }
}
