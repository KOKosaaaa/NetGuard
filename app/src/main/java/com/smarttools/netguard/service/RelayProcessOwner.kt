package com.smarttools.netguard.service

import java.io.BufferedWriter
import java.util.concurrent.TimeUnit

/** Admission and detach share a lock; process I/O and reaping never hold it. */
internal class RelayProcessOwner {
    private val lock = Any()
    @Volatile var process: Process? = null
        private set
    @Volatile var writer: BufferedWriter? = null
        private set
    @Volatile var stopped = false
        private set

    fun admit(candidate: Process, input: BufferedWriter): Boolean {
        val accepted = synchronized(lock) {
            if (stopped) false else {
                process = candidate
                writer = input
                true
            }
        }
        if (!accepted) reap(candidate, input)
        return accepted
    }

    fun stop() {
        val detached = synchronized(lock) {
            stopped = true
            val old = process to writer
            process = null
            writer = null
            old
        }
        reap(detached.first, detached.second)
    }

    private fun reap(process: Process?, input: BufferedWriter?) {
        // Termination must precede writer.close: a blocked pipe write may own its monitor.
        runCatching { process?.destroy() }
        if (process == null && input == null) return
        Thread {
            try {
                if (process != null && !process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } catch (_: Exception) {
                runCatching { process?.destroyForcibly() }
            } finally {
                runCatching { input?.close() }
            }
        }.apply { isDaemon = true; name = "relay-reaper" }.start()
    }
}
