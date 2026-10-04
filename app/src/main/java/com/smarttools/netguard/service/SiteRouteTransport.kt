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
        return handshake(channel.socket(), endpoint, destination, command)
    }
    fun handshake(socket: Socket, endpoint: LocalSocks, destination: SocksDestination, command: Int = 1): SocksDestination {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
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

internal data class DirectNetwork(val resolve: (String) -> List<InetAddress>, val prepare: (Socket) -> Boolean)

internal class SiteRouteTransport(
    val normal: LocalSocks,
    val vpn: LocalSocks,
    private val protect: (Socket) -> Boolean,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val publicAddress: (InetAddress) -> Boolean = { !AddressValidator.isPrivateOrReserved(it) },
    private val network: (() -> DirectNetwork)? = null,
    private val tlsContext: () -> javax.net.ssl.SSLContext = { TlsProbeStream.freshContext() }
) : AutoCloseable {
    private val scopes = ConcurrentHashMap.newKeySet<SocketScope>()
    private val closed = AtomicBoolean(false)
    private val deadlines = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "site-deadlines").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    private val dns = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { r -> Thread(r, "site-dns").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }

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

    fun connect(destination: SocksDestination, path: SitePath, scope: SocketScope, stage: (String) -> Unit = {}): SocketChannel {
        if (closed.get() || scope.closed.get()) throw IOException("Connection cancelled")
        if (path == SitePath.VPN) return viaProxy(destination, scope).first
        stage("DNS")
        val selected = network?.invoke() ?: DirectNetwork(resolve, protect)
        if (scope.closed.get()) throw IOException("Connection cancelled")
        val lookup = dns.submit<List<InetAddress>> { selected.resolve(destination.host).filter(publicAddress) }
        val addresses = try { lookup.get(1800, TimeUnit.MILLISECONDS) } finally { lookup.cancel(true); dns.purge() }
        stage("TCP")
        if (addresses.isEmpty()) throw IOException("No public direct address")
        val deadline = System.nanoTime() + 4_000_000_000
        for (address in addresses.take(4)) {
            if (scope.closed.get()) throw IOException("Connection cancelled")
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            if (remaining <= 0) break
            val channel = channel(scope)
            try {
                if (!selected.prepare(channel.socket())) throw IOException("Direct socket could not bypass VPN")
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
        if (closed.get() || scope.closed.get()) { scope.close(); return SiteMeasurement(outcome = "CANCELLED") }
        val expiry = try { deadlines.schedule({ scope.close() }, 8000, TimeUnit.MILLISECONDS) }
            catch (_: RejectedExecutionException) { scope.close(); return SiteMeasurement() }
        val start = System.nanoTime()
        var stage = "TCP"
        var received = 0
        var status = 0
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        return try {
            val raw = connect(destination, path, scope) { stage = it }.socket()
            raw.soTimeout = 4000
            stage = "TLS"
            val tls = if (origin.tls) TlsProbeStream(raw, origin.host, origin.port, path, tlsContext()).also { it.handshake() } else null
            // Separate safe request: no cookies, authorization, user path or request replay.
            val request = ("GET / HTTP/1.1\r\nHost: ${origin.host}:${origin.port}\r\nUser-Agent: NetGuard-Route\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            stage = "REQUEST"
            if (tls != null) tls.send(request) else raw.getOutputStream().write(request)
            val body = HttpBodyProbe.read(tls ?: raw.getInputStream(), { stage = it }, { code, bytes -> status = code; received = bytes })
            val verified = origin.tls && body.status in 200..299 && body.complete && body.bytes >= HttpBodyProbe.MIN_VERIFIED
            SiteMeasurement(if (verified) 2 else 1, elapsed(), "BODY",
                if (verified) "VERIFIED" else if (body.complete) "SMALL_OR_STATUS" else "LIMITED",
                body.bytes, body.status)
        } catch (e: Exception) {
            val causes = generateSequence<Throwable>(e) { it.cause }.take(12).toList()
            val reason = when {
                scope.closed.get() || e is java.net.SocketTimeoutException || e is java.util.concurrent.TimeoutException -> "TIMEOUT"
                causes.any { it is java.security.cert.CertificateException || it is java.security.cert.CertPathValidatorException || it is javax.net.ssl.SSLPeerUnverifiedException } -> "CERTIFICATE"
                e is java.net.SocketException && e.message.orEmpty().contains("reset", true) -> "RESET"
                e is javax.net.ssl.SSLException -> "TLS_ERROR"
                e is java.io.EOFException -> "TRUNCATED"
                e is RejectedExecutionException -> "BUSY"
                else -> "FAILED"
            }
            SiteMeasurement(0, elapsed(), stage, reason, received, status)
        } finally { expiry.cancel(false); scope.close() }
    }

    override fun close() {
        closed.set(true)
        scopes.toList().forEach { it.close() }
        deadlines.shutdownNow(); dns.shutdownNow()
    }
}

internal class SiteRouteChooser(private val policy: SiteRoutingPolicy, private val transport: SiteRouteTransport,
    private val localDpi: () -> Boolean = { false },
    private val diagnostics: RouteDiagnostics? = null, @Volatile private var token: RouteDiagnostics.Token? = null,
    private val current: () -> Boolean = { true }) : AutoCloseable {
    private val pool = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { r -> Thread(r, "site-probe").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val coordinators = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { r -> Thread(r, "site-compare").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val inflight = ConcurrentHashMap<String, CompletableFuture<SiteRouteRecord>>()
    private val ownedScopes = ConcurrentHashMap.newKeySet<SocketScope>()
    private val closed = AtomicBoolean(false)

    fun updateDiagnosticToken(value: RouteDiagnostics.Token?) { token = value }

    /** Returns immediately, including under probe saturation. Never called with get() by traffic handlers. */
    fun refresh(origin: SiteOrigin, target: String): CompletableFuture<SiteRouteRecord> {
        if (!policy.needsCheck(origin)) return CompletableFuture.completedFuture(policy.forConnection(origin, target))
        val token = this.token // immutable provenance for this admitted check
        val own = CompletableFuture<SiteRouteRecord>()
        val existing = inflight.putIfAbsent(origin.key, own)
        if (existing != null) return existing
        try {
            if (closed.get()) throw RejectedExecutionException("Site checks stopped")
            coordinators.execute {
                try {
                    if (closed.get()) throw IOException("Site checks stopped")
                    if (policy.needsCheck(origin)) own.complete(compare(origin, target, token))
                    else own.complete(policy.forConnection(origin, target))
                } catch (e: Exception) {
                    token?.let { diagnostics?.phase(it, origin, if (e.message == "Site check rate limit") "RATE_LIMIT" else "DEFERRED") }
                    own.completeExceptionally(e)
                }
                finally { inflight.remove(origin.key, own) }
            }
        } catch (e: RejectedExecutionException) {
            inflight.remove(origin.key, own)
            // No cached failure: a later visit can schedule the skipped check.
            token?.let { diagnostics?.phase(it, origin, "DEFERRED") }
            own.completeExceptionally(e)
        }
        return own
    }

    private fun compare(origin: SiteOrigin, target: String, token: RouteDiagnostics.Token?): SiteRouteRecord {
        if (!baselineBudget()) throw IOException("Site check rate limit")
        token?.let { diagnostics?.phase(it, origin, "CHECKING") }
        val paths = listOf(SitePath.DIRECT, SitePath.VPN)
        val scopes = paths.associateWith { probeScope() }
        val results = paths.associateWith { path ->
            val future = CompletableFuture<SiteMeasurement>()
            try { pool.execute {
                future.complete(runCatching { transport.measure(origin, SocksDestination(target, origin.port), path, scopes.getValue(path)) }
                    .getOrDefault(SiteMeasurement()))
            } } catch (_: RejectedExecutionException) { future.complete(SiteMeasurement(outcome = "BUSY")) }
            future
        }
        try {
            // Wait for both bounded measurements. A slower success is not a failure.
            val until = System.nanoTime() + 8_200_000_000L
            while (!results.values.all { it.isDone } && !closed.get() && System.nanoTime() < until) Thread.sleep(20)
            if (closed.get() || !current()) throw IOException("Site checks changed")
            if (!results.values.all { it.isDone }) throw IOException("Site checks not completed")
            val direct = results.getValue(SitePath.DIRECT).getNow(SiteMeasurement())
            val vpn = results.getValue(SitePath.VPN).getNow(SiteMeasurement())
            if (listOf(direct, vpn).any { it.outcome in setOf("UNKNOWN", "BUSY", "CANCELLED") }) throw IOException("Site checks not admitted")
            token?.let { diagnostics?.sample(it, origin, "DIRECT", direct); diagnostics?.sample(it, origin, "VPN", vpn) }
            var dpiPath: SitePath? = null
            var dpi: SiteMeasurement? = null
            // Do not infer censorship from an access-denied page, short answer or busy worker.
            val catalogReason = when {
                !localDpi() -> "DPI_DISABLED"
                !origin.tls -> "NOT_TLS"
                direct.quality == 2 -> "DIRECT_OK"
                direct.quality != 0 -> "DIRECT_LIMITED"
                direct.outcome !in setOf("TIMEOUT", "RESET", "TLS_ERROR", "TRUNCATED", "FAILED") -> "NOT_ELIGIBLE"
                direct.stage !in setOf("TLS", "HEADERS", "BODY") -> "EARLY_FAILURE"
                vpn.quality != 2 -> "VPN_NOT_VERIFIED"
                !dpiBudget() -> "RATE_LIMIT"
                else -> "ATTEMPTED"
            }
            token?.let { diagnostics?.catalog(it, origin, catalogReason) }
            if (catalogReason == "ATTEMPTED") {
                for (path in LocalDpi.paths) {
                    if (closed.get() || !current() || !localDpi()) break
                    val one = sample(origin, target, path)
                    token?.let { diagnostics?.sample(it, origin, "$path:1", one) }
                    if (one.quality != 2) continue
                    Thread.sleep(500)
                    if (closed.get() || !current() || !localDpi()) break
                    val two = sample(origin, target, path)
                    token?.let { diagnostics?.sample(it, origin, "$path:2", two) }
                    if (two.quality == 2) { dpiPath = path; dpi = two.copy(outcome = "VERIFIED_TWICE"); break }
                }
            }
            if (closed.get() || !current()) throw IOException("Site checks changed")
            return policy.record(origin, target, direct, vpn, dpiPath, dpi).also { record ->
                token?.let { diagnostics?.decision(it, record, false) }
            }
        } finally {
            scopes.values.forEach { it.close(); ownedScopes.remove(it) }
            results.values.forEach { it.cancel(false) }
        }
    }
    private fun probeScope(): SocketScope = transport.scope().also {
        ownedScopes.add(it)
        if (closed.get()) { it.close(); ownedScopes.remove(it); throw IOException("Site checks stopped") }
    }
    private fun sample(origin: SiteOrigin, target: String, path: SitePath): SiteMeasurement {
        val scope = probeScope()
        return try { transport.measure(origin, SocksDestination(target, origin.port), path, scope) }
        finally { scope.close(); ownedScopes.remove(scope) }
    }
    private var lastDpi = 0L
    private var baselineWindow = 0L
    private var baselineCount = 0
    @Synchronized private fun baselineBudget(): Boolean {
        val time = System.nanoTime()
        if (baselineWindow == 0L || time - baselineWindow >= 60_000_000_000L) {
            baselineWindow = time; baselineCount = 0
        }
        if (baselineCount >= 8) return false
        baselineCount++
        return true
    }
    @Synchronized private fun dpiBudget(): Boolean {
        val time = System.nanoTime()
        if (lastDpi != 0L && time - lastDpi < 60_000_000_000L) return false
        lastDpi = time
        return true
    }
    override fun close() {
        closed.set(true)
        ownedScopes.toList().forEach { it.close() }; ownedScopes.clear()
        inflight.values.forEach { it.cancel(false) }; inflight.clear()
        coordinators.shutdownNow(); pool.shutdownNow()
    }
}
