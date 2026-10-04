package com.smarttools.netguard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smarttools.netguard.util.ConfigBackupSubscriptions as Codec
import org.junit.Assert.*
import org.junit.Test

class ConfigBackupSubscriptionsQaTest {
    @Test fun sameNamedSubscriptionsRoundTripIntoSeparateNewDatabaseIds() {
        val encoded = Codec.export(
            listOf("vless://first" to 11L, "vless://second" to 22L, "vless://manual" to 0L),
            listOf(Codec.Subscription(11, "Premium", "https://one.example/sub"),
                Codec.Subscription(22, "Premium", "https://two.example/sub")), JsonObject()
        )
        val restored = JsonParser.parseString(encoded.toString()).asJsonObject
        assertEquals(3, restored.get("version").asInt)
        val subs = Codec.subscriptions(restored)
        assertEquals(listOf("https://one.example/sub", "https://two.example/sub"), subs.map { it.url })
        assertNotEquals(subs[0].key, subs[1].key)
        val newIds = mapOf(subs[0].key!! to 701L, subs[1].key!! to 903L)
        val groups = Codec.profileGroups(restored, newIds, mapOf("Premium" to 903L))
        assertEquals(listOf("vless://first"), groups[701L])
        assertEquals(listOf("vless://second"), groups[903L])
        assertEquals(listOf("vless://manual"), groups[0L])
        // Refreshing/removing subscription two can now address only its own group.
        assertEquals(listOf("vless://first"), groups.filterKeys { it != 903L }[701L])
    }

    @Test fun legacyV1AndV2RemainReadable() {
        val root = JsonParser.parseString("""{"profiles":["vless://v1",{"uri":"vless://v2","subscription":"Legacy"}]}""").asJsonObject
        assertEquals(mapOf(0L to listOf("vless://v1"), 42L to listOf("vless://v2")),
            Codec.profileGroups(root, emptyMap(), mapOf("Legacy" to 42L)))
    }

    @Test fun rejectedOrUnknownExplicitKeyNeverAttachesToAnotherSameNamedSubscription() {
        val root = JsonParser.parseString("""{"profiles":[{"uri":"vless://x","subscriptionKey":"rejected","subscription":"Same"}]}""").asJsonObject
        assertEquals(mapOf(0L to listOf("vless://x")), Codec.profileGroups(root, mapOf("accepted" to 9L), mapOf("Same" to 9L)))
    }

    @Test fun duplicateReferenceKeysRejectedBeforeDatabaseInsertion() {
        val root = JsonParser.parseString("""{"subscriptions":[{"key":"s1","name":"a","url":"https://a.example"},{"key":"s1","name":"b","url":"https://b.example"}]}""").asJsonObject
        assertThrows(IllegalArgumentException::class.java) { Codec.subscriptions(root) }
    }
}
