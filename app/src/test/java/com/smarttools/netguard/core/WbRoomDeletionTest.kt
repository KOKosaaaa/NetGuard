package com.smarttools.netguard.core

import com.smarttools.netguard.model.*
import org.junit.Assert.*
import org.junit.Test

class WbRoomDeletionTest {
    private val a = "https://stream.wb.ru/room/a"
    private val b = "https://stream.wb.ru/room/b"
    private fun p(id: Long, address: String) = ServerProfile(id=id, protocol=Protocol.WBSTREAM, address=address)
    @Test fun deletingLastReferenceRemovesItsRoom() {
        val profile = p(1,a)
        assertEquals(setOf(a), WbRoomDeletionPlan.unusedRooms(profile,listOf(profile)))
    }
    @Test fun sharedRoomRemainsUntilLastProfileIsDeleted() {
        val first = p(1,"$a\n$b")
        val second = p(2,a).copy(protocol=Protocol.TELEMOST)
        assertEquals(setOf(b),WbRoomDeletionPlan.unusedRooms(first,listOf(first,second)))
        assertEquals(setOf(a),WbRoomDeletionPlan.unusedRooms(second,listOf(second)))
    }
    @Test fun canonicalLinksMatchAndUnrelatedProtocolsAreIgnored() {
        val first=p(1,a)
        assertTrue(WbRoomDeletionPlan.unusedRooms(first,listOf(first,p(2,"wbstream://a"))).isEmpty())
        assertTrue(WbRoomDeletionPlan.unusedRooms(ServerProfile(address="example.com"),emptyList()).isEmpty())
    }
}
