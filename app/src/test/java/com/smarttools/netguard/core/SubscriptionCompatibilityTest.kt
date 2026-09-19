package com.smarttools.netguard.core

import com.google.gson.JsonParser
import com.smarttools.netguard.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.util.Base64

class SubscriptionCompatibilityTest {
    private val base = "vless://00000000-0000-4000-8000-000000000001@8.8.8.8:443"
    @Test fun xhttpExtraAndEncodedPathSurviveImportAndExport() {
        val extra = """{"xmux":{"maxConcurrency":"16-32"},"noGRPCHeader":true}"""
        val uri = "$base?type=xhttp&mode=packet-up&path=%2Fa%252Fb&extra=${URLEncoder.encode(extra, "UTF-8")}&security=none#Test"
        val p = ProfileParser.parseSingleUri(uri)!!
        assertEquals(TransportType.SPLIT_HTTP, p.network)
        assertEquals("/a%2Fb", p.path)
        assertEquals(extra, p.xhttpExtra)
        val root = JsonParser.parseString(XrayConfigGenerator.generate(p, AppSettings(bypassLan=false)).json).asJsonObject
        val stream = root.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("streamSettings")
        assertEquals("xhttp", stream["network"].asString)
        assertEquals(JsonParser.parseString(extra), stream.getAsJsonObject("xhttpSettings")["extra"])
        assertEquals(p.xhttpExtra, ProfileParser.parseSingleUri(p.toUri())!!.xhttpExtra)
    }
    @Test fun supportsHappWrappersAndBase64Lists() {
        val link = "https://example.com/SubCase?token=a%2Bb"
        assertEquals(link, SubscriptionLink.unwrap("happ://add/$link"))
        assertEquals(link, SubscriptionLink.unwrap("happ://add/${URLEncoder.encode(link, "UTF-8")}"))
        val body = "# comment\n$base?type=tcp#One\n$base?type=xhttp#Two"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())
        assertEquals(2, ProfileParser.parseSubscription(encoded).profiles.size)
    }
    @Test fun unknownTransportIsReportedWithoutFallingBackToTcp() {
        val result = ProfileParser.parseSubscription("$base?type=future-transport#Test")
        assertTrue(result.profiles.isEmpty()); assertFalse(result.errors.isEmpty())
        assertFalse(result.errors.joinToString().contains("00000000"))
    }
    @Test fun jsonPreservesTransportAndDetoursWithoutInstallingProviderInbounds() {
        val json = """{"remarks":"test","inbounds":[{"port":1}],"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"8.8.8.8","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000001","encryption":"none"}]}]},"streamSettings":{"network":"xhttp","xhttpSettings":{"path":"/test","extra":{"xmux":{"maxConnections":2}}},"sockopt":{"dialerProxy":"hop"}}},{"tag":"hop","protocol":"freedom","settings":{}}]}"""
        val p = ProfileParser.parseSubscription(json).profiles.single()
        val config = JsonParser.parseString(XrayConfigGenerator.generate(p, AppSettings(bypassLan=false)).json).asJsonObject
        assertEquals(3, config.getAsJsonArray("inbounds").size())
        val out = config.getAsJsonArray("outbounds")
        assertEquals(JsonParser.parseString(json).asJsonObject.getAsJsonArray("outbounds")[0], out[0])
        assertTrue(out.any { it.asJsonObject["tag"].asString == "hop" })
    }
    @Test fun dnsAndHealthAlwaysTraverseProxyAndFragmentIsInFreedomSettings() {
        val p = ServerProfile(address="8.8.8.8", uuid="00000000-0000-4000-8000-000000000001")
        val config = JsonParser.parseString(XrayConfigGenerator.generate(p, AppSettings(dohEnabled=true,tlsFragmentEnabled=true)).json).asJsonObject
        assertFalse(config["dns"].toString().contains("https+local"))
        assertFalse(config["dns"].toString().contains("localhost"))
        val fragment = config.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "fragment" }.asJsonObject
        assertTrue(fragment.getAsJsonObject("settings").has("fragment"))
        assertFalse(fragment.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").has("fragment"))
        val first = config.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject
        assertEquals("proxy", first["outboundTag"].asString)
        assertTrue(first["inboundTag"].toString().contains("health-in"))
        assertTrue(first["inboundTag"].toString().contains("http-in"))
    }
    @Test fun privateFixtureImportsAll24ProfilesAndProducesCoreConfigs() {
        val path = System.getenv("NETGUARD_SUBSCRIPTION_FIXTURE")
        assumeTrue(path != null)
        val result = ProfileParser.parseSubscription(File(path!!).readText())
        assertEquals(24, result.profiles.size); assertTrue(result.errors.isEmpty())
        assertEquals(1, result.profiles.count { it.network == TransportType.SPLIT_HTTP })
        val output = File(File(path).parentFile, "validated-configs").apply { mkdirs() }
        result.profiles.forEachIndexed { i, p -> File(output,"profile-$i.json").writeText(XrayConfigGenerator.generate(p, AppSettings(bypassLan=false)).json) }
        val hysteria = ServerProfile(protocol=Protocol.HYSTERIA2, address="8.8.8.8", hysteriaAuth="test", hysteriaObfs="salamander", hysteriaObfsPassword="test-password", security=SecurityType.TLS)
        File(output,"validation-hysteria.json").writeText(XrayConfigGenerator.generate(hysteria, AppSettings(bypassLan=false)).json)
        File(output,"validation-doh-fragment.json").writeText(XrayConfigGenerator.generate(result.profiles.first(), AppSettings(bypassLan=false,dohEnabled=true,tlsFragmentEnabled=true)).json)
    }
}
