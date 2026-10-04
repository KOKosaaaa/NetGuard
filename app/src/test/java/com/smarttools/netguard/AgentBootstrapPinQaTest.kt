package com.smarttools.netguard

import com.smarttools.netguard.agent.AgentApiClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket

class AgentBootstrapPinQaTest {
    @Test fun bootstrapTlsRequiresSshPinBeforeAnyHttpToken() {
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/dpi/fixture.p12").use { store.load(it, "fixture-only".toCharArray()) }
        val key = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "fixture-only".toCharArray())
        }
        val tls = SSLContext.getInstance("TLS").apply { init(key.keyManagers, null, null) }
        val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("fixture").publicKey.encoded)
            .joinToString("") { "%02x".format(it) }
        val requests = AtomicInteger()
        // Isolate rejection from success: a failed TLS peer may close with SocketException,
        // not SSLException. It must never kill the listener serving the positive scenario.
        for ((expected, succeeds) in listOf("00".repeat(32) to false, pin to true)) {
            val pool = Executors.newSingleThreadExecutor()
            (tls.serverSocketFactory.createServerSocket(0, 2, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).use { server ->
                server.soTimeout = 3000
                val serving = pool.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        try {
                            val header = StringBuilder()
                            while (!header.endsWith("\r\n\r\n")) {
                                val byte = socket.getInputStream().read()
                                if (byte < 0) return@use
                                check(header.length < 8192)
                                header.append(byte.toChar())
                            }
                            requests.incrementAndGet()
                            check(succeeds) { "An unpinned endpoint received HTTP credentials" }
                            val length = header.lines().first { it.startsWith("Content-Length:", true) }
                                .substringAfter(':').trim().toInt()
                            check(length in 1..1024)
                            val body = ByteArray(length)
                            var offset = 0
                            while (offset < length) {
                                val read = socket.getInputStream().read(body, offset, length - offset)
                                check(read > 0) { "Truncated pairing body" }
                                offset += read
                            }
                            check(body.toString(Charsets.UTF_8) == "synthetic-pair-token")
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                            socket.getOutputStream().flush()
                        } catch (failure: IOException) {
                            if (succeeds) throw failure
                            // Negative case still must fail client-side with SSLException and
                            // the independent request counter must remain zero.
                        }
                    }
                }
                try {
                    val client = AgentApiClient.bootstrapClient(expected).newBuilder()
                        .retryOnConnectionFailure(false)
                        .callTimeout(3, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
                    try {
                        val request = Request.Builder().url("https://127.0.0.1:${server.localPort}/v1/auth/pair")
                            .post("synthetic-pair-token".toRequestBody()).build()
                        if (succeeds) client.newCall(request).execute().use { assertEquals("OK", it.body!!.string()) }
                        else assertThrows(SSLException::class.java) { client.newCall(request).execute().close() }
                    } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                    serving.get(4, TimeUnit.SECONDS)
                    assertEquals("Only the SSH-pinned endpoint may receive the token", if (succeeds) 1 else 0, requests.get())
                } finally { pool.shutdownNow() }
            }
        }
    }
}
