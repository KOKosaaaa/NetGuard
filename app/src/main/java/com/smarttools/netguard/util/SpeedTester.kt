package com.smarttools.netguard.util

import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.service.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SpeedTester {
    data class SpeedResult(val downloadMbps: Double, val uploadMbps: Double, val pingMs: Int,
        val timedOut: Boolean = false)
    enum class Stage { LATENCY, DOWNLOAD, UPLOAD }

    internal suspend fun run(proxy: CredentialManager.SpeedProxy, progress: (Stage) -> Unit): SpeedResult = withContext(Dispatchers.IO) {
        val result = SpeedTestEngine(proxy.endpoint, SpeedTestEngine.productionConfig(), log = {
            LogBuffer.add(LogBuffer.LogLevel.WARN, "[speed-test] $it")
        }).run { progress(Stage.valueOf(it.name)) }
        LogBuffer.add(LogBuffer.LogLevel.INFO, "[speed-test] finished: download=${result.downloadMbps} Mbps upload=${result.uploadMbps} Mbps HTTP latency=${result.latencyMs} ms")
        SpeedResult(result.downloadMbps, result.uploadMbps, result.latencyMs, result.timedOut)
    }
}
