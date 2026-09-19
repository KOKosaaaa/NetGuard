package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.*
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in integration with the exact Xray revision shipped in the Android AAR. */
class ExactCoreSiteRoutingTest {
    @Test(timeout = 30000) fun authenticatedCoreCarriesWebsiteAndUdpThroughAdaptiveFrontend() {
        val executable = System.getenv("NETGUARD_XRAY_BINARY")
        assumeTrue(!executable.isNullOrBlank() && File(executable!!).isFile)
        val port = ServerSocket(0).use { it.localPort }
        val healthPort = ServerSocket(0).use { it.localPort }
        val config = File.createTempFile("netguard-core-site-test", ".json")
        val log = File.createTempFile("netguard-core-site-test", ".log")
        val user = "test-only-user"
        val password = "test-only-password"
        config.writeText("""{"log":{"loglevel":"error"},"inbounds":[
            {"tag":"tun-in","listen":"127.0.0.1","port":$port,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"$user","pass":"$password"}],"udp":true,"ip":"127.0.0.1"}},
            {"tag":"health-in","listen":"127.0.0.1","port":$healthPort,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"$user","pass":"$password"}],"udp":true,"ip":"127.0.0.1"}}
            ],"outbounds":[{"tag":"proxy","protocol":"freedom","settings":{}}]}""")
        var process: Process? = null
        val pool = Executors.newCachedThreadPool()
        val http = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val echo = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val heads = AtomicInteger()
        pool.execute {
            try { while (!http.isClosed) {
                val socket = http.accept()
                pool.execute { socket.use { s ->
                    s.soTimeout = 5000
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val c = s.getInputStream().read(); if (c < 0) break; header.append(c.toChar()) }
                    if (header.startsWith("HEAD / ")) heads.incrementAndGet()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ncore".toByteArray())
                } }
            } } catch (_: Exception) {}
        }
        pool.execute { try { while (!echo.isClosed) {
            val p = DatagramPacket(ByteArray(65536), 65536); echo.receive(p); echo.send(p)
        } } catch (_: Exception) {} }
        try {
            process = ProcessBuilder(executable!!, "run", "-config", config.absolutePath)
                .redirectErrorStream(true).redirectOutput(log).start()
            val until = System.nanoTime() + 5_000_000_000L
            while (true) {
                if (runCatching { Socket("127.0.0.1", port).use { } }.isSuccess) break
                check(process.isAlive && System.nanoTime() < until) { "Local exact-core startup failed" }
                Thread.sleep(30)
            }
            val normal = LocalSocks(port, user, password)
            val health = LocalSocks(healthPort, user, password)
            val transport = SiteRouteTransport(normal, health, { true }, publicAddress = { true })
            AdaptiveSiteProxy(transport, { "test-network" }, routeAllowed = { _, _ -> true }).use { frontend ->
                repeat(2) {
                    SocketChannel.open().use { c ->
                        c.socket().connect(InetSocketAddress("127.0.0.1", frontend.endpoint.port)); c.socket().soTimeout = 7000
                        SocksWire.handshake(c, frontend.endpoint, SocksDestination("127.0.0.1", http.localPort))
                        c.socket().getOutputStream().write("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".toByteArray())
                        c.socket().shutdownOutput()
                        assertTrue(String(c.socket().getInputStream().readBytes()).endsWith("core"))
                    }
                }
                val checksUntil = System.nanoTime() + 3_000_000_000L
                while (heads.get() < 2 && System.nanoTime() < checksUntil) Thread.sleep(10)
                assertEquals(2, heads.get()) // Asynchronous direct/VPN checks; traffic never waits for them.
                SocketChannel.open().use { control ->
                    control.socket().connect(InetSocketAddress("127.0.0.1", frontend.endpoint.port)); control.socket().soTimeout = 5000
                    val bound = SocksWire.handshake(control, frontend.endpoint, SocksDestination("0.0.0.0", 0), 3)
                    DatagramSocket().use { d ->
                        d.soTimeout = 5000
                        val header = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, (echo.localPort shr 8).toByte(), echo.localPort.toByte())
                        val packet = header + "voice-udp".toByteArray()
                        d.send(DatagramPacket(packet, packet.size, InetAddress.getByName(bound.host), bound.port))
                        val result = DatagramPacket(ByteArray(1024), 1024); d.receive(result)
                        assertArrayEquals(packet, result.data.copyOf(result.length))
                    }
                }
            }
        } finally {
            http.close(); echo.close(); pool.shutdownNow()
            process?.destroyForcibly()?.waitFor()
            config.delete(); log.delete()
        }
    }
}
