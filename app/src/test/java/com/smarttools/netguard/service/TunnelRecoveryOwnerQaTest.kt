package com.smarttools.netguard.service

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TunnelRecoveryOwnerQaTest {
    @Test fun stalePortWaitCannotTouchReplacementTunnelEvenIfCancellationIsIgnored() = runBlocking {
        val lock = Any()
        val owner = TunnelRecoveryOwner(lock)
        var epoch = 1
        var tunnel = "A"
        val waiting = CompletableDeferred<Unit>()
        val portReady = CompletableDeferred<Unit>()
        val recovery = owner.launch(this, { epoch == 1 }) {
            waiting.complete(Unit)
            withContext(NonCancellable) { portReady.await() }
            mutate { tunnel = "old HEV / stopped new tunnel" }
        }
        waiting.await()
        synchronized(lock) { epoch = 2; owner.cancel(); tunnel = "B" }
        portReady.complete(Unit)
        recovery.join()
        assertEquals("B", tunnel)
        assertTrue(recovery.isCancelled)
    }

    @Test fun previousRecoveryFinallyCannotRemoveNewRecoveryOwnership() = runBlocking {
        val owner = TunnelRecoveryOwner(Any())
        val oldStarted = CompletableDeferred<Unit>()
        val finishOld = CompletableDeferred<Unit>()
        val old = owner.launch(this, { true }) {
            oldStarted.complete(Unit)
            withContext(NonCancellable) { finishOld.await() }
        }
        oldStarted.await()
        val newStarted = CompletableDeferred<Unit>()
        val finishNew = CompletableDeferred<Unit>()
        var connected = false
        val newer = owner.launch(this, { true }) {
            newStarted.complete(Unit)
            finishNew.await()
            mutate { connected = true }
        }
        newStarted.await()
        finishOld.complete(Unit)
        old.join()
        finishNew.complete(Unit)
        newer.join()
        assertTrue(connected)
        assertFalse(newer.isCancelled)
    }

    @Test fun stopInvalidatesSuccessfulReadinessBeforeConnectedStateMutation() = runBlocking {
        val owner = TunnelRecoveryOwner(Any())
        val awaitingReadiness = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var connected = false
        val recovery = owner.launch(this, { true }) {
            mutate { /* restart remains owned */ }
            awaitingReadiness.complete(Unit)
            withContext(NonCancellable) { response.await() }
            mutate { connected = true }
        }
        awaitingReadiness.await()
        owner.cancel()
        response.complete(Unit)
        recovery.join()
        assertFalse(connected)
    }
}
