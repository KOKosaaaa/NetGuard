package com.smarttools.netguard.service

import com.smarttools.netguard.util.AddressValidator
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated loopback frontend for hev. Core health checks bypass this frontend. */
internal class AdaptiveSiteProxy(
    private val transport: SiteRouteTransport,
    private val contextKey: () -> String,
    private val loadCache: () -> String? = { null },
    private val saveCache: (String) -> Unit = {},
    private val routeAllowed: (SocksDestination, SiteOrigin) -> Boolean = { destination, _ ->
        !AddressValidator.isPrivateOrReserved(destination.host)
    },
    private val now: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val dirty = AtomicBoolean(false)
    private val listener = ServerSocketChannel.open()
    private val relay = NioSiteRelay()
    private val udp = SiteUdpRelay()
    private val workers = ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { r -> Thread(r, "site-handshake").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val maintenance = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "site-cache").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val deadlines = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "site-accept-timeout").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private data class State(val key: String, val policy: SiteRoutingPolicy, val chooser: SiteRouteChooser)
    private var state: State? = null
    val endpoint: LocalSocks
    private val thread: Thread

    init {
        try {
            listener.bind(InetSocketAddress("127.0.0.1", 0), 128)
            endpoint = LocalSocks((listener.localAddress as InetSocketAddress).port, UUID.randomUUID().toString(), UUID.randomUUID().toString())
            thread = Thread(::accept, "site-accept").apply { isDaemon = true; start() }
            maintenance.scheduleWithFixedDelay({
                runCatching { synchronized(this) { if (dirty.getAndSet(false)) state?.let { saveCache(it.policy.export()) } } }
            }, 2, 2, TimeUnit.SECONDS)
            maintenance.scheduleWithFixedDelay({
                runCatching {
                    val s = current()
                    // Failed probes retry on demand, not continuously in the background.
                    s.policy.expired().firstOrNull { it.direct.quality > 0 || it.vpn.quality > 0 }?.let {
                        s.chooser.refresh(it.origin, it.origin.host)
                    }
                }
            }, 60, 60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            running.set(false); listener.close(); workers.shutdownNow(); maintenance.shutdownNow(); deadlines.shutdownNow()
            relay.close(); udp.close(); transport.close(); throw e
        }
    }

    @Synchronized private fun current(): State {
        check(running.get())
        val key = contextKey()
        state?.takeIf { it.key == key }?.let { return it }
        state?.let { runCatching { saveCache(it.policy.export()) }; it.chooser.close() }
        val policy = SiteRoutingPolicy(key, now) { dirty.set(true) }
        runCatching { policy.restore(loadCache()) }
        return State(key, policy, SiteRouteChooser(policy, transport)).also { state = it }
    }

    private fun accept() {
        while (running.get()) {
            val client = try { listener.accept() } catch (_: Exception) {
                if (!running.get() || !listener.isOpen) break
                // A transient accept/descriptor failure must not leave a live
                // VPN with a permanently dead frontend. Existing streams drain
                // independently and free capacity for the next attempt.
                runCatching { Thread.sleep(100) }
                continue
            }
            val scope = transport.scope()
            try {
                scope.track(client.socket())
                // Includes time in the bounded handshake queue; slow clients cannot
                // occupy sockets/workers indefinitely.
                val expiry = deadlines.schedule({ scope.close() }, 14, TimeUnit.SECONDS)
                try { workers.execute { handle(client, scope, expiry) } }
                catch (_: RejectedExecutionException) { expiry.cancel(false); scope.close() }
            } catch (_: Exception) { scope.close(); runCatching { client.close() } }
        }
    }

    private fun authenticate(client: SocketChannel): DataInputStream {
        val input = DataInputStream(client.socket().getInputStream())
        val output = client.socket().getOutputStream()
        if (input.readUnsignedByte() != 5) throw IOException("SOCKS version")
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        if (2.toByte() !in methods) { output.write(byteArrayOf(5, -1)); throw IOException("Authentication required") }
        output.write(byteArrayOf(5, 2))
        if (input.readUnsignedByte() != 1) throw IOException("SOCKS auth version")
        val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        val pass = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        val ok = MessageDigest.isEqual(user, endpoint.user.toByteArray()) && MessageDigest.isEqual(pass, endpoint.password.toByteArray())
        output.write(byteArrayOf(1, if (ok) 0 else 1))
        if (!ok) throw IOException("SOCKS authentication")
        return input
    }

    private fun handle(client: SocketChannel, scope: SocketScope, expiry: ScheduledFuture<*>) {
        var lease: Triple<SiteRoutingPolicy, SiteOrigin, SitePath>? = null
        var recheck: (() -> Unit)? = null
        var association: SiteUdpRelay.Session? = null
        try {
            client.socket().tcpNoDelay = true
            client.socket().soTimeout = 4000
            val input = authenticate(client)
            if (input.readUnsignedByte() != 5) throw IOException("SOCKS request version")
            val command = input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) throw IOException("SOCKS reserved byte")
            val destination = SocksWire.address(input)
            val output = client.socket().getOutputStream()
            if (command == 3) {
                // The upstream UDP endpoint belongs to the app-owned SOCKS server.
                val (server, bound) = transport.viaProxy(SocksDestination("0.0.0.0", 0), scope, transport.normal, 3)
                val session = udp.associate(bound)
                association = session
                output.write(SocksWire.reply(session.port))
                expiry.cancel(false)
                scope.detach(client.socket()); scope.detach(server.socket())
                relay.attach(client, server, control = true) { session.close() }
                association = null
                return
            }
            if (command != 1 || destination.port == 0) { output.write(SocksWire.reply(result = 7)); return }
            // A SOCKS success permits hev to send the already buffered ClientHello.
            // Do not emit any of the user's bytes upstream until the route is fixed.
            output.write(SocksWire.reply())
            val first = if (destination.port == 53 || destination.port == 853) byteArrayOf() else peek(client, input)
            val hello = WebHello.inspect(first)
            val origin = (hello as? WebHello.Result.Site)?.let { SiteOrigin(it.host, destination.port, it.tls) }
            val server = if (origin != null && routeAllowed(destination, origin)) {
                val s = current()
                val record = s.policy.forConnection(origin, destination.host)
                val path = s.policy.pin(record)
                lease = Triple(s.policy, origin, path)
                recheck = { s.chooser.refresh(origin, destination.host); Unit }
                // Probe on separate, bounded workers. Neither a slow HEAD nor
                // a full probe queue may hold the user's first ClientHello/POST.
                s.chooser.refresh(origin, destination.host)
                try { transport.connect(destination, path, scope) }
                catch (e: Exception) { s.policy.connectionFailed(origin, path); recheck?.invoke(); throw e }
            } else transport.viaProxy(destination, scope, transport.normal).first
            val pinned = lease
            val retryCheck = recheck
            expiry.cancel(false)
            scope.detach(client.socket()); scope.detach(server.socket())
            relay.attach(client, server, first) { failed -> pinned?.let {
                // SOCKS can acknowledge CONNECT before its remote dial finishes.
                // A remote close without any response also invalidates the cache.
                if (failed) it.first.connectionFailed(it.second, it.third)
                it.first.release(it.second)
                if (failed) retryCheck?.invoke()
            } }
            lease = null
        } catch (_: Exception) {
            // A failed connection is closed, never replayed on another path. The
            // client can retry; the next attempt rechecks a failed cached route.
        } finally {
            expiry.cancel(false); scope.close(); association?.close()
            lease?.let { it.first.release(it.second) }
        }
    }

    private fun peek(client: SocketChannel, input: DataInputStream): ByteArray {
        val bytes = ByteArrayOutputStream()
        val scratch = ByteArray(4096)
        val until = System.nanoTime() + 1_500_000_000L
        while (bytes.size() < 32768 && System.nanoTime() < until) {
            client.socket().soTimeout = if (bytes.size() == 0) 300 else
                ((until - System.nanoTime()) / 1_000_000).toInt().coerceIn(1, 1200)
            val n = try { input.read(scratch, 0, minOf(scratch.size, 32768 - bytes.size())) }
                catch (_: SocketTimeoutException) { break }
            if (n < 0) break
            bytes.write(scratch, 0, n)
            if (WebHello.inspect(bytes.toByteArray()) != WebHello.Result.More) break
        }
        return bytes.toByteArray()
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { listener.close() }; deadlines.shutdownNow(); maintenance.shutdownNow(); workers.shutdownNow()
        synchronized(this) {
            state?.let { it.chooser.close(); runCatching { saveCache(it.policy.export()) } }
        }
        transport.close(); relay.close(); udp.close()
        if (Thread.currentThread() !== thread) runCatching { thread.join(1000) }
    }
}
