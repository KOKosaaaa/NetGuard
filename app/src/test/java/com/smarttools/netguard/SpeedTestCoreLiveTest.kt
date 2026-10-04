package com.smarttools.netguard

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.core.XrayConfigGenerator
import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.model.TransportType
import com.smarttools.netguard.util.SpeedTestEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Explicit opt-in only. Public output contains no profile names, destinations or credentials. */
class SpeedTestCoreLiveTest {
    private fun category(message:String):String {
        val stage=message.substringBefore(':').takeIf {it in listOf("latency","download","upload")} ?: "engine"
        val http=Regex("HTTP ([1-5][0-9]{2})").find(message)?.groupValues?.get(1)
        val type=Regex("\\b([A-Za-z]+Exception)\\b").find(message)?.groupValues?.get(1)
        val detail=listOf("Wrong payload size","Incomplete payload","Oversized payload","Unexpected test content",
            "Encoded test payload","Latency response not empty","Incomplete upload","Unexpected upload page",
            "Oversized upload acknowledgement","SOCKS authentication","SOCKS method","SOCKS destination",
            "deadline reached; preserving completed transfers").firstOrNull {message.contains(it)}
        return listOfNotNull(stage,http?.let{"HTTP $it"},type,detail).joinToString(": ")
    }

    @Test(timeout=180_000) fun generatedProfilesMeasureThroughActualAuthenticatedHealthInbound()=runBlocking {
        val exe=System.getenv("NETGUARD_SPEED_CORE")
        val fixture=System.getenv("NETGUARD_SPEED_FIXTURE")
        assumeTrue("Requires explicit owned private subscription and local Xray",!exe.isNullOrBlank() && !fixture.isNullOrBlank())
        check(File(exe!!).isFile) {"Configured Xray executable missing"}
        val input=File(fixture!!)
        check(input.isFile) {"Configured private subscription missing"}
        val privateDir=input.canonicalFile.parentFile
        check(privateDir.path.replace('\\','/').contains("/.codex/private/")) {"Fixtures/configs must remain in private directory"}
        val profiles=try {ProfileParser.parseSubscription(input.readText()).profiles}
            catch(_:Exception){error("Private subscription parse failed (details withheld)")}
        val preferred=listOf(TransportType.GRPC,TransportType.SPLIT_HTTP,TransportType.TCP)
        val selected=preferred.mapNotNull {transport->profiles.firstOrNull{it.network==transport && !it.protocol.usesRelay}}.take(2)
        check(selected.size==2) {"Need two distinct GRPC/XHTTP/TCP profiles"}
        val evidence=mutableListOf<Map<String,Any?>>()
        val failed=mutableListOf<String>()
        selected.forEachIndexed {index,profile->
            val label="profile-$index/${profile.network.name}"
            val trace=mutableListOf<String>()
            val started=System.nanoTime()
            var process:Process?=null
            var configFile:File?=null
            var result:SpeedTestEngine.Result?=null
            var failure:String?=null
            try {
                val config=XrayConfigGenerator.generate(profile,AppSettings(bypassLan=false))
                val proxy=checkNotNull(CredentialManager.speedProxy(false)) {"Generated credentials unavailable"}
                val parsed=JsonParser.parseString(config.json).asJsonObject
                val health=parsed.getAsJsonArray("inbounds").first {it.asJsonObject["tag"].asString=="health-in"}.asJsonObject
                val account=health.getAsJsonObject("settings").getAsJsonArray("accounts")[0].asJsonObject
                check(proxy.endpoint.port==health["port"].asInt && proxy.endpoint.user==account["user"].asString && proxy.endpoint.password==account["pass"].asString) {"Credential snapshot differs from generated health inbound"}
                configFile=File.createTempFile("speed-core-$index-",".json",privateDir).apply {writeText(config.json)}
                process=ProcessBuilder(exe,"run","-config",configFile!!.absolutePath)
                    .redirectErrorStream(true).redirectOutput(File(privateDir,"speed-core-$index-private.log")).start()
                val deadline=System.nanoTime()+8_000_000_000L
                while(runCatching {Socket("127.0.0.1",proxy.endpoint.port).use{}}.isFailure) {
                    check(process!!.isAlive && System.nanoTime()<deadline) {"Xray did not bind health inbound"}
                    Thread.sleep(40)
                }
                println("CORE_LIVE $label health-auth-snapshot=exact ready=true")
                val measurement = SpeedTestEngine.productionConfig().let { c ->
                    if (System.getenv("NETGUARD_SPEED_FORCE_FALLBACK") == "1") c.copy(
                        downloadUrl = "https://speed.cloudflare.com/netguard-nonexistent-measurement",
                        uploadUrl = "https://speed.cloudflare.com/netguard-nonexistent-measurement") else c
                }
                result=SpeedTestEngine(proxy.endpoint, measurement,log={message->
                    val safe=category(message);trace.add(safe);println("CORE_LIVE $label $safe")
                }).run {stage->trace.add("stage=$stage");println("CORE_LIVE $label stage=$stage")}
                check(CredentialManager.isCurrent(proxy)) {"Credential session changed during measurement"}
                println("CORE_LIVE $label downloadMbps=${result!!.downloadMbps} uploadMbps=${result!!.uploadMbps} httpLatencyMs=${result!!.latencyMs} timedOut=${result!!.timedOut}")
                if(result!!.downloadMbps<=0 || result!!.uploadMbps<=0 || result!!.latencyMs<0) failure="Incomplete measurement"
            } catch(t:Exception) {
                failure=t.javaClass.simpleName
                println("CORE_LIVE $label failure=$failure")
            } finally {
                process?.let {p->p.destroy();if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroyForcibly();check(p.waitFor(3,TimeUnit.SECONDS)){"Owned Xray did not exit"}}}
                configFile?.delete()
                CredentialManager.clear()
                val elapsed=(System.nanoTime()-started)/1_000_000L
                evidence.add(mapOf("profileIndex" to index,"transport" to profile.network.name,"elapsedMs" to elapsed,
                    "result" to result,"failureCategory" to failure,"trace" to trace.toList(),"ownedCoreExited" to (process?.isAlive!=true)))
                File(privateDir,"speed-core-safe-results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(evidence))
            }
            failure?.let {failed.add("$label: $it")}
        }
        assertTrue("Actual core measurements failed: ${failed.joinToString()}",failed.isEmpty())
    }
}
