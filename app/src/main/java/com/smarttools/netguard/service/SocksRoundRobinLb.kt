package com.smarttools.netguard.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Tiny TCP forwarder that listens on 127.0.0.1:listenPort, accepts incoming
 * connections (from tun2socks; they speak SOCKS5 but the LB is byte-transparent
 * and doesn't parse the protocol) and forwards each new connection to the next
 * upstream relay in a round-robin schedule.
 *
 * Connections within a TCP stream stay pinned to their upstream — we never
 * mid-stream re-pick, that would corrupt SOCKS5 framing. Distribution happens
 * only at connect time, so a single big download still occupies one upstream
 * (i.e. capped at one stream's bandwidth). Multi-channel aggregation pays off
 * when the workload has many concurrent TCP connections — typical browsing,
 * messaging, video streaming with parallel segment fetches.
 *
 * Auth: all upstream relays must be configured with the same SOCKS5
 * username/password so [TelemostRelayManager] can hand a single set of
 * credentials to tun2socks. The LB does not inspect or rewrite them.
 */
class SocksRoundRobinLb(
    private val listenPort: Int,
    private val upstreams: List<InetSocketAddress>
) {
    companion object {
        private const val TAG = "SocksLB"
        // tun2socks bursts can dwarf the default buffer; bigger window reduces
        // splice round-trips on the per-connection forwarder threads.
        private const val BUFFER_SIZE = 32 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private val cursor = AtomicInteger(0)
    private var acceptJob: Job? = null

    /**
     * Per-upstream cooldown timestamp (epoch ms). 0 means healthy. When an
     * upstream's connect() fails we stamp it as dead-until-now+DEAD_COOLDOWN_MS
     * and skip it during pick; round-robin walks past stale entries until it
     * finds a fresh one or exhausts the ring. This auto-recovers when Yandex
     * SFU recovers without us having to maintain a separate health-checker.
     */
    private val deadUntil: AtomicLongArray = AtomicLongArray(upstreams.size)
    private val DEAD_COOLDOWN_MS = 15_000L
    private val MAX_PICK_ATTEMPTS = upstreams.size.coerceAtMost(8)

    fun start(scope: CoroutineScope): Boolean {
        if (upstreams.isEmpty()) {
            Log.e(TAG, "Refusing to start LB with no upstreams")
            return false
        }
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 128)
            serverSocket = ss
            Log.i(TAG, "LB listening on 127.0.0.1:$listenPort, upstreams=${upstreams.size}")
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(scope) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "LB bind failed: ${e.message}")
            false
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    private fun acceptLoop(scope: CoroutineScope) {
        val ss = serverSocket ?: return
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (ss.isClosed) return
                // A transient accept error (e.g. EMFILE under fd pressure) must
                // NOT kill the listener — that silently stops forwarding every
                // future connection. Log, pause, and keep listening.
                Log.w(TAG, "accept failed (continuing): ${e.message}")
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                continue
            }
            scope.launch(Dispatchers.IO) { handle(client) }
        }
    }

    private fun pickHealthy(): Int {
        val now = System.currentTimeMillis()
        // Walk the rotation up to N times to find a healthy upstream.
        for (attempt in 0 until upstreams.size) {
            val idx = (cursor.getAndIncrement() and Int.MAX_VALUE) % upstreams.size
            if (deadUntil.get(idx) <= now) return idx
        }
        // All marked dead — return the one that recovers soonest. Better than
        // giving up: Yandex SFU recovers, sometimes the cooldown overestimates.
        var bestIdx = 0
        var bestTs = Long.MAX_VALUE
        for (i in 0 until upstreams.size) {
            val t = deadUntil.get(i)
            if (t < bestTs) { bestTs = t; bestIdx = i }
        }
        return bestIdx
    }

    private fun markDead(idx: Int) {
        deadUntil.set(idx, System.currentTimeMillis() + DEAD_COOLDOWN_MS)
    }

    private fun markAlive(idx: Int) {
        deadUntil.set(idx, 0L)
    }

    private fun handle(client: Socket) {
        client.tcpNoDelay = true
        var srv: Socket? = null
        var chosenIdx = -1
        // Try up to MAX_PICK_ATTEMPTS upstreams. Each connect failure marks the
        // upstream dead and we move to the next, so a single bad relay no longer
        // wastes 1/N of inbound connections.
        var lastError: Exception? = null
        for (attempt in 0 until MAX_PICK_ATTEMPTS) {
            val idx = pickHealthy()
            val upstream = upstreams[idx]
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(upstream, 4_000)
                srv = s
                chosenIdx = idx
                markAlive(idx)
                break
            } catch (e: Exception) {
                lastError = e
                Log.d(TAG, "upstream[$idx] connect failed: ${e.message}; cooling down")
                markDead(idx)
            }
        }
        if (srv == null) {
            Log.w(TAG, "all upstreams failed for new conn: ${lastError?.message}")
            try { client.close() } catch (_: Exception) {}
            return
        }
        try {
            val s = srv
            val c = client
            val idx = chosenIdx
            val s2cThread = Thread({ pump(s, c, "s2c#$idx") }, "lb-s2c-$idx")
                .apply { isDaemon = true; start() }
            pump(c, s, "c2s#$idx")
            // The client side ended. Give the server->client pump a brief
            // moment to flush, then the finally closes both sockets (which
            // unblocks s2c). MUST stay short: this LB handles many short-lived
            // connections (a web page / Telegram opens dozens), and a long
            // wait here pins handler threads on teardown and starves new
            // connections — a long cap made browsing crawl. 300ms is the
            // throughput-safe value (was the original); the rare half-closed-
            // slow-response truncation is an acceptable trade.
            try { s2cThread.join(300) } catch (_: Exception) {}
        } finally {
            try { srv.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pump(src: Socket, dst: Socket, label: String) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            val inp = src.getInputStream()
            val out = dst.getOutputStream()
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
            try { dst.shutdownOutput() } catch (_: Exception) {}
        } catch (_: Exception) {
            // closed by other side or write failure — normal end-of-life
        }
    }
}
