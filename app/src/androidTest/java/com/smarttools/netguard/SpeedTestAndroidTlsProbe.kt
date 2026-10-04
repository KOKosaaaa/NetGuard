package com.smarttools.netguard

import android.app.Instrumentation
import com.smarttools.netguard.service.LocalSocks
import com.smarttools.netguard.util.SpeedTestEngine
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real Android TLS + production Socket subclass. All destinations are loopback fixtures. */
internal class SpeedTestAndroidTlsProbe(private val test: Instrumentation) {
    fun run(keepAliveOnMain: Boolean = false): String {
        val store = KeyStore.getInstance("PKCS12")
        test.context.assets.open("dpi-fixture.p12").use { store.load(it, "fixture-only".toCharArray()) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "fixture-only".toCharArray())
        }
        val roots = KeyStore.getInstance("PKCS12").apply {
            load(null, null); setCertificateEntry("root", store.getCertificateChain("fixture").last())
        }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        val trust = tm.trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(trust), null) }
        val bind = InetAddress.getByName("127.0.0.1")
        val https = tls.serverSocketFactory.createServerSocket(0, 16, bind)
        val socks = ServerSocket(0, 16, bind)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val active = ConcurrentHashMap.newKeySet<Socket>()
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "qa-home-android-tls").apply { isDaemon = true } }
        val latency = AtomicInteger(); val downloads = AtomicInteger(); val uploads = AtomicInteger()
        val keepAliveClosed = java.util.concurrent.CountDownLatch(1)
        val connectedHosts = ConcurrentLinkedQueue<String>()
        val tlsProtocols = ConcurrentLinkedQueue<String>()
        val bodySize = 262_144
        val body = ByteArray(bodySize) { ((it * 71 + 19) and 255).toByte() }
        fun accept(server: ServerSocket, action: (Socket) -> Unit) {
            pool.execute {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (t: Throwable) {
                        if (!server.isClosed) errors.add(t)
                        break
                    }
                    active.add(client)
                    pool.execute {
                        try { client.use { it.soTimeout = 10_000; action(it) } }
                        catch (t: Throwable) { if (!server.isClosed) errors.add(t) }
                        finally { active.remove(client) }
                    }
                }
            }
        }
        fun header(s: Socket): String {
            val result = StringBuilder()
            val input = s.getInputStream()
            while (!result.endsWith("\r\n\r\n")) {
                val c = input.read()
                if(c < 0 && result.isEmpty())throw java.io.EOFException()
                check(c >= 0 && result.length < 16_384) { "Missing/big HTTP request header" }
                result.append(c.toChar())
            }
            return result.toString()
        }
        fun reply(s: Socket, bytes: ByteArray) {
            val output = s.getOutputStream()
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: ${if(keepAliveOnMain) "keep-alive" else "close"}\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(bytes); output.flush()
        }
        try {
            accept(https) { s ->
                do {
                val h = try { header(s) } catch(e:java.io.EOFException) {
                    if(keepAliveOnMain && uploads.get()==1) {keepAliveClosed.countDown();return@accept};throw e
                }
                tlsProtocols.add((s as SSLSocket).session.protocol)
                check(h.contains("Host: dpi.test:${https.localPort}", true)) { "TLS HTTP host changed" }
                check(h.contains("Accept-Encoding: identity", true))
                check(h.contains("User-Agent: NetGuard-SpeedTest", true))
                when {
                    h.startsWith("GET ") && Regex("[?&]bytes=0(?:&| )").containsMatchIn(h) -> {
                        latency.incrementAndGet(); Thread.sleep(25); reply(s, byteArrayOf())
                    }
                    h.startsWith("GET ") -> {
                        check(Regex("[?&]bytes=$bodySize(?:&| )").containsMatchIn(h))
                        downloads.incrementAndGet(); reply(s, body)
                    }
                    h.startsWith("POST ") -> {
                        val n = Regex("(?im)^Content-Length: (\\d+)\\r?$").find(h)?.groupValues?.get(1)?.toInt()
                        check(n == bodySize) { "Unexpected upload framing: $n" }
                        val upload = ByteArray(bodySize)
                        DataInputStream(s.getInputStream()).readFully(upload)
                        check(upload.any { it != 0.toByte() }) { "No real upload payload" }
                        uploads.incrementAndGet(); reply(s, "ack".toByteArray())
                    }
                    else -> error("Unexpected HTTP method")
                }
                } while(keepAliveOnMain)
            }
            accept(socks) { client ->
                val input = DataInputStream(client.getInputStream())
                val greeting = ByteArray(3).also(input::readFully)
                check(greeting.contentEquals(byteArrayOf(5, 1, 0))) { "WB no-auth greeting changed" }
                client.getOutputStream().write(byteArrayOf(5, 0))
                check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
                check(input.readUnsignedByte() == 3) { "DNS escaped SOCKS: expected DOMAIN" }
                val host = String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
                val port = input.readUnsignedShort()
                check(host == "dpi.test" && port == https.localPort) { "Incorrect SOCKS target" }
                connectedHosts.add(host)
                Socket(bind, https.localPort).use { remote ->
                    active.add(remote)
                    client.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                    val upstream = pool.submit {
                        // Reset/close during TLS shutdown is normal; protocol assertions above are not suppressed.
                        runCatching { client.getInputStream().copyTo(remote.getOutputStream()) }
                        runCatching { remote.shutdownOutput() }
                    }
                    try {
                        try { remote.getInputStream().copyTo(client.getOutputStream()) }
                        catch (_: java.io.IOException) { /* Peer may close immediately after the complete HTTP body. */ }
                    }
                    finally {
                        remote.close(); client.close(); active.remove(remote)
                        upstream.get(2, TimeUnit.SECONDS)
                    }
                }
            }
            val stages = mutableListOf<SpeedTestEngine.Stage>()
            val engine = SpeedTestEngine(LocalSocks(socks.localPort, "", ""),
                SpeedTestEngine.Config("https://dpi.test:${https.localPort}/down", "https://dpi.test:${https.localPort}/up",
                    bodySize, bodySize, 1, 30_000, 8_000), customize = {
                    // Trust only this fixture CA. Keep OkHttp's DEFAULT hostname verifier.
                    sslSocketFactory(tls.socketFactory, trust)
                })
            val result = runBlocking {
                if(keepAliveOnMain)kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
                    engine.run { stages.add(it) }.also { check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) }
                } else engine.run { stages.add(it) }
            }
            if(keepAliveOnMain)check(keepAliveClosed.await(2,TimeUnit.SECONDS)) { "Main caller completed but kept live TLS connection" }
            check(result.downloadMbps > 0 && result.uploadMbps > 0 && result.latencyMs > 0) { "Android TLS transfer failed: $result; peerErrors=$errors" }
            check(latency.get() == 3 && downloads.get() == 1 && uploads.get() == 1) {
                "Missing actual HTTPS transfer: latency=${latency.get()} download=${downloads.get()} upload=${uploads.get()}"
            }
            check(stages == SpeedTestEngine.Stage.entries)
            check(connectedHosts.size == (if(keepAliveOnMain)1 else 5) && connectedHosts.all { it == "dpi.test" })
            check(tlsProtocols.size == 5 && tlsProtocols.all { it.startsWith("TLS") })
            check(errors.isEmpty()) { "Android TLS fixture assertion failure: $errors" }
            return "PASS Android ${tls.provider.name}: production SpeedTestEngine/SOCKS/hostname-verified TLS, mainCallerKeepAlive=$keepAliveOnMain, 3 latency + 262144B DL + 262144B acknowledged UL; $result"
        } finally {
            https.close(); socks.close()
            active.forEach { runCatching { it.close() } }
            pool.shutdownNow()
            check(pool.awaitTermination(3, TimeUnit.SECONDS)) { "Android TLS fixture worker/socket cleanup stalled" }
        }
    }
}
