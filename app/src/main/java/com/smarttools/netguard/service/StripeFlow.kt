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
 *   - c2s (app -> server): the pump thread reads the tun2socks socket, chunks
 *     it, stripes Data frames across pipes, and window-gates on the server's
 *     Acks so we never run more than WINDOW bytes ahead.
 *   - s2c (server -> app): a writer thread drains a queue fed by the pipe
 *     readers, reassembles the bytes in offset order, writes them to the app
 *     socket, and Acks our cumulative delivery so the server's window advances.
 *
 * Frame routing (onFrame) is called from the pipe-reader threads; it only
 * enqueues s2c Data/Fin and bumps the c2s Ack — it never blocks on the app
 * socket, so one slow flow can't stall a shared pipe (the window keeps the
 * queue bounded).
 */
class StripeFlow(
    private val id: Int,
    private val client: Socket,
    private val mux: StripeMux
) {
    private companion object {
        private val EMPTY = ByteArray(0)
    }

    private val clientIn = DataInputStream(client.getInputStream())
    private val clientOut: OutputStream = client.getOutputStream()

    // c2s send state. txSeq is touched only by the pump thread; c2sAcked is
    // updated by pipe readers (onFrame) so it is guarded by lock/cond.
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var txSeq: Long = 0
    private var c2sAcked: Long = 0

    // s2c receive state — touched only by the writer thread, so no lock.
    private val rx = StripeReorder(StripeProtocol.WINDOW.toInt())
    private var rxFinal: Long = 0
    private var rxFinSet = false
    private var lastAckSent: Long = 0

    // Bounded by the window: the server won't send s2c past WINDOW unacked,
    // and we Ack only after writing to the app socket, so the queue can't grow
    // past ~WINDOW/CHUNK. +8 slack.
    private val s2cQueue = ArrayBlockingQueue<StripeFrame>(
        (StripeProtocol.WINDOW / StripeProtocol.CHUNK_SIZE).toInt() + 8
    )
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
            StripeProtocol.ACK -> {
                lock.withLock {
                    if (f.seq > c2sAcked) { c2sAcked = f.seq; cond.signalAll() }
                }
            }
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

    /** c2s: read the app socket, stripe chunks, gate on the server's window. */
    private fun c2sPump() {
        val buf = ByteArray(StripeProtocol.CHUNK_SIZE)
        try {
            while (true) {
                lock.withLock {
                    while (txSeq - c2sAcked >= StripeProtocol.WINDOW && !closed.get()) {
                        cond.await()
                    }
                }
                if (closed.get()) return
                val n = clientIn.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val off = txSeq
                txSeq += n
                if (!mux.send(StripeFrame(StripeProtocol.DATA, id, off, buf.copyOf(n)))) {
                    abort(); return
                }
            }
            // Clean app EOF: half-close c2s with the total byte count.
            mux.send(StripeFrame(StripeProtocol.FIN, id, txSeq, EMPTY))
            txDone.set(true)
            maybeFinish()
        } catch (e: Exception) {
            if (!closed.get()) abort()
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
            mux.send(StripeFrame(StripeProtocol.ACK, id, d, EMPTY))
            lastAckSent = d
        }
    }

    /** True once all s2c bytes are delivered: half-close the app socket. */
    private fun checkRxFin(): Boolean {
        if (!rxFinSet || rx.delivered() != rxFinal) return false
        try { client.shutdownOutput() } catch (_: Exception) {}
        mux.send(StripeFrame(StripeProtocol.ACK, id, rx.delivered(), EMPTY))
        rxDone.set(true)
        maybeFinish()
        return true
    }

    private fun maybeFinish() {
        if (rxDone.get() && txDone.get()) close()
    }

    /** Tell the server to drop the flow, then close locally. */
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
