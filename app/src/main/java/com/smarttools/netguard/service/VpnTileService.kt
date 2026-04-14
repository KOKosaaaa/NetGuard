package com.smarttools.netguard.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.smarttools.netguard.App
import com.smarttools.netguard.MainActivity
import com.smarttools.netguard.model.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class VpnTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var lastTileState: Int = -1
    private var lastTileLabel: String? = null

    override fun onStartListening() {
        super.onStartListening()
        // Immediate sync update from cached state — no coroutine needed
        val current = TunnelVpnService.connectionState.value
        updateTileIfChanged(current)

        // Only create scope if not already active
        if (scope?.isActive != true) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        scope?.launch {
            TunnelVpnService.connectionState.collectLatest { state ->
                try {
                    updateTileIfChanged(state)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun updateTileIfChanged(state: ConnectionState) {
        val tile = qsTile ?: return
        val newState: Int
        val newLabel: String
        when (state) {
            is ConnectionState.Connected -> {
                newState = Tile.STATE_ACTIVE
                newLabel = "Connected"
            }
            is ConnectionState.Connecting -> {
                newState = Tile.STATE_ACTIVE
                newLabel = "Connecting..."
            }
            is ConnectionState.Disconnected -> {
                newState = Tile.STATE_INACTIVE
                newLabel = "NetGuard"
            }
            is ConnectionState.Error -> {
                newState = Tile.STATE_INACTIVE
                newLabel = "Error"
            }
        }
        // Skip expensive updateTile() if nothing changed
        if (newState == lastTileState && newLabel == lastTileLabel) return
        lastTileState = newState
        lastTileLabel = newLabel
        tile.state = newState
        tile.label = newLabel
        if (state is ConnectionState.Connected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = ""
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val state = TunnelVpnService.connectionState.value

        when (state) {
            is ConnectionState.Connected -> {
                TunnelVpnService.stop(applicationContext)
            }
            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                val app = application as App
                val profileId = app.getPreferences().getLong("last_profile_id", -1)
                if (profileId != -1L) {
                    // Check VPN permission — if not granted, open app
                    val prepareIntent = android.net.VpnService.prepare(applicationContext)
                    if (prepareIntent != null) {
                        val intent = Intent(applicationContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("auto_connect", true)
                        }
                        collapseAndStart(intent)
                    } else {
                        TunnelVpnService.start(applicationContext, profileId)
                    }
                } else {
                    // No profile — open app
                    val intent = Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    collapseAndStart(intent)
                }
            }
            is ConnectionState.Connecting -> { /* ignore during connecting */ }
        }
    }

    private fun collapseAndStart(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                applicationContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
