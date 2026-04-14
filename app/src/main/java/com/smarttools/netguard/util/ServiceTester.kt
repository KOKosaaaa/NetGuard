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
        val client = buildClient()
        SERVICES.map { service ->
            async { testService(service, client) }
        }.awaitAll()
    }

    suspend fun testSingle(index: Int): TestResult = withContext(Dispatchers.IO) {
        val client = buildClient()
        testService(SERVICES[index], client)
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)

        // Prefer SOCKS5 no-auth (works on all server configs)
        val noAuthPort = CredentialManager.getNoAuthSocksPort()
        if (noAuthPort != null) {
            Log.d(TAG, "Testing through SOCKS5 no-auth 127.0.0.1:$noAuthPort")
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", noAuthPort)))
        } else {
            // Fallback to HTTP proxy
            val httpPort = CredentialManager.getHttpPort()
            val user = CredentialManager.getUser()
            val pass = CredentialManager.getPass()
            if (httpPort != null && user != null && pass != null) {
                Log.d(TAG, "Testing through HTTP proxy 127.0.0.1:$httpPort")
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
                builder.proxy(proxy)
                builder.proxyAuthenticator { _: Route?, response: Response ->
                    val credential = Credentials.basic(user, pass)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            } else {
                Log.d(TAG, "No proxy available, testing direct connection")
            }
        }

        return builder.build()
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
