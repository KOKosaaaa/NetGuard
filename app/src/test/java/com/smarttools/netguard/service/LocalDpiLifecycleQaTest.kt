package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LocalDpiLifecycleQaTest {
    private fun encoded(write: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()
    private fun ext(type: Int, body: ByteArray) = encoded { writeShort(type); writeShort(body.size); write(body) }
    private fun hello(extra: ByteArray = byteArrayOf()): ByteArray {
        val host = "example.test".toByteArray()
        val sni = ext(0, encoded { writeShort(host.size + 3); writeByte(0); writeShort(host.size); write(host) })
        val extensions = sni + extra
        val body = encoded {
            writeShort(0x0303); write(ByteArray(32)); writeByte(0); writeShort(2); writeShort(0x1301)
            writeByte(1); writeByte(0); writeShort(extensions.size); write(extensions)
        }
        val handshake = byteArrayOf(1, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
        return encoded { writeByte(22); writeShort(0x0303); writeShort(handshake.size); write(handshake) }
    }
    private fun validPsk(binders: Int = 1): ByteArray {
        val identities = encoded { writeShort(1); writeByte(0x55); writeInt(1234567) }
        val binderBytes = encoded { repeat(binders) { writeByte(32); write(ByteArray(32) { (it + 31).toByte() }) } }
        return ext(41, encoded { writeShort(identities.size); write(identities); writeShort(binderBytes.size); write(binderBytes) })
    }
    private fun payload(wire: ByteArray): ByteArray {
        var p = 0; val output = ByteArrayOutputStream()
        while (p < wire.size) {
            val n = ((wire[p + 3].toInt() and 255) shl 8) or (wire[p + 4].toInt() and 255)
            output.write(wire, p + 5, n); p += n + 5
        }
        return output.toByteArray()
    }
    @Test fun validPskIdentityAgeAndBinderAreByteIdenticalAfterBothStrategies() {
        val original = hello(validPsk())
        for (strategy in LocalDpi.paths) {
            val split = requireNotNull(LocalDpi.transform(original, strategy))
            assertArrayEquals(payload(original), payload(split))
        }
    }
    @Test fun pskMustBeLastAndIdentityBinderCountsMustMatch() {
        for (wire in listOf(hello(validPsk() + ext(21, byteArrayOf(0))), hello(validPsk(2)), hello(validPsk(0))))
            for (strategy in LocalDpi.paths) assertNull(LocalDpi.transform(wire, strategy))
    }
    @Test fun closedScopeCannotResolveOrOpenAnyDirectSocket() {
        val lookups = AtomicInteger(); val protections = AtomicInteger()
        val ep = LocalSocks(9, "", "")
        SiteRouteTransport(ep, ep, { protections.incrementAndGet(); true }, resolve = {
            lookups.incrementAndGet(); listOf(InetAddress.getLoopbackAddress())
        }, publicAddress = { true }).use { transport ->
            val scope = transport.scope(); scope.close()
            try { transport.connect(SocksDestination("example.test", 443), SitePath.TLS_RECORD_SNI, scope); fail("closed scope must fail") }
            catch (_: Exception) { }
            assertEquals("DNS must not happen after cancellation", 0, lookups.get())
            assertEquals(0, protections.get())
        }
    }

    private class Listener(private val handler: (Socket) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val port get() = listener.localPort
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val pool = Executors.newCachedThreadPool()
        val errors = ConcurrentLinkedQueue<Throwable>()
        init { pool.execute {
            while (!listener.isClosed) {
                val s = try { listener.accept() } catch (_: Exception) { break }
                sockets.add(s)
                pool.execute { try { s.use { s.soTimeout = 4000; handler(s) } }
                    catch (e: Throwable) { errors.add(e) } finally { sockets.remove(s) } }
            }
        } }
        override fun close() { listener.close(); sockets.forEach { runCatching { it.close() } }; pool.shutdownNow() }
    }
    private fun socks(s: Socket) {
        val input = DataInputStream(s.getInputStream()); val output = s.getOutputStream()
        check(input.readUnsignedByte() == 5)
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully); check(0.toByte() in methods)
        output.write(byteArrayOf(5, 0)); check(input.readUnsignedByte() == 5)
        check(input.readUnsignedByte() == 1); check(input.readUnsignedByte() == 0)
        SocksWire.address(input); output.write(SocksWire.reply())
    }
    private fun request(endpoint: LocalSocks, port: Int, bytes: ByteArray): String = SocketChannel.open().use { channel ->
        channel.socket().soTimeout = 4000; channel.socket().connect(InetSocketAddress("127.0.0.1", endpoint.port))
        SocksWire.handshake(channel, endpoint, SocksDestination("127.0.0.1", port))
        channel.socket().getOutputStream().write(bytes); channel.socket().shutdownOutput()
        channel.socket().getInputStream().readBytes().toString(Charsets.US_ASCII)
    }
    private fun cached(port: Int, checkedAt: Long = System.currentTimeMillis()): String = SiteRoutingPolicy("qa", { checkedAt }).apply {
        record(SiteOrigin("example.test", port, true), "127.0.0.1", SiteMeasurement(), SiteMeasurement(2, 100, "BODY", "VERIFIED", 65536, 200),
            SitePath.TLS_RECORD_SNI, SiteMeasurement(2, 90, "BODY", "VERIFIED_TWICE", 65536, 200))
    }.export()

    @Test fun selectedAndUnknownOwnersNeverProbeOrUseCachedTlsDirectStrategy() {
        val directBodies = ConcurrentLinkedQueue<ByteArray>(); val vpnBodies = ConcurrentLinkedQueue<ByteArray>()
        val resolves = AtomicInteger(); val protects = AtomicInteger(); val normalConnections = AtomicInteger()
        val diagnostics = RouteDiagnostics()
        Listener { s -> directBodies.add(s.getInputStream().readBytes()); s.getOutputStream().write("direct".toByteArray()) }.use { direct ->
            Listener { s -> socks(s); vpnBodies.add(s.getInputStream().readBytes()); s.getOutputStream().write("vpn".toByteArray()) }.use { vpn ->
                Listener { normalConnections.incrementAndGet() }.use { normal ->
                    val routes = AppRoutePolicy(setOf(10001), true) { if (it.local.port == 40001) 10001 else 10002 }
                    val transport = SiteRouteTransport(LocalSocks(normal.port, "", ""), LocalSocks(vpn.port, "", ""),
                        { protects.incrementAndGet(); true }, resolve = { resolves.incrementAndGet(); listOf(InetAddress.getByName("127.0.0.1")) }, publicAddress = { true })
                    AdaptiveSiteProxy(transport, { "qa" }, loadCache = { cached(direct.port) }, forceVpn = routes::forceVpn,
                        localDpi = { true }, diagnostics = diagnostics, routeAllowed = { _, _ -> true }).use { proxy ->
                        val original = hello()
                        fun app(port: Int) = proxy.endpoint.copy(user = proxy.endpoint.user + "|6|10.10.10.1|$port|127.0.0.1|${direct.port}")
                        assertEquals("vpn", request(app(40001), direct.port, original))
                        assertEquals("vpn", request(proxy.endpoint, direct.port, original))
                        assertEquals(0, protects.get()); assertEquals(0, resolves.get()); assertEquals(0, directBodies.size)
                        assertEquals(2, vpnBodies.size); vpnBodies.forEach { assertArrayEquals(original, it) }
                        val strictSnapshot = diagnostics.render(false, false, true)
                        assertTrue(strictSnapshot, strictSnapshot.contains("Route reason: ONLY_VPN"))
                        assertTrue(strictSnapshot, strictSnapshot.contains("VPN: connections=2,"))
                        assertTrue(strictSnapshot, strictSnapshot.contains("↑${2 * original.size} B ↓6 B"))
                        assertFalse(strictSnapshot, strictSnapshot.contains("TLS_RECORD_SNI: connections="))
                        // Positive control: the very same saved strategy actually applies to an ordinary UID.
                        assertEquals("direct", request(app(40002), direct.port, original))
                        assertEquals(1, protects.get()); assertEquals(1, resolves.get()); assertEquals(1, directBodies.size)
                        assertArrayEquals(LocalDpi.transform(original, SitePath.TLS_RECORD_SNI), directBodies.single())
                        assertEquals(0, normalConnections.get())
                        val snapshot = diagnostics.render(false, false, true)
                        assertFalse(snapshot, snapshot.contains("previous network"))
                        assertTrue(snapshot, snapshot.contains("TLS_RECORD_SNI: connections=1,"))
                        assertTrue(snapshot, snapshot.contains("↑${LocalDpi.transform(original, SitePath.TLS_RECORD_SNI)!!.size} B ↓6 B"))
                    }
                    assertTrue(direct.errors.toString(), direct.errors.isEmpty()); assertTrue(vpn.errors.toString(), vpn.errors.isEmpty())
                }
            }
        }
    }
    @Test fun disablingLocalDpiSendsOriginalBytesViaVpnWithoutDirectProbe() {
        val protected = AtomicInteger(); val vpnBodies = ConcurrentLinkedQueue<ByteArray>()
        val diagnostics = RouteDiagnostics()
        Listener { s -> socks(s); vpnBodies.add(s.getInputStream().readBytes()); s.getOutputStream().write("vpn".toByteArray()) }.use { vpn ->
            val ep = LocalSocks(vpn.port, "", "")
            AdaptiveSiteProxy(SiteRouteTransport(ep, ep, { protected.incrementAndGet(); true }), { "qa" },
                loadCache = { cached(443) }, localDpi = { false }, diagnostics = diagnostics, routeAllowed = { _, _ -> true }).use { proxy ->
                val original = hello(); assertEquals("vpn", request(proxy.endpoint, 443, original))
                assertEquals(0, protected.get()); assertEquals(1, vpnBodies.size); assertArrayEquals(original, vpnBodies.single())
                val snapshot = diagnostics.render(false, false, false)
                assertTrue(snapshot, snapshot.contains("Route reason: DPI_DISABLED"))
                assertTrue(snapshot, snapshot.contains("VPN: connections=1,"))
                assertTrue(snapshot, snapshot.contains("↑${original.size} B ↓3 B"))
                assertFalse(snapshot, snapshot.contains("TLS_RECORD_SNI: connections="))
            }
            assertTrue(vpn.errors.toString(), vpn.errors.isEmpty())
        }
    }
    @Test fun unsupportedHelloActuallyUsesVpnAndNeverClaimsCachedStrategyApplication() {
        val protected = AtomicInteger(); val vpnBodies = ConcurrentLinkedQueue<ByteArray>()
        val diagnostics = RouteDiagnostics()
        Listener { s -> socks(s); vpnBodies.add(s.getInputStream().readBytes()); s.getOutputStream().write("vpn".toByteArray()) }.use { vpn ->
            val ep = LocalSocks(vpn.port, "", "")
            AdaptiveSiteProxy(SiteRouteTransport(ep, ep, { protected.incrementAndGet(); true }), { "qa" },
                loadCache = { cached(443) }, localDpi = { true }, diagnostics = diagnostics,
                routeAllowed = { _, _ -> true }).use { proxy ->
                // SNI is recognizable, but TLS early_data makes the local transformer refuse this ClientHello.
                val original = hello(ext(42, byteArrayOf()))
                assertNull(LocalDpi.transform(original, SitePath.TLS_RECORD_SNI))
                assertEquals("vpn", request(proxy.endpoint, 443, original))
                assertEquals(0, protected.get()); assertEquals(1, vpnBodies.size)
                assertArrayEquals(original, vpnBodies.single())
                val snapshot = diagnostics.render(false, false, true)
                assertTrue(snapshot, snapshot.contains("Route reason: UNSUPPORTED_HELLO"))
                assertTrue(snapshot, snapshot.contains("VPN: connections=1,"))
                assertTrue(snapshot, snapshot.contains("↑${original.size} B ↓3 B"))
                assertFalse(snapshot, snapshot.contains("TLS_RECORD_SNI: connections="))
            }
            assertTrue(vpn.errors.toString(), vpn.errors.isEmpty())
        }
    }
    @Test fun returningToSameNetworkAfterOnlyVpnTrafficKeepsNewFlowsInCurrentContext() {
        val diagnostics = RouteDiagnostics()
        val network = java.util.concurrent.atomic.AtomicReference("qa")
        val forced = java.util.concurrent.atomic.AtomicBoolean(false)
        val protected = AtomicInteger()
        val directBodies = ConcurrentLinkedQueue<ByteArray>(); val vpnBodies = ConcurrentLinkedQueue<ByteArray>()
        Listener { s -> directBodies.add(s.getInputStream().readBytes()); s.getOutputStream().write("direct".toByteArray()) }.use { direct ->
            Listener { s -> socks(s); vpnBodies.add(s.getInputStream().readBytes()); s.getOutputStream().write("vpn".toByteArray()) }.use { vpn ->
                val ep = LocalSocks(vpn.port, "", "")
                val transport = SiteRouteTransport(ep, ep, { protected.incrementAndGet(); true },
                    resolve = { listOf(InetAddress.getByName("127.0.0.1")) }, publicAddress = { true })
                AdaptiveSiteProxy(transport, { network.get() }, loadCache = { cached(direct.port) },
                    forceVpn = { forced.get() }, localDpi = { true }, diagnostics = diagnostics,
                    routeAllowed = { _, _ -> true }).use { proxy ->
                    val original = hello()
                    assertEquals("direct", request(proxy.endpoint, direct.port, original))
                    network.set("other-network"); forced.set(true)
                    assertEquals("vpn", request(proxy.endpoint, direct.port, original))
                    network.set("qa"); forced.set(false)
                    assertEquals("direct", request(proxy.endpoint, direct.port, original))
                    val snapshot = diagnostics.render(false, false, true)
                    assertTrue(snapshot, snapshot.contains("example.test:${direct.port} [current network]"))
                    assertTrue(snapshot, snapshot.contains("example.test:${direct.port} [previous network]"))
                    assertEquals(snapshot, 2, snapshot.lines().count { it.contains("TLS_RECORD_SNI: connections=1,") })
                    assertEquals(2, protected.get()); assertEquals(2, directBodies.size); assertEquals(1, vpnBodies.size)
                    directBodies.forEach { assertArrayEquals(LocalDpi.transform(original, SitePath.TLS_RECORD_SNI), it) }
                    assertArrayEquals(original, vpnBodies.single())
                }
                assertTrue(vpn.errors.toString(), vpn.errors.isEmpty())
            }
            assertTrue(direct.errors.toString(), direct.errors.isEmpty())
        }
    }
    @Test fun strictVpnModeNeverRunsMaintenanceOnHistoricalAutoCache() {
        val cacheLoads = AtomicInteger(); val resolves = AtomicInteger(); val ep = LocalSocks(9, "", "")
        val old = cached(443, 1_000_000L)
        AdaptiveSiteProxy(SiteRouteTransport(ep, ep, { fail("unexpected DIRECT protect"); false }, resolve = {
            resolves.incrementAndGet(); emptyList()
        }), { "qa" }, loadCache = { cacheLoads.incrementAndGet(); old }, automatic = false, localDpi = { true },
            now = { 1_000_000L + SiteRoutingPolicy.WEEK_MS + 1 }).use { proxy ->
            proxy.maintainRoutes()
            assertEquals("strict mode must not even revive historical auto cache", 0, cacheLoads.get())
            assertEquals(0, resolves.get())
        }
    }
}
