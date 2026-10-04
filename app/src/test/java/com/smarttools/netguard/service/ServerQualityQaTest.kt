package com.smarttools.netguard.service

import com.google.gson.JsonParser
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.model.*
import org.junit.Assert.*
import org.junit.Test

class ServerQualityQaTest {
    private fun quality(states: List<Reachability>, ms: Int, ping: Int) = ServerQuality(
        states.mapIndexed { i, state -> HealthTarget.entries[i] to ServiceAnswer(state, ms) }.toMap(), ping)

    @Test fun deadLowPingServerCannotBeatWorkingServices() {
        val dead = quality(List(6) { Reachability.FAILED }, 5, 1)
        val working = quality(List(6) { Reachability.AVAILABLE }, 600, 250)
        assertTrue(ServerQuality.bestFirst.compare(working, dead) < 0)
    }
    @Test fun restrictedHttpResponseIsNotFullAccess() {
        val restricted = quality(List(6) { Reachability.LIMITED }, 10, 1)
        val working = quality(List(6) { Reachability.AVAILABLE }, 900, 350)
        assertTrue(ServerQuality.bestFirst.compare(working, restricted) < 0)
    }
    @Test fun coverageWinsThenServiceResponseAndPingBothMatter() {
        val complete = quality(List(6) { Reachability.AVAILABLE }, 500, 200)
        val partial = quality(List(5) { Reachability.AVAILABLE } + Reachability.FAILED, 20, 1)
        assertTrue(ServerQuality.bestFirst.compare(complete, partial) < 0)
        val quickServices = quality(List(6) { Reachability.AVAILABLE }, 100, 100)
        val slowServices = quality(List(6) { Reachability.AVAILABLE }, 400, 5)
        assertTrue(ServerQuality.bestFirst.compare(quickServices, slowServices) < 0)
        assertTrue(ServerQuality.bestFirst.compare(quickServices.copy(pingMs=10), quickServices) < 0)
    }
    @Test fun observationsInvalidateOnNetworkSettingsTargetsAndProfileChanges() {
        val profile=ServerProfile(id=41,address="vpn.example",uuid="00000000-0000-0000-0000-000000000001")
        val targets=setOf(HealthTarget.YOUTUBE)
        val settings=AppSettings()
        val result=quality(listOf(Reachability.AVAILABLE),100,40)
        ServerQualityCache.put(profile,"wifi",targets,result,settings)
        assertEquals(result,ServerQualityCache.get(profile.copy(isSelected=true,lastPingMs=1),"wifi",targets,settings))
        assertNull(ServerQualityCache.get(profile,"cellular",targets,settings))
        assertNull(ServerQualityCache.get(profile,"wifi",targets,settings.copy(primaryDns="9.9.9.9")))
        assertNull(ServerQualityCache.get(profile,"wifi",setOf(HealthTarget.TELEGRAM),settings))
        assertNull(ServerQualityCache.get(profile.copy(uuid="changed"),"wifi",targets,settings))
    }
    @Test fun isolatedConfigForcesProbeIntoCandidateAndPreservesLiveCredentials() {
        try {
            CredentialManager.generate()
            val active=CredentialManager.speedProxy(false)!!
            for (protocol in listOf(Protocol.VLESS,Protocol.VMESS,Protocol.TROJAN,Protocol.SHADOWSOCKS,Protocol.HYSTERIA2)) {
                val profile=ServerProfile(protocol=protocol,address="vpn.example",uuid="00000000-0000-0000-0000-000000000001",password="test",hysteriaAuth="test")
                val root=JsonParser.parseString(CandidateProbeConfig.generate(profile,AppSettings(routingMode=RoutingMode.DIRECT),LocalSocks(12345,"probe-user","probe-pass"))).asJsonObject
                assertEquals(1,root.getAsJsonArray("inbounds").size())
                val inbound=root.getAsJsonArray("inbounds")[0].asJsonObject
                assertEquals("127.0.0.1",inbound["listen"].asString)
                assertEquals("health-in",inbound["tag"].asString)
                assertEquals(12345,inbound["port"].asInt)
                val route=root.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject
                assertTrue(route.getAsJsonArray("inboundTag").any{it.asString=="health-in"})
                assertEquals("proxy",route["outboundTag"].asString)
                assertTrue(CredentialManager.isCurrent(active))
                assertEquals(active.endpoint,CredentialManager.speedProxy(false)!!.endpoint)
            }
        } finally {CredentialManager.clear()}
    }
    @Test fun importedDetourIsPreservedAndProbeDoesNotFollowImportedDirectRules() {
        val graph="""{"outbounds":[{"tag":"remote","protocol":"vless","settings":{"vnext":[{"address":"vpn.example","port":443,"users":[{"id":"00000000-0000-0000-0000-000000000001","encryption":"none"}]}]},"proxySettings":{"tag":"upstream"}},{"tag":"upstream","protocol":"socks","settings":{"servers":[{"address":"exit.example","port":1080}]}}],"routing":{"rules":[{"type":"field","network":"tcp","outboundTag":"direct"}]}}"""
        val root=JsonParser.parseString(CandidateProbeConfig.generate(ServerProfile(address="vpn.example",xrayConfigJson=graph),AppSettings(),LocalSocks(12346,"u","p"))).asJsonObject
        val first=root.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject
        assertEquals("remote",first["outboundTag"].asString)
        assertTrue(root.getAsJsonArray("outbounds").any{it.asJsonObject["tag"].asString=="upstream"})
    }
}
