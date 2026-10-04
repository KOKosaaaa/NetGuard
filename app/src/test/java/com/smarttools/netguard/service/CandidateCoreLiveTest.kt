package com.smarttools.netguard.service

import com.google.gson.GsonBuilder
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.model.TransportType
import com.smarttools.netguard.util.RandomPort
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

class CandidateCoreLiveTest {
    @Test(timeout=150_000) fun actualCandidateCoreChecksServiceRepliesWithoutChangingActiveCredentials() = runBlocking {
        val executable=System.getenv("NETGUARD_SPEED_CORE")
        val fixture=System.getenv("NETGUARD_SPEED_FIXTURE")
        assumeTrue(!executable.isNullOrBlank() && !fixture.isNullOrBlank())
        val input=File(fixture!!)
        require(input.canonicalPath.replace('\\','/').contains("/.codex/private/"))
        val profiles=try {ProfileParser.parseSubscription(input.readText()).profiles}
            catch(_:Exception){error("Private fixture parse failed")}
        val selected=listOf(TransportType.GRPC,TransportType.SPLIT_HTTP,TransportType.TCP)
            .mapNotNull {transport->profiles.firstOrNull{it.network==transport && !it.protocol.usesRelay}}.take(2)
        assertEquals(2,selected.size)
        val evidence=mutableListOf<Map<String,Any>>()
        CredentialManager.generate()
        val active=CredentialManager.speedProxy(false)!!
        try {
            for((index,profile) in selected.withIndex()) {
                val endpoint=LocalSocks(RandomPort.getAvailable(),"probe-test-user","probe-test-password")
                val config=File(input.parentFile,"candidate-$index-private.json")
                var core:Process?=null
                try {
                    config.writeText(CandidateProbeConfig.generate(profile,AppSettings(bypassLan=false),endpoint))
                    core=ProcessBuilder(executable!!,"run","-c",config.absolutePath)
                        .directory(File(executable).parentFile).redirectErrorStream(true)
                        .redirectOutput(File(input.parentFile,"candidate-$index-private.log")).start()
                    withTimeout(8000) {
                        while(true) {
                            check(core.isAlive){"Candidate core exited"}
                            if(runCatching {Socket("127.0.0.1",endpoint.port).use{} }.isSuccess)break
                            delay(50)
                        }
                    }
                    val startedAt=System.nanoTime()
                    val result=ServerQualitySelector.measure(endpoint,HealthTarget.entries.toSet(),3000)
                    val durationMs=(System.nanoTime()-startedAt)/1_000_000
                    assertTrue("Fast service measurement exceeded cleanup allowance",durationMs<5500)
                    assertTrue(CredentialManager.isCurrent(active))
                    assertEquals(active.endpoint,CredentialManager.speedProxy(false)!!.endpoint)
                    val controlStart=System.nanoTime()
                    val control=ServerQualitySelector.measure(endpoint,HealthTarget.entries.toSet(),10_000)
                    val controlMs=(System.nanoTime()-controlStart)/1_000_000
                    val row=mapOf("index" to index,"transport" to profile.network.name,"available" to result.available,
                        "responding" to result.responding,"medianResponseMs" to result.responseMs,"measurementMs" to durationMs,
                        "services" to result.answers.mapKeys{it.key.name},"control10s" to mapOf("available" to control.available,
                            "responding" to control.responding,"measurementMs" to controlMs,"services" to control.answers.mapKeys{it.key.name}))
                    evidence.add(row)
                    println("CANDIDATE_CORE transport=${profile.network.name} available=${result.available} responding=${result.responding} median=${result.responseMs}ms")
                    assertTrue("No validated service response through candidate",result.responding>0)
                } finally {
                    core?.let{it.destroy();if(!it.waitFor(3,TimeUnit.SECONDS)){it.destroyForcibly();check(it.waitFor(3,TimeUnit.SECONDS))}}
                    config.delete()
                    File(input.parentFile,"candidate-quality-safe.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(evidence))
                }
            }
        } finally {CredentialManager.clear()}
    }
}
