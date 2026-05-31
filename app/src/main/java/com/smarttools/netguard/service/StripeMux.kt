package com.smarttools.netguard.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
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
    fun start(scope: CoroutineScope): Boolean {
        if (upstreams.isEmpty()) {
            onLog("StripeMux: no upstreams")
            return false
        }
        this.scope = scope
        for ((i, up) in upstreams.withIndex()) {
            val p = try {
                openPipe(i, up)
            } catch (e: Exception) {
                onLog("StripeMux pipe #${i + 1} failed: ${e.message}")
                null
            }
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
            onLog("StripeMux up: ${pipes.size}/${upstreams.size} pipes, listening on :$listenPort")
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
        s.tcpNoDelay = true
        s.connect(upstream, 8_000)
        val out = s.getOutputStream()
        val din = DataInputStream(s.getInputStream())
        socks5ClientConnect(din, out, stripeHost, stripePort)
        // HELLO binds this pipe to our session on the server.
        val payload = ByteArray(17)
        System.arraycopy(sessionId, 0, payload, 0, 16)
        payload[16] = idx.toByte()
        out.write(StripeFrame(StripeProtocol.HELLO, 0, 0, payload).encode())
        out.flush()
        return Pipe(idx, s, din, out)
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
                if (!ss.isClosed) Log.w(TAG, "accept failed: ${e.message}")
                return
            }
            scope?.launch(Dispatchers.IO) { handleClient(client) }
        }
    }

    /** SOCKS5-terminates a tun2socks connection, opens a flow, runs it. */
    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        val dest = try {
            socks5ServerHandshake(client)
        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
            return
        }
        if (dest == null) {
            try { client.close() } catch (_: Exception) {}
            return
        }
        val id = flowSeq.getAndIncrement()
        val flow = StripeFlow(id, client, this)
        flows[id] = flow
        // OPEN must precede DATA so the server learns the destination.
        if (!send(StripeFrame(StripeProtocol.OPEN, id, 0, dest.toByteArray(Charsets.US_ASCII)))) {
            flows.remove(id)
            try { client.close() } catch (_: Exception) {}
            return
        }
        flow.run()
    }

    /**
     * Reads the tun2socks SOCKS5 greeting + CONNECT and returns "host:port"
     * (or null on an unsupported/non-CONNECT request). Replies success
     * optimistically — the real dial happens on the server; a dial failure
     * surfaces later as an RST that drops the connection.
     */
    private fun socks5ServerHandshake(client: Socket): String? {
        val din = DataInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val ver = din.readUnsignedByte()
        if (ver != 0x05) return null
        val nMethods = din.readUnsignedByte()
        din.skipBytes(nMethods)
        out.write(byteArrayOf(0x05, 0x00)) // choose NO-AUTH
        out.flush()
        // Request.
        if (din.readUnsignedByte() != 0x05) return null
        val cmd = din.readUnsignedByte()
        din.readUnsignedByte() // RSV
        val atyp = din.readUnsignedByte()
        val host: String = when (atyp) {
            0x01 -> {
                val a = ByteArray(4); din.readFully(a)
                "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}.${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}"
            }
            0x03 -> {
                val len = din.readUnsignedByte()
                val a = ByteArray(len); din.readFully(a)
                String(a, Charsets.US_ASCII)
            }
            0x04 -> {
                val a = ByteArray(16); din.readFully(a)
                "[" + InetAddress.getByAddress(a).hostAddress + "]"
            }
            else -> return null
        }
        val port = (din.readUnsignedByte() shl 8) or din.readUnsignedByte()
        if (cmd != 0x01) { // only CONNECT
            out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // command not supported
            out.flush()
            return null
        }
        // Success, BND 0.0.0.0:0.
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
        return "$host:$port"
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

    /** Stripes one frame across the live pipes round-robin. */
    fun send(frame: StripeFrame): Boolean {
        val snapshot: List<Pipe> = synchronized(pipes) { ArrayList(pipes) }
        val n = snapshot.size
        if (n == 0) return false
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
                return true
            } catch (e: Exception) {
                p.dead = true
            }
        }
        return false
    }

    private fun pipeReader(p: Pipe) {
        try {
            while (true) {
                val f = StripeFrame.read(p.din)
                val flow = flows[f.flowId] ?: continue
                flow.onFrame(f)
            }
        } catch (_: Exception) {
            // pipe EOF / error: mark dead. Flows striped over it will stall
            // and be torn down; new frames route to surviving pipes.
            p.dead = true
        }
    }

    fun dropFlow(id: Int) { flows.remove(id) }
}
