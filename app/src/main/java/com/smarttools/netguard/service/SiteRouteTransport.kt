package com.smarttools.netguard.service

import com.smarttools.netguard.util.AddressValidator
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class LocalSocks(val port: Int, val user: String, val password: String)
internal data class SocksDestination(val host: String, val port: Int)

internal object SocksWire {
    fun address(input: DataInputStream): SocksDestination {
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress!!
            4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress!!
            3 -> {
                val n = input.readUnsignedByte()
                if (n == 0) throw IOException("Empty SOCKS host")
                String(ByteArray(n).also { input.readFully(it) }, Charsets.US_ASCII)
            }
            else -> throw IOException("SOCKS address type")
        }
        if (host.any { it <= ' ' || it.code >= 127 }) throw IOException("Invalid SOCKS host")
        return SocksDestination(host, input.readUnsignedShort())
    }
    fun request(host: String, port: Int, command: Int = 1): ByteArray {
        val h = host.toByteArray(Charsets.US_ASCII)
        require(h.size in 1..255 && port in 0..65535)
        return byteArrayOf(5, command.toByte(), 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), port.toByte())
    }
    fun handshake(channel: SocketChannel, endpoint: LocalSocks, destination: SocksDestination, command: Int = 1): SocksDestination {
        val input = DataInputStream(channel.socket().getInputStream())
        val output = channel.socket().getOutputStream()
        val method = if (endpoint.user.isEmpty()) 0 else 2
        output.write(byteArrayOf(5, 1, method.toByte()))
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != method) throw IOException("SOCKS method")
        if (method == 2) {
            val u = endpoint.user.toByteArray(Charsets.UTF_8)
            val p = endpoint.password.toByteArray(Charsets.UTF_8)
            require(u.size in 1..255 && p.size in 1..255)
            output.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS authentication")
        }
        output.write(request(destination.host, destination.port, command))
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0) throw IOException("SOCKS destination")
        return address(input)
    }
    fun reply(port: Int = 0, result: Int = 0): ByteArray =
        byteArrayOf(5, result.toByte(), 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte())
}

internal class SocketScope(private val onClose: (SocketScope) -> Unit = {}) : AutoCloseable {
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val closed = AtomicBoolean(false)
    fun track(socket: Socket) {
        sockets.add(socket)
        if (closed.get()) { socket.close(); throw IOException("Connection cancelled") }
    }
    fun detach(socket: Socket) { sockets.remove(socket) }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
            onClose(this)
        }
    }
}

internal class SiteRouteTransport(
    val normal: LocalSocks,
    val vpn: LocalSocks,
    private val protect: (Socket) -> Boolean,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val publicAddress: (InetAddress) -> Boolean = { !AddressValidator.isPrivateOrReserved(it) }
) : AutoCloseable {
    private val scopes = ConcurrentHashMap.newKeySet<SocketScope>()
    private val closed = AtomicBoolean(false)
    private val deadlines = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "site-deadlines").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    fun scope(): SocketScope = SocketScope { scopes.remove(it) }.also {
        scopes.add(it)
        if (closed.get()) it.close()
    }

    private fun channel(scope: SocketScope): SocketChannel = SocketChannel.open().also {
        scope.track(it.socket())
        it.socket().tcpNoDelay = true
        it.socket().soTimeout = 4000
    }

    fun viaProxy(destination: SocksDestination, scope: SocketScope, endpoint: LocalSocks = vpn, command: Int = 1): Pair<SocketChannel, SocksDestination> {
        val channel = channel(scope)
        channel.socket().connect(InetSocketAddress("127.0.0.1", endpoint.port), 1000)
        return channel to SocksWire.handshake(channel, endpoint, destination, command)
    }

    fun connect(destination: SocksDestination, path: SitePath, scope: SocketScope): SocketChannel {
        if (path == SitePath.VPN) return viaProxy(destination, scope).first
        val addresses = resolve(destination.host).filter(publicAddress)
        if (addresses.isEmpty()) throw IOException("No public direct address")
        val deadline = System.nanoTime() + 4_000_000_000
        for (address in addresses.take(4)) {
            if (scope.closed.get()) throw IOException("Connection cancelled")
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            if (remaining <= 0) break
            val channel = channel(scope)
            try {
                if (!protect(channel.socket())) throw IOException("Direct socket could not bypass VPN")
                channel.socket().connect(InetSocketAddress(address, destination.port), remaining.coerceAtMost(1800))
                return channel
            } catch (_: Exception) {
                scope.detach(channel.socket())
                channel.close()
            }
        }
        throw IOException("Direct route unavailable")
    }

    fun measure(origin: SiteOrigin, destination: SocksDestination, path: SitePath, scope: SocketScope): SiteMeasurement {
        val expiry = try { deadlines.schedule({ scope.close() }, 4500, TimeUnit.MILLISECONDS) }
            catch (_: RejectedExecutionException) { scope.close(); return SiteMeasurement() }
        val start = System.nanoTime()
        return try {
            val raw = connect(destination, path, scope).socket()
            val socket = if (origin.tls) {
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, origin.host, origin.port, true) as SSLSocket).also { tls ->
                    scope.track(tls)
                    tls.soTimeout = 3500
                    tls.sslParameters = tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                        serverNames = listOf(SNIHostName(origin.host))
                    }
                    tls.startHandshake()
                }
            } else raw
            // Only a fresh HEAD for the origin is sent. Never duplicate user
            // requests, cookies, authorization, POST bodies or URL paths.
            socket.getOutputStream().write(("HEAD / HTTP/1.1\r\nHost: ${origin.host}:${origin.port}\r\nUser-Agent: NetGuard-Route\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            val input = socket.getInputStream()
            val line = StringBuilder()
            while (line.length < 4096) {
                val c = input.read()
                if (c < 0) throw IOException("HTTP EOF")
                if (c == 10) break
                line.append(c.toChar())
            }
            if (!line.startsWith("HTTP/1.")) throw IOException("Not HTTP")
            val status = line.toString().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("HTTP status")
            val quality = when (status) { in 200..399 -> 2; 401, 403, 405, 429 -> 1; else -> 0 }
            SiteMeasurement(quality, (System.nanoTime() - start) / 1_000_000)
        } catch (_: Exception) { SiteMeasurement() }
        finally { expiry.cancel(false); scope.close() }
    }

    override fun close() {
        closed.set(true)
        scopes.toList().forEach { it.close() }
        deadlines.shutdownNow()
    }
}

