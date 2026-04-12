package com.smarttools.netguard.service

import android.net.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TrafficMonitor {

    private companion object {
        private const val TAG = "TrafficMonitor"
    }

    private var monitorJob: Job? = null
    private var startRx: Long = 0
    private var startTx: Long = 0
    private var prevRx: Long = 0
    private var prevTx: Long = 0

    fun start(
        scope: CoroutineScope,
        onUpdate: (TunnelVpnService.TrafficSnapshot) -> Unit
    ) {
        monitorJob = scope.launch {
            try {
                startRx = TrafficStats.getTotalRxBytes()
                startTx = TrafficStats.getTotalTxBytes()
                prevRx = startRx
                prevTx = startTx
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to init traffic stats", e)
                return@launch
            }

            while (isActive) {
                try {
                    delay(1000)
                    val nowRx = TrafficStats.getTotalRxBytes()
                    val nowTx = TrafficStats.getTotalTxBytes()

                    val speedRx = if (nowRx >= prevRx) nowRx - prevRx else 0
                    val speedTx = if (nowTx >= prevTx) nowTx - prevTx else 0

                    // Handle counter reset (system reboot): re-anchor start values
                    if (nowRx < startRx) startRx = nowRx
                    if (nowTx < startTx) startTx = nowTx
                    val totalRx = nowRx - startRx
                    val totalTx = nowTx - startTx

                    prevRx = nowRx
                    prevTx = nowTx

                    onUpdate(
                        TunnelVpnService.TrafficSnapshot(
                            rxBytes = totalRx,
                            txBytes = totalTx,
                            rxSpeed = speedRx,
                            txSpeed = speedTx
                        )
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error in traffic monitor loop", e)
                }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }
}
