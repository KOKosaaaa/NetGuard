package com.smarttools.netguard.service

import android.content.Context
import com.smarttools.netguard.model.ConnectionState

object ServiceManager {

    fun start(context: Context, profileId: Long) {
        TunnelVpnService.start(context, profileId)
    }

    fun stop(context: Context) {
        TunnelVpnService.stop(context)
    }

    fun restart(context: Context, profileId: Long) {
        stop(context)
        start(context, profileId)
    }

    fun isRunning(): Boolean {
        return TunnelVpnService.connectionState.value is ConnectionState.Connected ||
               TunnelVpnService.connectionState.value is ConnectionState.Connecting
    }
}
