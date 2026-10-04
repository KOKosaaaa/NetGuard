package com.smarttools.netguard.service

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import com.smarttools.netguard.model.Protocol
import com.smarttools.netguard.model.ServerProfile

class RelayStartupTest {
    @Test fun firstRoomMayArriveAfterPoolGrace() = runBlocking {
        val slow = async { delay(150); "wb" }
        assertEquals(listOf("wb"), awaitRelayPool(listOf(slow), 2000, 20))
    }
    @Test fun fastRoomDoesNotWaitForDeadRoom() = runBlocking {
        val ready = CompletableDeferred<String?>("ready")
        val dead = CompletableDeferred<String?>()
        try {
            assertEquals(listOf("ready"), withTimeout(2000) { awaitRelayPool(listOf(dead, ready), 10_000, 20) })
        } finally { dead.cancel() }
    }
    @Test fun failedRoomDoesNotHideLaterSuccess() = runBlocking {
        val failed = CompletableDeferred<String?>().apply { complete(null) }
        val later = async { delay(50); "ready" }
        assertEquals(listOf("ready"), awaitRelayPool(listOf(failed, later), 2000, 0))
    }
    @Test fun noRoomHasBoundedWait() = runBlocking {
        val dead = CompletableDeferred<String?>()
        try { assertTrue(awaitRelayPool(listOf(dead), 30, 0).isEmpty()) }
        finally { dead.cancel() }
    }
    @Test fun parentCancellationIsNotReportedAsFailedConnection() = runBlocking {
        val dead = CompletableDeferred<String?>()
        val waiting = async { awaitRelayPool(listOf(dead), 10_000, 0) }
        yield()
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        dead.cancel()
    }
    @Test fun wbFailoverNeverSelectsOrdinaryVpn() {
        val wb = ServerProfile(id = 1, protocol = Protocol.WBSTREAM)
        val legacy = ServerProfile(id = 2, protocol = Protocol.TELEMOST, address = "https://stream.wb.ru/room/12345678-1234-1234-1234-123456789012")
        val ordinary = ServerProfile(id = 3, lastPingMs = 1)
        assertEquals(setOf(1L, 2L), failoverCandidates(wb, listOf(ordinary, legacy, wb)).map { it.id }.toSet())
        assertEquals(listOf(wb), failoverCandidates(wb, listOf(ordinary, wb)))
        assertEquals(3, failoverCandidates(ordinary, listOf(ordinary, legacy, wb)).size)
    }
}
