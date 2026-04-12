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

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope?.launch {
            TunnelVpnService.connectionState.collectLatest { state ->
                try {
                    updateTile(state)
                } catch (_: Exception) {
                    // qsTile may not be ready yet
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
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

    private fun updateTile(state: ConnectionState) {
        val tile = qsTile ?: return
        when (state) {
            is ConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Connected"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = ""
                }
            }
            is ConnectionState.Connecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Connecting..."
            }
            is ConnectionState.Disconnected -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "NetGuard"
            }
            is ConnectionState.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Error"
            }
        }
        tile.updateTile()
    }
}
