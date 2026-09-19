package com.smarttools.netguard.service

import kotlinx.coroutines.*

class TrafficMonitor {
    private var monitorJob: Job? = null

    fun start(scope: CoroutineScope, onUpdate: (TunnelVpnService.TrafficSnapshot) -> Unit) {
        stop()
        monitorJob = scope.launch {
            val counter = TrafficRateCounter()
            while (isActive) {
                try {
                    // JNI layout: tx packets, tx bytes, rx packets, rx bytes.
                    // Count TUN traffic only; excluded apps never affect this.
                    val stats = hev.sockstun.TProxyService.TProxyGetStats()
                    if (stats != null && stats.size >= 4 && stats[1] >= 0 && stats[3] >= 0) {
                        val s = counter.sample(stats[3], stats[1], System.nanoTime())
                        onUpdate(TunnelVpnService.TrafficSnapshot(s.rxBytes, s.txBytes, s.rxPerSecond, s.txPerSecond))
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: LinkageError) { return@launch }
                catch (_: Exception) { /* Keep last sample while native stats are unavailable. */ }
                delay(2000)
            }
        }
    }

    fun stop() { monitorJob?.cancel(); monitorJob = null }
}
