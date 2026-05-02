package com.smarttools.netguard.util

import android.util.Log
import com.smarttools.netguard.core.CredentialManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object ServiceTester {

    private const val TAG = "ServiceTester"

    data class Service(
        val name: String,
        val icon: String,
        val testUrl: String
    )

    val SERVICES = listOf(
        Service("YouTube", "\u25B6", "https://www.youtube.com/generate_204"),
        Service("Telegram", "\u2708", "https://web.telegram.org"),
        Service("Instagram", "\uD83D\uDCF7", "https://www.instagram.com"),
        Service("ChatGPT", "\uD83E\uDD16", "https://chat.openai.com"),
        Service("Discord", "\uD83D\uDCAC", "https://discord.com"),
        Service("Google", "\uD83D\uDD0D", "https://www.google.com/generate_204"),
        Service("Twitter/X", "X", "https://x.com"),
        Service("Spotify", "\uD83C\uDFB5", "https://open.spotify.com"),
    )

    data class TestResult(
        val service: Service,
        val accessible: Boolean,
        val responseTimeMs: Int = -1
    )

    suspend fun testAll(): List<TestResult> = withContext(Dispatchers.IO) {
        // Refuse to run without an active tunnel — otherwise the tests go
        // direct from the user's real IP to youtube.com / openai.com /
        // instagram.com / discord.com, leaking VPN-active intent and visited
        // services to the ISP. Surface that as a uniform error result so the
        // UI can show "Connect VPN first" instead of confusingly green checks.
        if (!isTunnelReady()) return@withContext SERVICES.map { TestResult(it, false, -1) }
        val client = buildClient() ?: return@withContext SERVICES.map { TestResult(it, false, -1) }
        SERVICES.map { service ->
            async { testService(service, client) }
        }.awaitAll()
    }

    suspend fun testSingle(index: Int): TestResult = withContext(Dispatchers.IO) {
        if (!isTunnelReady()) return@withContext TestResult(SERVICES[index], false, -1)
        val client = buildClient() ?: return@withContext TestResult(SERVICES[index], false, -1)
        testService(SERVICES[index], client)
    }

    private fun isTunnelReady(): Boolean {
        val state = com.smarttools.netguard.service.TunnelVpnService.connectionState.value
        return state is com.smarttools.netguard.model.ConnectionState.Connected
    }

    /**
     * Build an OkHttp client routed through our authenticated HTTP-in bridge.
     * Returns null when credentials aren't ready — the caller MUST treat that
     * as an error and not fall back to a direct OkHttpClient (which would
     * leak the test traffic to the ISP).
     */
    private fun buildClient(): OkHttpClient? {
        val httpPort = CredentialManager.getHttpPort() ?: return null
        val user = CredentialManager.getUser() ?: return null
        val pass = CredentialManager.getPass() ?: return null
        Log.d(TAG, "Testing through HTTP proxy 127.0.0.1:$httpPort")
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .proxy(proxy)
            .proxyAuthenticator { _: Route?, response: Response ->
                val credential = Credentials.basic(user, pass)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
            .build()
    }

    private fun testService(service: Service, client: OkHttpClient): TestResult {
        return try {
            val start = System.currentTimeMillis()

            val request = Request.Builder()
                .url(service.testUrl)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,*/*")
                .build()

            client.newCall(request).execute().use { response ->
                val elapsed = (System.currentTimeMillis() - start).toInt()
                val ok = response.code in 200..499
                Log.d(TAG, "${service.name}: HTTP ${response.code}, ${elapsed}ms, accessible=$ok")
                TestResult(service, ok, elapsed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "${service.name}: FAILED - ${e.javaClass.simpleName}: ${e.message}")
            TestResult(service, false)
        }
    }
}
