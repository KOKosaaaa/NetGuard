package com.smarttools.netguard.service

import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded writer per physical channel. Callers never wait on Socket.write. */
internal class QueuedPipeWriter(private val socket: Socket, private val output: OutputStream, private val failed: () -> Unit) {
    private val data = ArrayBlockingQueue<StripeFrame>(64)
    private val control = ArrayBlockingQueue<StripeFrame>(64)
    private val available = Semaphore(0)
    private val closed = AtomicBoolean(false)
    @Volatile private var writeStarted = 0L
    private val deadline = timers.scheduleAtFixedRate({
        val started = writeStarted
        if (started != 0L && System.nanoTime() - started > 8_000_000_000L) fail()
    }, 1, 1, TimeUnit.SECONDS)
    private val worker = Thread({ run() }, "stripe-pipe-writer").apply { isDaemon = true; start() }

    fun offer(frame: StripeFrame): Boolean {
        if (closed.get()) return false
        val queue = if (frame.type == StripeProtocol.DATA) data else control
        if (!queue.offer(frame)) {
            if (queue === control) fail()
            return false
        }
        available.release()
        return true
    }

    private fun run() {
        var controls = 0
        try {
            while (!closed.get()) {
                available.acquire()
                if (closed.get()) break
                val frame = (if (controls < 8) control.poll() else null) ?: data.poll() ?: control.poll() ?: continue
                controls = if (frame.type == StripeProtocol.DATA) 0 else controls + 1
                writeStarted = System.nanoTime()
                output.write(frame.encode())
                writeStarted = 0L
            }
        } catch (_: Exception) { if (!closed.get()) fail() }
    }

    private fun fail() { if (!closed.get()) { close(); failed() } }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        deadline.cancel(false)
        data.clear(); control.clear()
        runCatching { socket.close() }
        available.release(); worker.interrupt()
    }

    companion object {
        val timers = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "stripe-timers").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }
}
