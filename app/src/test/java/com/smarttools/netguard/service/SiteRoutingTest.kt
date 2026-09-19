package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SiteRoutingTest {
    private val origin = SiteOrigin("chatgpt.com", 443, true)
    private val good = SiteMeasurement(2, 100)

    @Test fun decisionsSurviveRestartForAWeekButNeverCrossNetworksOrServers() {
        var time = 1_000_000L
        val policy = SiteRoutingPolicy("wifi|server1", { time })
        policy.record(origin, "8.8.8.8", good, SiteMeasurement(2, 600))
        assertEquals(SitePath.DIRECT, policy.cached(origin)!!.path)
        val json = policy.export()
        time += SiteRoutingPolicy.WEEK_MS - 1
        val restored = SiteRoutingPolicy("wifi|server1", { time }).apply { restore(json) }
        assertEquals(SitePath.DIRECT, restored.cached(origin)!!.path)
        assertNull(SiteRoutingPolicy("cell|server1", { time }).apply { restore(json) }.cached(origin))
        assertNull(SiteRoutingPolicy("wifi|server2", { time }).apply { restore(json) }.cached(origin))
        time++
        assertNull(restored.cached(origin))
        assertEquals(1, restored.expired().size)
        time = 999_999L
        assertNull(SiteRoutingPolicy("wifi|server1", { time }).apply { restore(json) }.cached(origin))
    }

    @Test fun activeSessionsAndGraceKeepTheirEgressWhenWeeklyCheckIsDue() {
        var time = 1_000_000L
        val policy = SiteRoutingPolicy("network", { time })
        val record = policy.record(origin, "8.8.8.8", SiteMeasurement(), good)
        assertEquals(SitePath.VPN, policy.pin(record))
        time += SiteRoutingPolicy.WEEK_MS + 1
        assertNotNull(policy.cached(origin)); assertEquals(1, policy.expired().size)
        assertEquals(SitePath.VPN, policy.record(origin, "8.8.8.8", good, SiteMeasurement()).path)
        policy.release(origin)
        assertEquals(SitePath.VPN, policy.cached(origin)!!.path)
        time += 120_001
        assertEquals(SitePath.DIRECT, policy.cached(origin)!!.path)
        assertFalse(policy.needsCheck(origin))
    }

    @Test fun backgroundWinnerIsSavedWithoutChangingAnUnmeasuredFirstSession() {
        var time = 1_000_000L
        val p = SiteRoutingPolicy("network", { time })
        assertEquals(SitePath.VPN, p.pin(p.forConnection(origin, "8.8.8.8")))
        assertTrue(p.needsCheck(origin))
        assertEquals(SitePath.VPN, p.record(origin, "8.8.8.8", good, SiteMeasurement()).path)
        assertFalse(p.needsCheck(origin))
        assertEquals(SitePath.VPN, p.pin(p.forConnection(origin, "8.8.8.8")))
        p.release(origin); p.release(origin)
        assertEquals(SitePath.VPN, p.forConnection(origin, "8.8.8.8").path)
        // Persistence stores the measured winner, not the temporary session path.
        val restarted = SiteRoutingPolicy("network", { time }).apply { restore(p.export()) }
        assertEquals(SitePath.DIRECT, restarted.cached(origin)!!.path)
        time += 120_001
        assertEquals(SitePath.DIRECT, p.forConnection(origin, "8.8.8.8").path)
    }

    @Test fun routeQualityBeatsLatencyAndSmallJitterDoesNotSwitchIps() {
        val p = SiteRoutingPolicy("network")
        assertEquals(SitePath.VPN, p.record(origin, "8.8.8.8", SiteMeasurement(1, 10), SiteMeasurement(2, 800)).path)
        assertEquals(SitePath.VPN, p.record(origin, "8.8.8.8", SiteMeasurement(2, 150), SiteMeasurement(2, 200)).path)
        assertEquals(SitePath.DIRECT, p.record(origin, "8.8.8.8", SiteMeasurement(2, 100), SiteMeasurement(2, 600)).path)
        assertEquals(SitePath.DIRECT, p.record(origin, "8.8.8.8", SiteMeasurement(2, 190), SiteMeasurement(2, 160)).path)
    }

    @Test fun failuresAreNotCachedForAWeekAndConnectFailureInvalidatesImmediately() {
        var time = 1_000_000L
        val p = SiteRoutingPolicy("network", { time })
        p.record(origin, "8.8.8.8", SiteMeasurement(), SiteMeasurement())
        time += 60_001
        assertNull(p.cached(origin))
        val goodRecord = p.record(origin, "8.8.8.8", good, SiteMeasurement())
        p.pin(goodRecord); p.connectionFailed(origin, SitePath.DIRECT); p.release(origin)
        assertNull(p.cached(origin))
        p.restore("not JSON") // Damaged cache cannot break new connections.
    }

    @Test fun historyIsBoundedAndDoesNotStoreRequestPaths() {
        val p = SiteRoutingPolicy("network")
        repeat(SiteRoutingPolicy.MAX_SITES + 100) {
            p.record(SiteOrigin("s$it.test", 443, true), "8.8.8.8", good, good)
        }
        assertNull(p.cached(SiteOrigin("s0.test", 443, true)))
        assertNotNull(p.cached(SiteOrigin("s1123.test", 443, true)))
        assertFalse(p.export().contains("https://"))
    }

    @Test fun explicitDomainAndCidrExclusionsKeepTheirOriginalRules() {
        val d = SocksDestination("8.8.8.8", 443)
        assertFalse(SiteBypassRules("domain:chatgpt.com", "").allows(d, origin))
        assertFalse(SiteBypassRules("", "8.8.0.0/16").allows(d, origin))
        assertTrue(SiteBypassRules("full:other.test", "8.9.0.0/16").allows(d, origin))
        assertFalse(SiteBypassRules("geosite:ru", "").allows(d, origin))
        assertFalse(SiteBypassRules("", "").allows(SocksDestination("192.168.1.1", 443), origin))
    }

    @Test fun fragmentedTlsHelloFindsSniWithoutChangingBytesAndEchStaysOpaque() {
        val hello = hello("ChatGPT.com", false)
        val before = hello.clone()
        assertEquals(WebHello.Result.More, WebHello.inspect(hello.copyOf(7)))
        assertEquals(WebHello.Result.Site("chatgpt.com", true), WebHello.inspect(hello))
        assertArrayEquals(before, hello)
        assertEquals(WebHello.Result.Opaque, WebHello.inspect(hello("chatgpt.com", true)))
        assertEquals(WebHello.Result.Opaque, WebHello.inspect(byteArrayOf(22, 3, 3, -1, -1)))
    }

    @Test fun httpParserRequiresOneHostAndDoesNotConfuseOtherProtocols() {
        assertEquals(WebHello.Result.Site("example.com", false), WebHello.inspect(
            "POST /private?token=secret HTTP/1.1\r\nHost: Example.com:8080\r\n\r\nbody".toByteArray()))
        assertEquals(WebHello.Result.Opaque, WebHello.inspect("GET / HTTP/1.1\r\nHost: a.test\r\nHost: b.test\r\n\r\n".toByteArray()))
        assertEquals(WebHello.Result.Opaque, WebHello.inspect("SSH-2.0-server\r\n".toByteArray()))
        assertEquals(WebHello.Result.More, WebHello.inspect("GET / HTTP/1.1\r\nHo".toByteArray()))
    }

    @Test fun udpValidationAllowsQuicAndCallsAndRejectsMalformedEnvelopes() {
        val header = byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 1, 0xbb.toByte())
        val quic = ByteArray(1200).also { it[0] = 0xc0.toByte(); it[4] = 1; it[5] = 8; it[14] = 8 }
        assertTrue(SiteUdpRelay.validPacket(header + quic))
        quic[0] = 0x40
        assertTrue(SiteUdpRelay.validPacket(header + quic))
        assertTrue(SiteUdpRelay.validPacket(header + "STUN/DTLS data".toByteArray()))
        assertFalse(SiteUdpRelay.validPacket(byteArrayOf(0, 0, 0, 4)))
        assertFalse(SiteUdpRelay.validPacket((header + byteArrayOf(1)).also { it[2] = 1 }))
    }

    private fun hello(host: String, ech: Boolean): ByteArray {
        fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out -> DataOutputStream(out).use(block) }.toByteArray()
        val name = host.toByteArray()
        val extensions = bytes {
            writeShort(0); writeShort(5 + name.size); writeShort(3 + name.size); writeByte(0); writeShort(name.size); write(name)
            if (ech) { writeShort(0xfe0d); writeShort(1); writeByte(0) }
        }
        val body = bytes { writeShort(0x303); write(ByteArray(32)); writeByte(0); writeShort(2); writeShort(0x1301)
            writeByte(1); writeByte(0); writeShort(extensions.size); write(extensions) }
        val handshake = bytes { writeByte(1); writeByte(0); writeShort(body.size); write(body) }
        // Fragment across TLS records, independently of TCP fragmentation.
        return bytes { writeByte(22); writeShort(0x303); writeShort(17); write(handshake, 0, 17)
            writeByte(22); writeShort(0x303); writeShort(handshake.size - 17); write(handshake, 17, handshake.size - 17) }
    }
}
