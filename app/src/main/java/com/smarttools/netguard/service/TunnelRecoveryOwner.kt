package com.smarttools.netguard.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Serializes recovery mutations with the service's start/stop lifecycle lock. */
internal class TunnelRecoveryOwner(private val lock: Any) {
    private var generation = 0L
    private var job: Job? = null

    fun cancel(): Job? = synchronized(lock) {
        generation++
        job.also { it?.cancel(); job = null }
    }

    fun launch(scope: CoroutineScope, currentSession: () -> Boolean, block: suspend Lease.() -> Unit) = synchronized(lock) {
        job?.cancel()
        val token = ++generation
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val lease = Lease(token, currentSession)
            try {
                lease.check()
                lease.block()
            } finally {
                synchronized(lock) { if (generation == token) job = null }
            }
        }
        job = next
        next.start()
        next
    }

    inner class Lease internal constructor(private val token: Long, private val currentSession: () -> Boolean) {
        fun check() = mutate { }

        fun <T> mutate(action: () -> T): T = synchronized(lock) {
            if (generation != token || job?.isActive != true || !currentSession())
                throw CancellationException("Recovery no longer owns the tunnel")
            action()
        }
    }
}
