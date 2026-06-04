package com.smarttools.netguard.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client end of the Telemost room-striping mux — the opt-in alternative to
 * [SocksRoundRobinLb]. Where the round-robin LB pins each whole connection to
 * ONE room (so a single big download is capped at one room's ~1.25 Mbps),
 * StripeMux splits every connection's bytes across ALL rooms at once and the
 * Go stripe-server reassembles them, so one heavy transfer gets the aggregate.
 *
 * Topology: StripeMux listens on [listenPort] (tun2socks talks SOCKS5 to it).
 * It holds N "pipes", one per relay upstream; each pipe is a SOCKS5 CONNECT
 * through that relay to the exit's stripe-server (127.0.0.1:[stripePort] from
 * the exit's point of view). On top of the pipes runs the mux protocol
 * (StripeProtocol): each accepted SOCKS5 connection becomes a flow whose bytes
 * are striped across the pipes and window-flow-controlled.
 *
 * This is purely additive: it does not replace or modify SocksRoundRobinLb,
 * the relays, or the agent — TelemostRelayManager chooses one or the other.
 */
class StripeMux(
    private val listenPort: Int,
    private val upstreams: List<InetSocketAddress>,
    private val stripeHost: String,
    private val stripePort: Int,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "StripeMux"
        // How long openPipe waits for the server's HELLO echo before declaring
        // a pipe a black hole and dropping it. Generous for slow mobile carrier
        // round-trips (HELLO out → carrier → creator → stripe-server → echo back).
        private const val HELLO_ACK_TIMEOUT_MS = 8_000
        // The watchdog reopens dead pipes so a churny mobile carrier (librelay
        // rooms restart, idle pipes get dropped) can't drain the pool to zero
        // and black-hole all traffic. Without it striping works for ~60s then
        // dies as pipes drop one by one and are never replaced.
        private const val PIPE_WATCHDOG_INTERVAL_MS = 2_500L
        // Keepalive cadence in watchdog ticks. Every ~15s an idle pipe gets a
        // no-op ACK(flow 0) so the carrier/creator doesn't drop it on an idle
        // timeout - the main cause of pipes dying ~once a minute and dragging
        // aggregate speed below the rooms×1.25 Mbps ceiling.
        private const val KEEPALIVE_EVERY_TICKS = 6
        // After an idx fails to reopen, skip it for this many watchdog ticks so a
        // permanently-dead room (stale profile link with no creator) isn't
        // hammered with an 8s HELLO-ack wait every tick.
        private const val PIPE_REOPEN_BACKOFF_TICKS = 8
        private val KEEPALIVE_FRAME = StripeFrame(StripeProtocol.ACK, 0, 0, ByteArray(0)).encode()
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var watchdogJob: Job? = null
    // idx -> earliest watchdog tick at which a failed-reopen pipe may be retried.
    private val pipeReopenBackoff = HashMap<Int, Int>()
    private val pipes = ArrayList<Pipe>()
    private val pipeCursor = AtomicInteger(0)
    private val flows = ConcurrentHashMap<Int, StripeFlow>()
    private val flowSeq = AtomicInteger(1)
    private val sessionId = ByteArray(16).also { SecureRandom().nextBytes(it) }

    @Volatile private var scope: CoroutineScope? = null

    /** One physical room pipe: a raw byte tunnel to the stripe-server. */
    private class Pipe(
        val idx: Int,
        val socket: Socket,
        val din: DataInputStream,
        val out: OutputStream
    ) {
        val writeLock = Any()
        @Volatile var dead = false
    }

    /**
     * Opens all pipes, starts their reader loops, then binds the local SOCKS5
     * listener. Returns true once at least one pipe is up and the listener is
     * bound. A failed pipe is skipped, mirroring the relay manager's tolerance.
     */
    suspend fun start(scope: CoroutineScope): Boolean {
        if (upstreams.isEmpty()) {
            onLog("StripeMux: no upstreams")
            return false
        }
        this.scope = scope
        // Open all pipes CONCURRENTLY. Each openPipe is a blocking SOCKS5
        // CONNECT + HELLO-ack wait through a room's carrier tunnel to the
        // stripe-server; doing them serially stacked 6x the (slow) carrier
        // round-trip. Use coroutineScope (NOT runBlocking — start() runs on a
        // non-blocking coroutine dispatcher where runBlocking throws
        // "Cannot block on non-blocking thread"). A failed pipe is skipped.
        val opened = coroutineScope {
            upstreams.mapIndexed { i, up ->
                async(Dispatchers.IO) {
                    try {
                        openPipe(i, up)
                    } catch (e: Exception) {
                        onLog("StripeMux pipe #${i + 1} failed: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }
        for (p in opened) {
            if (p != null) {
                pipes.add(p)
                scope.launch(Dispatchers.IO) { pipeReader(p) }
            }
        }
        if (pipes.isEmpty()) {
            onLog("StripeMux: all pipes failed")
            return false
        }
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 128)
            serverSocket = ss
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop() }
            startPipeWatchdog(scope)
            onLog("StripeMux up: ${pipes.size}/${upstreams.size} pipes, listening on :$listenPort")
            Log.i(TAG, "up: ${pipes.size}/${upstreams.size} pipes confirmed, listening on :$listenPort")
            true
        } catch (e: Exception) {
            onLog("StripeMux bind failed: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        watchdogJob?.cancel()
        watchdogJob = null
        acceptJob?.cancel()
        acceptJob = null
        flows.values.toList().forEach { it.close() }
        flows.clear()
        synchronized(pipes) {
            pipes.forEach { try { it.socket.close() } catch (_: Exception) {} }
            pipes.clear()
        }
    }

    /** Connects one pipe: SOCKS5 no-auth handshake + CONNECT, then HELLO. */
    private fun openPipe(idx: Int, upstream: InetSocketAddress): Pipe {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(upstream, 8_000)
            // Bound the WHOLE handshake (SOCKS5 reply + HELLO ack) with a read
            // timeout. A dead room's librelay may accept the local TCP connect
            // but never reply (its carrier tunnel is down), which would
            // otherwise block this pipe's coroutine forever and stall awaitAll.
            s.soTimeout = HELLO_ACK_TIMEOUT_MS
            val out = s.getOutputStream()
            val din = DataInputStream(s.getInputStream())
            socks5ClientConnect(din, out, stripeHost, stripePort)
            // HELLO binds this pipe to our session on the server.
            val payload = ByteArray(17)
            System.arraycopy(sessionId, 0, payload, 0, 16)
            payload[16] = idx.toByte()
            out.write(StripeFrame(StripeProtocol.HELLO, 0, 0, payload).encode())
            out.flush()
            // Wait for the server's HELLO echo: proof this pipe actually
            // traversed the carrier to the stripe-server. librelay acks the SOCKS
            // CONNECT locally before the tunnel delivers it, so a black-hole room
            // would otherwise look "open" and we'd stripe (and lose) data into
            // it, stalling every flow. Pipes that don't confirm in time are
            // dropped (read timeout was armed right after connect, above).
            val ack = StripeFrame.read(din)
            if (ack.type != StripeProtocol.HELLO) {
                throw IOException("pipe #${idx + 1}: expected HELLO ack, got type=${ack.type}")
            }
            s.soTimeout = 0 // restore blocking reads for the pipeReader loop
            onLog("StripeMux pipe #${idx + 1} confirmed end-to-end")
            Log.i(TAG, "pipe #${idx + 1} confirmed end-to-end (upstream $upstream)")
            return Pipe(idx, s, din, out)
        } catch (e: Exception) {
            // Close the socket on ANY failure (SOCKS reject / HELLO-ack timeout /
            // wrong ack). The watchdog reopens dead idxs repeatedly, so a leaked
            // socket per failed attempt would exhaust fds over a long session.
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    /** Performs a SOCKS5 client CONNECT to host:port over an open socket. */
    private fun socks5ClientConnect(din: DataInputStream, out: OutputStream, host: String, port: Int) {
        // Greeting: VER=5, 1 method, NO-AUTH.
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val ver = din.readUnsignedByte()
        val method = din.readUnsignedByte()
        if (ver != 0x05 || method != 0x00) {
            throw IOException("SOCKS5 method rejected: ver=$ver method=$method")
        }
        // CONNECT request. Use a domain ATYP to keep it simple/universal.
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val req = ByteArray(4 + 1 + hostBytes.size + 2)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = (port ushr 8).toByte()
        req[6 + hostBytes.size] = port.toByte()
        out.write(req)
        out.flush()
        // Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT — consume per ATYP.
        if (din.readUnsignedByte() != 0x05) throw IOException("SOCKS5 reply bad version")
        val rep = din.readUnsignedByte()
        if (rep != 0x00) throw IOException("SOCKS5 CONNECT failed: rep=$rep")
        din.readUnsignedByte() // RSV
        when (din.readUnsignedByte()) {
            0x01 -> din.skipBytes(4)
            0x03 -> din.skipBytes(din.readUnsignedByte())
            0x04 -> din.skipBytes(16)
            else -> throw IOException("SOCKS5 reply bad ATYP")
        }
        din.skipBytes(2) // BND.PORT
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (ss.isClosed) return
                // Transient accept error (e.g. EMFILE under fd pressure) must NOT
                // kill the listener — that would silently stop accepting every
                // future flow. Log, pause briefly, and keep listening.
                Log.w(TAG, "accept failed (continuing): ${e.message}")
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                continue
            }
            scope?.launch(Dispatchers.IO) { handleClient(client) }
        }
    }

    /**
     * Handles one tun2socks SOCKS5 connection. TCP CONNECT becomes a striped/
     * pinned flow through the mux. UDP ASSOCIATE (DNS, QUIC — tun2socks runs
     * with --enable-udprelay) is transparently spliced to a librelay upstream,
     * exactly like the round-robin LB, so UDP keeps working (this is what was
     * breaking Telegram: we used to reject everything but CONNECT).
     */
    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        try {
            val din = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()
            if (din.readUnsignedByte() != 0x05) { client.close(); return }
            val nMethods = din.readUnsignedByte()
            din.skipBytes(nMethods)
            out.write(byteArrayOf(0x05, 0x00)); out.flush() // NO-AUTH
            // Request — capture raw bytes so a non-CONNECT can be replayed.
            if (din.readUnsignedByte() != 0x05) { client.close(); return }
            val cmd = din.readUnsignedByte()
            din.readUnsignedByte() // RSV
            val atyp = din.readUnsignedByte()
            val addrField: ByteArray
            val host: String
            when (atyp) {
                0x01 -> {
                    val a = ByteArray(4); din.readFully(a); addrField = a
                    host = "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}.${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}"
                }
                0x03 -> {
                    val len = din.readUnsignedByte()
                    val a = ByteArray(len); din.readFully(a)
                    addrField = byteArrayOf(len.toByte()) + a
                    host = String(a, Charsets.US_ASCII)
                }
                0x04 -> {
                    val a = ByteArray(16); din.readFully(a); addrField = a
                    host = "[" + InetAddress.getByAddress(a).hostAddress + "]"
                }
                else -> { client.close(); return }
            }
            val p1 = din.readUnsignedByte(); val p2 = din.readUnsignedByte()
            val port = (p1 shl 8) or p2

            when (cmd) {
                0x01 -> { // CONNECT -> striped flow
                    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
                    val id = flowSeq.getAndIncrement()
                    val flow = StripeFlow(id, client, this)
                    flows[id] = flow
                    if (send(StripeFrame(StripeProtocol.OPEN, id, 0, "$host:$port".toByteArray(Charsets.US_ASCII))) < 0) {
                        flows.remove(id); client.close(); return
                    }
                    flow.run()
                }
                0x03 -> { // UDP ASSOCIATE -> transparent splice to a librelay upstream
                    val rawReq = byteArrayOf(0x05, cmd.toByte(), 0x00, atyp.toByte()) +
                        addrField + byteArrayOf(p1.toByte(), p2.toByte())
                    spliceToRelay(client, din, out, rawReq)
                }
                else -> {
                    out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
                    client.close()
                }
            }
        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Transparently relays a (UDP-associate) SOCKS5 connection to a librelay
     * upstream: re-handshake with the relay, replay the request, forward the
     * relay's reply, then pump both ways. The actual UDP datagrams flow
     * directly between tun2socks and librelay's UDP bind port (from the reply),
     * so we only carry the control connection.
     */
    private fun spliceToRelay(client: Socket, clientIn: DataInputStream, clientOut: OutputStream, rawReq: ByteArray) {
        val up = upstreams[(pipeCursor.getAndIncrement() and Int.MAX_VALUE) % upstreams.size]
        val us = Socket()
        try {
            us.tcpNoDelay = true
            us.connect(up, 8_000)
            val uout = us.getOutputStream()
            val uin = DataInputStream(us.getInputStream())
            uout.write(byteArrayOf(0x05, 0x01, 0x00)); uout.flush()
            if (uin.readUnsignedByte() != 0x05 || uin.readUnsignedByte() != 0x00) {
                us.close(); client.close(); return
            }
            uout.write(rawReq); uout.flush()
            // Relay reply: VER REP RSV ATYP BND.ADDR BND.PORT — read + forward.
            val head = ByteArray(4); uin.readFully(head)
            val addrLen = when (head[3].toInt() and 0xFF) {
                0x01 -> 4; 0x04 -> 16; 0x03 -> uin.readUnsignedByte().let { it }
                else -> { us.close(); client.close(); return }
            }
            val reply: ByteArray
            if ((head[3].toInt() and 0xFF) == 0x03) {
                val rest = ByteArray(addrLen + 2); uin.readFully(rest)
                reply = head + byteArrayOf(addrLen.toByte()) + rest
            } else {
                val rest = ByteArray(addrLen + 2); uin.readFully(rest)
                reply = head + rest
            }
            clientOut.write(reply); clientOut.flush()
            // Pump the control connection both ways until either side closes.
            val t = Thread({ pump(clientIn, uout) }, "stripe-udp-c2u").apply { isDaemon = true; start() }
            pump(uin, clientOut)
            t.interrupt()
        } catch (e: Exception) {
            // fall through to close
        } finally {
            try { us.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pump(src: java.io.InputStream, dst: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Writes a frame to every live pipe. Used for tiny control frames (Acks)
     * so the fastest surviving pipe delivers them and one slow/dead pipe can't
     * delay the peer's window update. Duplicates are harmless (cumulative).
     */
    fun broadcast(frame: StripeFrame) {
        val snapshot: List<Pipe> = synchronized(pipes) { ArrayList(pipes) }
        if (snapshot.isEmpty()) return
        val wire = frame.encode()
        for (p in snapshot) {
            if (p.dead) continue
            try {
                synchronized(p.writeLock) {
                    p.out.write(wire)
                    p.out.flush()
                }
            } catch (e: Exception) {
                p.dead = true
            }
        }
    }

    /**
     * Stripes one frame across the live pipes round-robin. Returns the idx of
     * the pipe that carried it, or -1 if none could — the caller records that
     * on the chunk so a pipe death can resend exactly its chunks.
     */
    fun send(frame: StripeFrame): Int {
        val snapshot: List<Pipe> = synchronized(pipes) { ArrayList(pipes) }
        val n = snapshot.size
        if (n == 0) return -1
        val wire = frame.encode()
        val start = (pipeCursor.getAndIncrement() and Int.MAX_VALUE) % n
        for (i in 0 until n) {
            val p = snapshot[(start + i) % n]
            if (p.dead) continue
            try {
                synchronized(p.writeLock) {
                    p.out.write(wire)
                    p.out.flush()
                }
                return p.idx
            } catch (e: Exception) {
                p.dead = true
            }
        }
        return -1
    }

    /**
     * Writes a frame to the flow's pinned home pipe so a small flow rides ONE
     * reliable room (no cross-pipe reorder fragility — what was breaking TG).
     * Reassigns the home pipe if it died. Returns the chosen pipe idx, or -1.
     */
    fun sendPinned(homeIdx: Int, frame: StripeFrame): Int {
        val snapshot: List<Pipe> = synchronized(pipes) { ArrayList(pipes) }
        if (snapshot.isEmpty()) return -1
        var home = snapshot.firstOrNull { it.idx == homeIdx && !it.dead }
        if (home == null) {
            val start = (pipeCursor.getAndIncrement() and Int.MAX_VALUE) % snapshot.size
            for (i in snapshot.indices) {
                val p = snapshot[(start + i) % snapshot.size]
                if (!p.dead) { home = p; break }
            }
        }
        if (home == null) return -1
        val wire = frame.encode()
        return try {
            synchronized(home.writeLock) {
                home.out.write(wire)
                home.out.flush()
            }
            home.idx
        } catch (e: Exception) {
            home.dead = true
            -1
        }
    }

    /** Tells every flow to resend the c2s chunks that rode the dead pipe. */
    private fun onPipeDead(deadIdx: Int) {
        for (flow in flows.values) flow.onPipeDead(deadIdx)
    }

    /**
     * Keeps the pipe pool replenished. Mobile carriers churn rooms (librelay
     * restarts, idle pipes get dropped), and a dead pipe is never reused — so
     * without this the pool drains to zero in ~a minute and every flow
     * black-holes. Each tick: prune dead pipes, then reopen any upstream idx
     * that has no live pipe (the relay's SOCKS port is stable across restarts,
     * so the same upstream address works once the room is back). After adding
     * pipes, nudge flows to resend chunks that got stuck (pid == -1) when no
     * pipe was live, so an in-flight transfer recovers instead of stalling.
     */
    private fun startPipeWatchdog(scope: CoroutineScope) {
        watchdogJob = scope.launch(Dispatchers.IO) {
            var tick = 0
            while (true) {
                delay(PIPE_WATCHDOG_INTERVAL_MS)
                tick++
                // Keepalive: every ~15s poke each live pipe with a no-op
                // ACK(flow 0) (server ignores it) so an idle pipe's carrier/SOCKS
                // connection isn't torn down by an idle timeout. A write failure
                // flags the pipe dead so the reopen pass below replaces it.
                if (tick % KEEPALIVE_EVERY_TICKS == 0) {
                    val live = synchronized(pipes) { ArrayList(pipes) }
                    for (p in live) {
                        if (p.dead) continue
                        try {
                            synchronized(p.writeLock) { p.out.write(KEEPALIVE_FRAME); p.out.flush() }
                        } catch (_: Exception) { p.dead = true }
                    }
                }
                // Prune dead pipes (close + drop from pool).
                synchronized(pipes) {
                    val dead = pipes.filter { it.dead }
                    pipes.removeAll(dead)
                    dead.forEach { try { it.socket.close() } catch (_: Exception) {} }
                }
                val liveIdxs = synchronized(pipes) { pipes.filter { !it.dead }.map { it.idx }.toSet() }
                // Skip idxs that are backed off after a recent failed reopen.
                val toReopen = upstreams.indices.filter { it !in liveIdxs && (pipeReopenBackoff[it] ?: 0) <= tick }
                if (toReopen.isEmpty()) continue
                val results = coroutineScope {
                    toReopen.map { idx ->
                        async(Dispatchers.IO) {
                            val p = try { openPipe(idx, upstreams[idx]) } catch (_: Exception) { null }
                            idx to p
                        }
                    }.awaitAll()
                }
                var added = 0
                for ((idx, p) in results) {
                    if (p == null) {
                        pipeReopenBackoff[idx] = tick + PIPE_REOPEN_BACKOFF_TICKS
                        continue
                    }
                    pipeReopenBackoff.remove(idx)
                    synchronized(pipes) { pipes.add(p) }
                    scope.launch(Dispatchers.IO) { pipeReader(p) }
                    added++
                }
                if (added > 0) {
                    val total = synchronized(pipes) { pipes.count { !it.dead } }
                    Log.i(TAG, "watchdog reopened $added pipe(s); pool now $total/${upstreams.size}")
                    // Flush chunks that failed to send (pid == -1) over the
                    // freshly reopened pipes so a mid-flight transfer recovers.
                    onPipeDead(-1)
                }
            }
        }
    }

    private fun pipeReader(p: Pipe) {
        try {
            while (true) {
                val f = StripeFrame.read(p.din)
                val flow = flows[f.flowId] ?: continue
                flow.onFrame(f)
            }
        } catch (_: Exception) {
            // pipe EOF / error: the room died. Mark it dead and have every
            // flow resend the c2s chunks it had on this pipe over a live one.
        } finally {
            p.dead = true
            onPipeDead(p.idx)
        }
    }

    fun dropFlow(id: Int) { flows.remove(id) }
}
