package com.smarttools.netguard.service

import com.smarttools.netguard.model.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Periodic service checks run even when the user is idle. */
class ThrottleDetector(
    private val scope: CoroutineScope,
    private val probe: SocksServiceProbe,
    private val settings: () -> AppSettings,
    private val network: () -> String?,
    private val receivedBytes: () -> Long,
    private val onHealthy: () -> Unit,
    private val onThrottle: (Set<HealthTarget>) -> Unit
) {
    private var job: Job? = null
    fun arm() {
        job = scope.launch {
            val policy = ServiceHealthPolicy()
            val parallel = Semaphore(3)
            var previousNetwork: String? = null
            var previousRx = receivedBytes()
            delay(30_000)
            while (isActive) {
                val prefs = settings()
                val currentNetwork = network()
                if (previousNetwork != currentNetwork) policy.reset()
                previousNetwork = currentNetwork
                if (!prefs.autoSwitchOnThrottle || currentNetwork == null) { policy.reset(); delay(10_000); continue }
                val selected = HealthTarget.entries.filter { it.name in prefs.healthCheckServices }
                if (selected.isEmpty()) { policy.reset(); delay(10_000); continue }
                val results = coroutineScope {
                    selected.map { target -> async(Dispatchers.IO) { parallel.withPermit { target to probe.check(target) } } }.awaitAll().toMap()
                }
                ensureActive()
                if (network() != currentNetwork) { policy.reset(); continue }
                val rx = receivedBytes()
                val receivingTraffic = rx - previousRx >= 1024
                previousRx = rx
                val failed = policy.record(results, receivingTraffic = receivingTraffic)
                LogBuffer.add(LogBuffer.LogLevel.INFO, "Проверка VPN: " + results.entries.joinToString { (target, state) ->
                    "${target.title}: " + when(state) { Reachability.AVAILABLE -> "доступен"; Reachability.LIMITED -> "ответил с ограничением"; Reachability.FAILED -> "нет ответа" }
                })
                if (failed.isNotEmpty()) onThrottle(failed)
                // Working traffic or even one responding service cancels a
                // queued switch. The user's existing sessions take priority.
                if (receivingTraffic || results.values.any { it != Reachability.FAILED }) onHealthy()
                delay(if (!receivingTraffic && results.values.all { it == Reachability.FAILED }) 30_000 else 120_000)
            }
        }
    }
    fun stop() { job?.cancel(); job = null; probe.close() }
}
