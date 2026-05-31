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

    private class TxChunk(val off: Long, val data: ByteArray)

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

    // s2c receive state — touched only by the writer thread, so no lock.
    private val rx = StripeReorder(StripeProtocol.WINDOW.toInt())
    private var rxFinal: Long = 0
    private var rxFinSet = false
    private var lastAckSent: Long = 0

    private val s2cQueue = ArrayBlockingQueue<StripeFrame>(
        (StripeProtocol.WINDOW / StripeProtocol.CHUNK_SIZE).toInt() + 8
    )
    private val POISON = StripeFrame(StripeProtocol.RST, -1, 0, EMPTY)

    private val rxDone = AtomicBoolean(false)
    private val txDone = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile private var writerThread: Thread? = null
    @Volatile private var pumpThread: Thread? = null
    @Volatile private var retransmitThread: Thread? = null

    fun run() {
        writerThread = Thread({ s2cWriter() }, "stripe-s2c-$id").apply { isDaemon = true; start() }
        pumpThread = Thread({ c2sPump() }, "stripe-c2s-$id").apply { isDaemon = true; start() }
        retransmitThread = Thread({ c2sRetransmit() }, "stripe-rtx-$id").apply { isDaemon = true; start() }
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

    /** c2s: read the app socket, buffer + stripe each chunk, gate on the window. */
    private fun c2sPump() {
        val buf = ByteArray(StripeProtocol.CHUNK_SIZE)
        try {
            while (true) {
                lock.withLock {
                    while (txNext - txBase >= StripeProtocol.WINDOW && !closed.get()) {
                        cond.await()
                    }
                }
                if (closed.get()) return
                val n = clientIn.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val data = buf.copyOf(n)
                val off: Long
                lock.withLock {
                    off = txNext
                    txNext += n
                    unacked.addLast(TxChunk(off, data))
                }
                mux.send(StripeFrame(StripeProtocol.DATA, id, off, data))
            }
            lock.withLock { finReached = true; finSeq = txNext }
            mux.send(StripeFrame(StripeProtocol.FIN, id, txNext, EMPTY))
            txDone.set(true)
            maybeFinish()
        } catch (e: Exception) {
            if (!closed.get()) abort()
        }
    }

    /** Resend the unacked window when the server's Acks stop advancing. */
    private fun c2sRetransmit() {
        var lastBase = -1L
        try {
            while (!closed.get()) {
                Thread.sleep(StripeProtocol.RETRANSMIT_RTO_MS)
                if (closed.get()) return
                var resend: List<TxChunk> = emptyList()
                var fr = false
                var fs = 0L
                lock.withLock {
                    if (txBase < txNext) {
                        val progressed = txBase != lastBase
                        lastBase = txBase
                        if (!progressed) {
                            resend = ArrayList(unacked); fr = finReached; fs = finSeq
                        }
                    } else {
                        lastBase = txBase
                    }
                }
                if (resend.isEmpty()) continue
                for (c in resend) {
                    mux.send(StripeFrame(StripeProtocol.DATA, id, c.off, c.data))
                }
                if (fr) mux.send(StripeFrame(StripeProtocol.FIN, id, fs, EMPTY))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
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
                        maybeAck()
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

    private fun maybeAck() {
        val d = rx.delivered()
        if (d - lastAckSent >= StripeProtocol.ACK_THRESHOLD) {
            mux.broadcast(StripeFrame(StripeProtocol.ACK, id, d, EMPTY))
            lastAckSent = d
        }
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
        retransmitThread?.interrupt()
        try { client.close() } catch (_: Exception) {}
        mux.dropFlow(id)
    }
}
