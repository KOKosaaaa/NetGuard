package com.smarttools.netguard.util

import android.util.Log
import com.smarttools.netguard.core.CredentialManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

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
        // Set up SOCKS5 auth before tests
        val port = CredentialManager.getPort()
        val user = CredentialManager.getUser()
        val pass = CredentialManager.getPass()

        if (port != null && user != null && pass != null) {
            Log.d(TAG, "Testing through SOCKS5 proxy 127.0.0.1:$port")
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(user, pass.toCharArray())
                }
            })
        } else {
            Log.d(TAG, "No proxy available, testing direct connection")
        }

        try {
            SERVICES.map { service ->
                async { testService(service, port, user, pass) }
            }.awaitAll()
        } finally {
            if (port != null) {
                Authenticator.setDefault(null)
            }
        }
    }

    suspend fun testSingle(index: Int): TestResult = withContext(Dispatchers.IO) {
        val port = CredentialManager.getPort()
        val user = CredentialManager.getUser()
        val pass = CredentialManager.getPass()
        testService(SERVICES[index], port, user, pass)
    }

    private fun testService(service: Service, proxyPort: Int?, user: String?, pass: String?): TestResult {
        var connection: HttpURLConnection? = null
        return try {
            val start = System.currentTimeMillis()
            val url = URL(service.testUrl)

            connection = if (proxyPort != null && user != null && pass != null) {
                // Route through xray SOCKS5 proxy
                System.setProperty("socksProxyHost", "127.0.0.1")
                System.setProperty("socksProxyPort", proxyPort.toString())
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
                url.openConnection(proxy) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }

            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            connection.setRequestProperty("Accept", "text/html,*/*")
            val code = connection.responseCode
            val elapsed = (System.currentTimeMillis() - start).toInt()
            val ok = code in 200..499
            Log.d(TAG, "${service.name}: HTTP $code, ${elapsed}ms, accessible=$ok (proxy=${proxyPort != null})")
            TestResult(service, ok, elapsed)
        } catch (e: Exception) {
            Log.w(TAG, "${service.name}: FAILED - ${e.javaClass.simpleName}: ${e.message}")
            TestResult(service, false)
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }
}
