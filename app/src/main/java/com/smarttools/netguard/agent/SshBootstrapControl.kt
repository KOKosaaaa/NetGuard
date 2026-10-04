package com.smarttools.netguard.agent

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

/** Closing the transport, rather than just cancelling a coroutine, wakes blocking SSH reads. */
class SshBootstrapControl {
    private val cancelled = AtomicBoolean()
    private val connections = CopyOnWriteArrayList<() -> Unit>()

    fun track(close: () -> Unit) {
        connections.add(close)
        if (cancelled.get()) {
            connections.remove(close)
            closeAsync(close)
            checkActive()
        }
    }

    fun checkActive() {
        if (cancelled.get()) throw CancellationException("SSH bootstrap cancelled")
    }

    fun cancel() {
        cancelled.set(true)
        closeConnections()
    }

    fun closeConnections() {
        val pending = connections.toList()
        connections.removeAll(pending.toSet())
        pending.forEach(::closeAsync)
    }

    /** A socket read timeout alone doesn't bound multi-step banner/KEX/auth handshakes. */
    fun <T> handshake(timeoutMs: Long, close: () -> Unit, block: () -> T): T {
        checkActive()
        val state = AtomicInteger(0) // running / finished / deadline
        val alarm = deadlines.schedule({
            if (state.compareAndSet(0, 2)) {
                closeAsync(close)
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val result = block()
            checkActive()
            if (!state.compareAndSet(0, 1) && state.get() == 2) throw deadlineFailure(timeoutMs)
            return result
        } catch (e: Exception) {
            closeAsync(close)
            checkActive()
            if (state.get() == 2) throw deadlineFailure(timeoutMs)
            throw e
        } finally {
            state.compareAndSet(0, 1)
            alarm.cancel(false)
        }
    }

    private fun deadlineFailure(ms: Long) = SshBootstrap.Failure.BannerTimeout(
        "SSH handshake did not finish within ${ms / 1000}s",
        "Transport closed at the SSH handshake deadline; no installation was started.",
    )

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        private val deadlines = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ssh-deadline").apply { isDaemon = true }
        }
        private val closers = Executors.newCachedThreadPool { r ->
            Thread(r, "ssh-close").apply { isDaemon = true }
        }
        private fun closeAsync(close: () -> Unit) { closers.execute { runCatching(close) } }
    }
}
