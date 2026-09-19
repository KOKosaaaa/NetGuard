package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.nio.channels.SocketChannel
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AdaptiveSiteProxyTest {
    private class Listener(private val handle: (Socket) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val port get() = listener.localPort
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val pool = Executors.newCachedThreadPool()
        init {
            pool.execute {
                while (!listener.isClosed) {
                    val s = try { listener.accept() } catch (_: Exception) { break }
                    sockets.add(s)
                    pool.execute { try { s.use { s.soTimeout = 7000; handle(s) } } catch (_: Exception) {} finally { sockets.remove(s) } }
                }
            }
        }
        override fun close() { listener.close(); sockets.forEach { it.close() }; pool.shutdownNow() }
    }

    private fun readHeaders(s: Socket): String {
        val result = StringBuilder()
        val input = s.getInputStream()
        while (!result.endsWith("\r\n\r\n") && result.length < 32768) {
            val c = input.read()
            if (c < 0) break
            result.append(c.toChar())
        }
        return result.toString()
    }

    private fun socks(s: Socket): Pair<Int, SocksDestination> {
        val input = DataInputStream(s.getInputStream())
        check(input.readUnsignedByte() == 5)
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        check(0.toByte() in methods)
        s.getOutputStream().write(byteArrayOf(5, 0))
        check(input.readUnsignedByte() == 5)
        val cmd = input.readUnsignedByte(); check(input.readUnsignedByte() == 0)
        return cmd to SocksWire.address(input)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue("Asynchronous route check did not finish", condition())
    }

    private fun request(endpoint: LocalSocks, port: Int, bytes: ByteArray, timeout: Int = 10_000): ByteArray = SocketChannel.open().use { client ->
        client.socket().connect(InetSocketAddress("127.0.0.1", endpoint.port))
        client.socket().soTimeout = timeout
        SocksWire.handshake(client, endpoint, SocksDestination("127.0.0.1", port))
        client.socket().getOutputStream().write(bytes)
        client.socket().shutdownOutput()
        client.socket().getInputStream().readBytes()
    }

    @Test fun slowHeadChecksAndSaturatedProbeWorkersNeverHoldFirstRequests() {
        val gate = CountDownLatch(1)
        val checksStarted = CountDownLatch(1)
        val actualRequests = AtomicInteger()
        Listener { s -> readHeaders(s); checksStarted.countDown(); gate.await(10, TimeUnit.SECONDS) }.use { direct ->
            Listener { s ->
                socks(s); s.getOutputStream().write(SocksWire.reply())
                val header = readHeaders(s)
                if (header.startsWith("HEAD / ")) { checksStarted.countDown(); gate.await(10, TimeUnit.SECONDS) }
                else {
                    actualRequests.incrementAndGet()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nfirst".toByteArray())
                }
            }.use { vpn ->
                val ep = LocalSocks(vpn.port, "", "")
                try {
                    AdaptiveSiteProxy(SiteRouteTransport(ep, ep, { true }, publicAddress = { true }),
                        { "network" }, routeAllowed = { _, _ -> true }).use { proxy ->
                        // More origins than both foreground/probe worker counts.
                        // HEADs remain blocked for the entire assertion. Real
                        // requests must arrive independently and succeed once.
                        repeat(24) { i ->
                            val requestBytes = "GET / HTTP/1.1\r\nHost: first$i.test\r\n\r\n".toByteArray()
                            assertTrue(String(request(proxy.endpoint, direct.port, requestBytes, timeout = 1000)).endsWith("first"))
                        }
                        assertTrue(checksStarted.await(1, TimeUnit.SECONDS))
                        assertEquals(1L, gate.count)
                        assertEquals(24, actualRequests.get())
                    }
                } finally { gate.countDown() }
            }
        }
    }

    @Test fun comparesOnFirstOpenCachesForWeekAndNeverReplaysPostOrBreaksHalfClose() {
        val directHeads = AtomicInteger()
        val vpnHeads = AtomicInteger()
        val posts = ConcurrentLinkedQueue<ByteArray>()
        val protected = AtomicInteger()
        val time = AtomicLong(2_000_000L)
        val cache = AtomicReference<String>()
        val body = ByteArray(300_000) { (it % 251).toByte() }
        Listener { s ->
            val header = readHeaders(s)
            if (header.startsWith("HEAD / ")) directHeads.incrementAndGet()
            s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }.use { direct ->
            Listener { s ->
                val (cmd, destination) = socks(s)
                check(cmd == 1 && destination.port == direct.port)
                s.getOutputStream().write(SocksWire.reply())
                val header = readHeaders(s)
                if (header.startsWith("HEAD / ")) {
                    vpnHeads.incrementAndGet()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                } else {
                    posts.add(header.toByteArray() + s.getInputStream().readBytes())
                    Thread.sleep(50) // The client has half-closed; the response still has to drain.
                    s.getOutputStream().write(body); s.shutdownOutput()
                }
            }.use { backend ->
                val ep = LocalSocks(backend.port, "", "")
                val transport = SiteRouteTransport(ep, ep, { protected.incrementAndGet(); !it.isConnected }, publicAddress = { true })
                AdaptiveSiteProxy(transport, { "network|server" }, saveCache = cache::set,
                    routeAllowed = { _, _ -> true }, now = time::get).use { proxy ->
                    val post = "POST /private?token=secret HTTP/1.1\r\nHost: site.test\r\nCookie: session=only-once\r\nContent-Length: 131071\r\n\r\n".toByteArray() + ByteArray(131071) { (it % 251).toByte() }
                    val clients = Executors.newFixedThreadPool(3)
                    try {
                        val responses = (1..3).map { clients.submit<ByteArray> { request(proxy.endpoint, direct.port, post) } }
                        responses.forEach { assertArrayEquals(body, it.get(10, TimeUnit.SECONDS)) }
                    } finally { clients.shutdownNow() }
                    awaitCondition { directHeads.get() == 1 && vpnHeads.get() == 1 }
                    assertEquals(3, posts.size)
                    posts.forEach { assertArrayEquals(post, it) }
                    assertTrue(protected.get() > 0)
                    // End the old session before advancing wall time. In a live
                    // session, the last asynchronous close rightly starts grace.
                    proxy.close()
                    time.addAndGet(SiteRoutingPolicy.WEEK_MS + 120_001)
                    val restoredTransport = SiteRouteTransport(ep, ep, { true }, publicAddress = { true })
                    AdaptiveSiteProxy(restoredTransport, { "network|server" }, loadCache = cache::get,
                        routeAllowed = { _, _ -> true }, now = time::get).use { restored ->
                        assertArrayEquals(body, request(restored.endpoint, direct.port, post))
                        awaitCondition { directHeads.get() == 2 && vpnHeads.get() == 2 }
                    }
                    assertEquals(2, directHeads.get()); assertEquals(2, vpnHeads.get())
                }
            }
        }
    }

    @Test fun cachedVpnFailureRechecksOnNextAttemptWithoutReplayingTheFailedRequest() {
        val broken = AtomicBoolean(false)
        val vpnRequests = AtomicInteger()
        val directRequests = AtomicInteger()
        val cache = AtomicReference<String>()
        Listener { s ->
            val head = readHeaders(s).startsWith("HEAD / ")
            if (!head) directRequests.incrementAndGet()
            s.getOutputStream().write((if (broken.get()) "HTTP/1.1 200 OK\r\n\r\ndirect" else "HTTP/1.1 403 Forbidden\r\n\r\n").toByteArray())
        }.use { direct ->
            Listener { s ->
                socks(s); s.getOutputStream().write(SocksWire.reply())
                val head = readHeaders(s).startsWith("HEAD / ")
                if (!head) vpnRequests.incrementAndGet()
                if (head || !broken.get()) s.getOutputStream().write(
                    (if (broken.get()) "HTTP/1.1 403 Forbidden\r\n\r\n" else "HTTP/1.1 200 OK\r\n\r\nvpn").toByteArray())
                // Simulate SOCKS accepting CONNECT, then the remote dial failing.
            }.use { vpn ->
                val ep = LocalSocks(vpn.port, "", "")
                AdaptiveSiteProxy(SiteRouteTransport(ep, ep, { true }, publicAddress = { true }), { "network" },
                    saveCache = cache::set, routeAllowed = { _, _ -> true }).use { proxy ->
                    val get = "GET / HTTP/1.1\r\nHost: site.test\r\n\r\n".toByteArray()
                    assertTrue(String(request(proxy.endpoint, direct.port, get)).endsWith("vpn"))
                    awaitCondition { cache.get()?.contains("\"path\":\"VPN\"") == true }
                    broken.set(true)
                    assertEquals(0, request(proxy.endpoint, direct.port, get).size)
                    assertEquals(2, vpnRequests.get()); assertEquals(0, directRequests.get())
                    awaitCondition { cache.get()?.contains("\"path\":\"DIRECT\"") == true }
                    assertTrue(String(request(proxy.endpoint, direct.port, get)).endsWith("direct"))
                    assertEquals(2, vpnRequests.get()); assertEquals(1, directRequests.get())
                }
            }
        }
    }

    @Test fun cachedDirectWinnerUsesProtectedSocketAndOpaqueProtocolUsesNormalBackend() {
        val directRequests = AtomicInteger()
        val normalRequests = AtomicInteger()
        val vpnHeads = AtomicInteger()
        val protected = AtomicInteger()
        Listener { s ->
            val h = readHeaders(s)
            if (!h.startsWith("HEAD / ")) directRequests.incrementAndGet()
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\ndirect".toByteArray())
        }.use { direct ->
            Listener { s -> socks(s); s.getOutputStream().write(SocksWire.reply()); normalRequests.incrementAndGet()
                s.getOutputStream().write(s.getInputStream().readBytes())
            }.use { normal ->
                Listener { s -> socks(s); s.getOutputStream().write(SocksWire.reply()); readHeaders(s); vpnHeads.incrementAndGet()
                    s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
                }.use { vpn ->
                    val t = SiteRouteTransport(LocalSocks(normal.port, "", ""), LocalSocks(vpn.port, "", ""),
                        { protected.incrementAndGet(); !it.isConnected }, publicAddress = { true })
                    val saved = SiteRoutingPolicy("network").apply {
                        record(SiteOrigin("site.test", direct.port, false), "127.0.0.1", SiteMeasurement(2, 10), SiteMeasurement(1, 30))
                    }.export()
                    AdaptiveSiteProxy(t, { "network" }, loadCache = { saved }, routeAllowed = { _, _ -> true }).use { proxy ->
                        val result = request(proxy.endpoint, direct.port, "GET / HTTP/1.1\r\nHost: site.test\r\n\r\n".toByteArray())
                        assertTrue(String(result).endsWith("direct")); assertEquals(1, directRequests.get())
                        assertEquals(0, vpnHeads.get()); assertEquals(0, normalRequests.get())
                        val opaque = "SSH-2.0-test\r\n".toByteArray()
                        assertArrayEquals(opaque, request(proxy.endpoint, 22, opaque))
                        assertEquals(1, normalRequests.get()); assertEquals(0, vpnHeads.get())
                        assertEquals(1, protected.get())
                    }
                }
            }
        }
    }

    @Test fun socksAuthenticationIsRequiredAndUdpCallsPassThrough() {
        val udpServer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val echoes = Executors.newSingleThreadExecutor()
        val udpCount = AtomicInteger()
        echoes.execute {
            try { while (!udpServer.isClosed) {
                val p = DatagramPacket(ByteArray(65536), 65536)
                udpServer.receive(p); udpCount.incrementAndGet(); udpServer.send(p)
            } } catch (_: Exception) {}
        }
        try {
            Listener { s ->
                check(socks(s).first == 3)
                s.getOutputStream().write(SocksWire.reply(udpServer.localPort))
                s.getInputStream().readBytes() // Association remains valid while control TCP is open.
            }.use { backend ->
                val ep = LocalSocks(backend.port, "", "")
                AdaptiveSiteProxy(SiteRouteTransport(ep, ep, { true }), { "network" }).use { proxy ->
                    Socket("127.0.0.1", proxy.endpoint.port).use { unauth ->
                        unauth.soTimeout = 2000
                        unauth.getOutputStream().write(byteArrayOf(5, 1, 0))
                        assertEquals(5, unauth.getInputStream().read()); assertEquals(255, unauth.getInputStream().read())
                    }
                    SocketChannel.open().use { control ->
                        control.socket().connect(InetSocketAddress("127.0.0.1", proxy.endpoint.port)); control.socket().soTimeout = 5000
                        val bound = SocksWire.handshake(control, proxy.endpoint, SocksDestination("0.0.0.0", 0), 3)
                        DatagramSocket().use { client ->
                            client.soTimeout = 2000
                            val header = byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 1, 0xbb.toByte())
                            val voice = header + ByteArray(160) { 42 }
                            fun send(b: ByteArray) = client.send(DatagramPacket(b, b.size, InetAddress.getByName("127.0.0.1"), bound.port))
                            send(voice)
                            val reply = DatagramPacket(ByteArray(2048), 2048); client.receive(reply)
                            assertArrayEquals(voice, reply.data.copyOf(reply.length))
                            val quic = ByteArray(1200).also { it[0] = 0xc0.toByte(); it[4] = 1; it[5] = 8; it[14] = 8 }
                            send(header + quic)
                            reply.length = reply.data.size; client.receive(reply)
                            assertArrayEquals(header + quic, reply.data.copyOf(reply.length))
                            assertEquals(2, udpCount.get())
                            send(voice); reply.length = reply.data.size; client.receive(reply)
                            assertArrayEquals(voice, reply.data.copyOf(reply.length))
                        }
                    }
                }
            }
        } finally { udpServer.close(); echoes.shutdownNow() }
    }
}
