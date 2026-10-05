package com.smarttools.netguard.agent

import android.content.res.AssetManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

/** Called on Dispatchers.IO. Check the actual capability after a self-update,
 * rather than accepting /health from the old agent while it is shutting down. */
object WbStreamUpdater {
    internal const val REQUIRED_REVISION = 15
    internal fun needsUpdate(revision: Int) = revision < REQUIRED_REVISION
    suspend fun ensureTransport(client: AgentApiClient, assets: AssetManager, onUpdate: () -> Unit = {}) {
        val revision = try { client.wbStreamTransportRevision() } catch (e: AgentApiError) {
            if (e.httpCode == 404) 0 else throw e
        }
        if (!needsUpdate(revision)) return
        onUpdate()
        val arch = client.status().agentArch
        require(arch in listOf("amd64", "arm64")) { "Неизвестная архитектура сервера: $arch" }
        val bytes = BundledAgent.read(assets, arch)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        client.uploadAgentBinary(bytes, sha)
        withTimeout(90_000) {
            while (true) {
                delay(2000)
                if (!needsUpdate(runCatching { client.wbStreamTransportRevision() }.getOrDefault(0))) break
            }
        }
    }
}