internal class SiteRouteChooser(private val policy: SiteRoutingPolicy, private val transport: SiteRouteTransport) : AutoCloseable {
    private val pool = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { r -> Thread(r, "site-probe").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val coordinators = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { r -> Thread(r, "site-compare").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val inflight = ConcurrentHashMap<String, CompletableFuture<SiteRouteRecord>>()
    private val closed = AtomicBoolean(false)

    /** Returns immediately, including under probe saturation. Never called with get() by traffic handlers. */
    fun refresh(origin: SiteOrigin, target: String): CompletableFuture<SiteRouteRecord> {
        if (!policy.needsCheck(origin)) return CompletableFuture.completedFuture(policy.forConnection(origin, target))
        val own = CompletableFuture<SiteRouteRecord>()
        val existing = inflight.putIfAbsent(origin.key, own)
        if (existing != null) return existing
        try {
            if (closed.get()) throw RejectedExecutionException("Site checks stopped")
            coordinators.execute {
                try {
                    if (closed.get()) throw IOException("Site checks stopped")
                    if (policy.needsCheck(origin)) own.complete(compare(origin, target))
                    else own.complete(policy.forConnection(origin, target))
                } catch (e: Exception) { own.completeExceptionally(e) }
                finally { inflight.remove(origin.key, own) }
            }
        } catch (e: RejectedExecutionException) {
            inflight.remove(origin.key, own)
            // No cached failure: a later visit can schedule the skipped check.
            own.completeExceptionally(e)
        }
        return own
    }

    private fun compare(origin: SiteOrigin, target: String): SiteRouteRecord {
        val scopes = SitePath.entries.associateWith { transport.scope() }
        val results = SitePath.entries.associateWith { path ->
            val future = CompletableFuture<SiteMeasurement>()
            try { pool.execute {
                future.complete(runCatching { transport.measure(origin, SocksDestination(target, origin.port), path, scopes.getValue(path)) }
                    .getOrDefault(SiteMeasurement()))
            } }
            catch (_: Exception) { future.complete(SiteMeasurement()) }
            future
        }
        return try {
            val until = System.nanoTime() + 4_600_000_000L
            var goodAt = 0L
            while (!closed.get() && System.nanoTime() < until) {
                if (results.values.all { it.isDone }) break
                val good = results.values.any { it.getNow(SiteMeasurement()).quality == 2 }
                if (good && goodAt == 0L) goodAt = System.nanoTime()
                if (goodAt != 0L && System.nanoTime() - goodAt > 250_000_000L) break
                Thread.sleep(20)
            }
            if (closed.get()) throw IOException("Site checks stopped")
            policy.record(origin, target, results.getValue(SitePath.DIRECT).getNow(SiteMeasurement()),
                results.getValue(SitePath.VPN).getNow(SiteMeasurement()))
        } finally {
            scopes.values.forEach { it.close() }
            results.values.forEach { it.cancel(false) }
        }
    }
    override fun close() {
        closed.set(true)
        inflight.values.forEach { it.cancel(false) }; inflight.clear()
        coordinators.shutdownNow(); pool.shutdownNow()
    }
}
