package com.smarttools.netguard.core

import com.smarttools.netguard.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WbStreamTest {
    private val room = "https://stream.wb.ru/room/019f0000-1111-2222-3333-444444444444"

    @Test fun roomLinksCreateASeparateRelayProtocol() {
        val p = ProfileParser.parseSingleUri("$room#WB+%D0%BC%D0%BE%D1%81%D1%82")!!
        assertEquals(Protocol.WBSTREAM, p.protocol)
        assertEquals("WB мост", p.name)
        assertEquals(room, p.address)
        assertEquals(443, p.port)
        assertTrue(p.protocol.usesRelay)
        assertEquals("stream.wb.ru", p.relayHost)
        assertEquals("WB Stream", p.displayProtocol)
        assertFalse(Protocol.VLESS.usesRelay)
        assertTrue(Protocol.TELEMOST.usesRelay)
        assertEquals(Protocol.WBSTREAM, Protocol.fromString("wbstream"))
    }

    @Test fun shareAndQrRoundTripPreserveSingleRoomAndName() {
        val p = ProfileParser.parseSingleUri(room)!!.copy(name = "WB / Россия + #1")
        val uri = p.toUri()
        assertTrue(uri.startsWith("wbstream://"))
        val decoded = ProfileParser.parseSingleUri(uri)!!
        assertEquals(p.name, decoded.name)
        assertEquals(p.address, decoded.address)
        assertEquals(p.protocol, decoded.protocol)
    }

    @Test fun multipleRoomsRoundTripAndDeduplicateWithoutDroppingFollowingFragments() {
        val second = "https://stream.wb.ru/room/another-room"
        val p = ProfileParser.parseSingleUri("$room#first\n$second#second\n$room")!!
        assertEquals("$room\n$second", p.address)
        val decoded = ProfileParser.parseSingleUri(p.toUri())!!
        assertEquals(p.address, decoded.address)
        assertEquals("first", decoded.name)
    }

    @Test fun legacyDatabaseProfilesAndLegacyTelemostExportsRemainImportable() {
        val legacy = ServerProfile(name = "Old WB", protocol = Protocol.TELEMOST, address = room)
        assertTrue(legacy.isWbStream)
        assertEquals("stream.wb.ru", legacy.relayHost)
        assertEquals(Protocol.WBSTREAM, ProfileParser.parseSingleUri(legacy.toUri())!!.protocol)
        val oldExport = "telemost://" + Base64.getUrlEncoder().withoutPadding().encodeToString(room.toByteArray()) + "#Old+WB"
        val p = ProfileParser.parseSingleUri(oldExport)!!
        assertEquals(Protocol.WBSTREAM, p.protocol)
        assertEquals(room, p.address)
        assertEquals("Old WB", p.name)
    }

    @Test fun subscriptionSupportsPlainAndBase64RoomLists() {
        val content = "$room#one\nwbstream://room-two#two"
        for (body in listOf(content, Base64.getEncoder().encodeToString(content.toByteArray()))) {
            val result = ProfileParser.parseSubscription(body)
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertEquals(2, result.profiles.size)
            assertTrue(result.profiles.all { it.protocol == Protocol.WBSTREAM })
        }
    }

    @Test fun onlyExactHttpsRoomHostsAndBoundedRoomIdsAreAccepted() {
        val bad = listOf("", "http://stream.wb.ru/room/a", "https://stream.wb.ru.evil.test/room/a",
            "https://user@stream.wb.ru/room/a", "https://stream.wb.ru:444/room/a", "https://stream.wb.ru/room/",
            "https://stream.wb.ru/room/../other", "https://stream.wb.ru/room/a/b", "https://stream.wb.ru/room/%2fabc",
            "wbstream://", "wbstream://multi/not!base64", "wbstream://a?redirect=evil", "wbstream://" + "x".repeat(129),
            (1..13).joinToString("\n") { "https://stream.wb.ru/room/$it" })
        for (link in bad) {
            try { WbStreamLink.parse(link); fail("Accepted invalid room link") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(listOf(room), WbStreamLink.parse(room.replace("https://stream.wb.ru", "HTTPS://STREAM.WB.RU:443") + "/?utm_source=share").links)
    }

    @Test fun wbNativeLaunchUsesItsOwnJoinKeyAndOnlyPerConnectionArq() {
        val wb = RelayCarrier.forRoom(room)
        assertEquals("wbstream-headless-joiner", wb.mode)
        assertEquals("roomId", wb.roomKey)
        val env = mutableMapOf("WLB_CARRIER_ARQ" to "1", "unchanged" to "value")
        wb.configureEnvironment(env)
        assertEquals("1", env["WLB_CARRIER_PCARQ"])
        assertEquals("10000", env["WLB_CARRIER_KBPS"])
        assertEquals("probe", env["WLB_PC_RATE_MODE"])
        assertEquals("96", env["WLB_PC_FPS"])
        assertEquals("4096", env["WLB_PC_CHUNK"])
        assertFalse(env.containsKey("WLB_CARRIER_ARQ"))
        assertEquals("1", env["WLB_VALID_VP8_TUNNEL"])
        assertEquals("value", env["unchanged"])
        RelayCarrier.forRoom("https://telemost.yandex.ru/j/test").configureEnvironment(env)
        assertEquals("1", env["WLB_CARRIER_ARQ"])
        assertFalse(env.containsKey("WLB_CARRIER_PCARQ"))
        assertFalse(env.containsKey("WLB_CARRIER_KBPS"))
        assertFalse(env.containsKey("WLB_PC_RATE_MODE"))
        assertFalse(env.containsKey("WLB_PC_FPS"))
        assertFalse(env.containsKey("WLB_PC_CHUNK"))
    }

    @Test fun wbCannotAccidentallyUseXrayOutbound() {
        val p = ProfileParser.parseSingleUri(room)!!
        try { XrayConfigGenerator.generate(p, AppSettings()); fail("WB should use native relay") }
        catch (_: IllegalStateException) { }
    }

    @Test fun diagnosticsDestinationHasAnActualCompiledFragment() {
        assertNotNull(Class.forName("com.smarttools.netguard.ui.logs.LogFragment", false, javaClass.classLoader))
    }
}
