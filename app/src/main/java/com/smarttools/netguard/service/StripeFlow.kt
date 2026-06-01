package com.smarttools.netguard.service

import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One application connection multiplexed over [StripeMux]'s pipes. Mirror of
 * the Go server's flow, from the client's point of view:
 *
 *   - c2s (app -> server): the pump thread reads the tun2socks socket, buffers
 *     each chunk for possible retransmit, stripes Data across pipes, and
 *     window-gates on the server's Acks. A retransmit thread resends the
 *     unacked window if Acks stop advancing (a room died mid-transfer) — the
 *     substrate has no retransmit of its own, so without this a dead room
 *     leaves a permanent gap and the flow stalls to zero.
 *   - s2c (server -> app): a writer thread drains a queue fed by the pipe
 *     readers, reassembles in offset order, writes to the app socket, and
 *     broadcasts our cumulative-delivered Ack on every pipe so the server's
 *     window can't stall behind one slow pipe.
 */
class StripeFlow(
    private val id: Int,
    private val client: Socket,
    private val mux: StripeMux
) {
    private companion object {
        private val EMPTY = ByteArray(0)
    }

    private class TxChunk(val off: Long, val data: ByteArray, var pid: Int = -1)

    private val clientIn = DataInputStream(client.getInputStream())
    private val clientOut: OutputStream = client.getOutputStream()

    // c2s send state — guarded by lock/cond.
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var txBase: Long = 0   // cumulative bytes acked by the server
    private var txNext: Long = 0   // next offset to assign
    private val unacked = ArrayDeque<TxChunk>()
    private var finReached = false
    private var finSeq: Long = 0
    private var finPid: Int = -1
    private var homeIdx: Int = -1 // pinned home pipe while c2s is small (touched only by pump thread)

    // s2c receive state — touched only by the writer thread, so no lock.
    private val rx = StripeReorder(StripeProtocol.MAX_REORDER)
    private var rxFinal: Long = 0
    private var rxFinSet = false
    private var lastPosMs: Long = 0

    private val s2cQueue = ArrayBlockingQueue<StripeFrame>(512)
    private val POISON = StripeFrame(StripeProtocol.RST, -1, 0, EMPTY)

    private val rxDone = AtomicBoolean(false)
    private val txDone = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile private var writerThread: Thread? = null
    @Volatile private var pumpThread: Thread? = null

    fun run() {
        writerThread = Thread({ s2cWriter() }, "stripe-s2c-$id").apply { isDaemon = true; start() }
        pumpThread = Thread({ c2sPump() }, "stripe-c2s-$id").apply { isDaemon = true; start() }
    }

    /** Called from pipe-reader threads. Never blocks on the app socket. */
    fun onFrame(f: StripeFrame) {
        when (f.type) {
            StripeProtocol.ACK -> onAck(f.seq)
            StripeProtocol.DATA, StripeProtocol.FIN -> {
                try {
                    s2cQueue.put(f)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            StripeProtocol.RST -> close()
        }
    }

    /** Advance the c2s window and drop fully-acked chunks from the buffer. */
    private fun onAck(cum: Long) {
        lock.withLock {
            if (cum > txBase) {
                txBase = cum
                while (unacked.isNotEmpty()) {
                    val c = unacked.first()
                    if (c.off + c.data.size <= txBase) unacked.removeFirst() else break
                }
                cond.signalAll()
            }
        }
    }

    /** c2s: read the app socket, retain + stripe each chunk. No window gate —
     *  the pipe writes block on backpressure; the only gate is a generous
     *  retain cap so memory stays bounded if position reports stop. */
    private fun c2sPump() {
        val buf = ByteArray(StripeProtocol.CHUNK_SIZE)
        try {
            while (true) {
                lock.withLock {
                    while (txNext - txBase >= StripeProtocol.MAX_REORDER && !closed.get()) {
                        cond.await()
                    }
                }
                if (closed.get()) return
                val n = clientIn.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val data = buf.copyOf(n)
                val ch: TxChunk
                lock.withLock {
                    ch = TxChunk(txNext, data)
                    txNext += n
                    unacked.addLast(ch)
                }
                // Small flows ride one pinned pipe (reliable, like round-robin
                // — this is what fixes Telegram's many tiny conns); only bulk
                // flows stripe across all pipes.
                val f = StripeFrame(StripeProtocol.DATA, id, ch.off, data)
                val pid = if (ch.off < StripeProtocol.PROMOTE_THRESHOLD) {
                    val r = mux.sendPinned(homeIdx, f)
                    if (r >= 0) homeIdx = r
                    r
                } else {
                    mux.send(f)
                }
                lock.withLock { ch.pid = pid }
            }
            val fs: Long
            lock.withLock { finReached = true; finSeq = txNext; fs = txNext }
            val pid = mux.send(StripeFrame(StripeProtocol.FIN, id, fs, EMPTY))
            lock.withLock { finPid = pid }
            txDone.set(true)
            maybeFinish()
        } catch (e: Exception) {
            if (!closed.get()) abort()
        }
    }

    /**
     * Resend only the c2s chunks that rode the dead pipe (plus any never-sent
     * one) over a live pipe — the chunks the substrate just lost. Event-driven
     * off a real pipe death, so a merely-slow Ack never triggers a resend
     * storm; the server dedups resends by offset.
     */
    fun onPipeDead(deadId: Int) {
        val resend = ArrayList<TxChunk>()
        var resendFin = false
        var fs = 0L
        lock.withLock {
            for (c in unacked) if (c.pid == deadId || c.pid == -1) resend.add(c)
            if (finReached && (finPid == deadId || finPid == -1)) { resendFin = true; fs = finSeq }
        }
        for (c in resend) {
            val pid = mux.send(StripeFrame(StripeProtocol.DATA, id, c.off, c.data))
            lock.withLock { c.pid = pid }
        }
        if (resendFin) {
            val pid = mux.send(StripeFrame(StripeProtocol.FIN, id, fs, EMPTY))
            lock.withLock { finPid = pid }
        }
    }

    /** s2c: reassemble server bytes in order and write them to the app. */
    private fun s2cWriter() {
        try {
            while (true) {
                val f = s2cQueue.take()
                if (f === POISON || closed.get()) return
                when (f.type) {
                    StripeProtocol.DATA -> {
                        val out = try {
                            rx.insert(f.seq, f.payload)
                        } catch (e: Exception) {
                            abort(); return
                        }
                        if (out != null) {
                            clientOut.write(out)
                            clientOut.flush()
                        }
                        maybePos()
                        if (checkRxFin()) return
                    }
                    StripeProtocol.FIN -> {
                        rxFinal = f.seq
                        rxFinSet = true
                        if (checkRxFin()) return
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) abort()
        }
    }

    private fun maybePos() {
        val now = System.currentTimeMillis()
        if (now - lastPosMs < StripeProtocol.POS_INTERVAL_MS) return
        lastPosMs = now
        mux.broadcast(StripeFrame(StripeProtocol.ACK, id, rx.delivered(), EMPTY))
    }

    private fun checkRxFin(): Boolean {
        if (!rxFinSet || rx.delivered() != rxFinal) return false
        try { client.shutdownOutput() } catch (_: Exception) {}
        mux.broadcast(StripeFrame(StripeProtocol.ACK, id, rx.delivered(), EMPTY))
        rxDone.set(true)
        maybeFinish()
        return true
    }

    private fun maybeFinish() {
        if (rxDone.get() && txDone.get()) close()
    }

    private fun abort() {
        mux.send(StripeFrame(StripeProtocol.RST, id, 0, EMPTY))
        close()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        lock.withLock { cond.signalAll() }
        s2cQueue.offer(POISON)
        writerThread?.interrupt()
        pumpThread?.interrupt()
        try { client.close() } catch (_: Exception) {}
        mux.dropFlow(id)
    }
}
