package com.smarttools.netguard.service

import com.google.gson.JsonParser
import com.smarttools.netguard.core.ProfileParser
import com.smarttools.netguard.core.XrayConfigGenerator
import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.model.TransportType
import java.io.File
import java.net.Socket
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in integration check. Credentials stay in the private fixture directory. */
class LiveSubscriptionProbeTest {
    @Test fun serviceChecksTraverseRealTcpAndXhttpProfiles() {
        val executable = System.getenv("NETGUARD_LIVE_CORE")
        val fixture = System.getenv("NETGUARD_SUBSCRIPTION_FIXTURE")
        assumeTrue(executable != null && fixture != null)
        val profiles = ProfileParser.parseSubscription(File(fixture!!).readText()).profiles
        val selected = listOf(profiles.first { it.network == TransportType.TCP }, profiles.first { it.network == TransportType.SPLIT_HTTP })
        val results = mutableListOf<String>()
        var reachable = 0
        selected.forEachIndexed { index, profile ->
            val config = XrayConfigGenerator.generate(profile, AppSettings(bypassLan=false))
            val path = File(File(fixture).parentFile,"live-$index.json").apply { writeText(config.json) }
            val parsed = JsonParser.parseString(config.json).asJsonObject
            val port = parsed.getAsJsonArray("inbounds").first { it.asJsonObject["tag"].asString == "health-in" }.asJsonObject["port"].asInt
            val process = ProcessBuilder(executable!!, "run", "-config", path.absolutePath)
                .redirectErrorStream(true).redirectOutput(File(path.parentFile,"live-$index.log")).start()
            try {
                val deadline = System.nanoTime() + 8_000_000_000L
                while (runCatching { Socket("127.0.0.1",port).close() }.isFailure) {
                    check(process.isAlive && System.nanoTime()<deadline) { "Core did not start" }; Thread.sleep(50)
                }
                SocksServiceProbe(port,config.socksUser!!,config.socksPass!!).use { probe ->
                    HealthTarget.entries.forEach { target ->
                        val state = probe.check(target)
                        if (state != Reachability.FAILED) reachable++
                        results.add("${profile.network}: ${target.title} = $state")
                    }
                }
            } finally { process.destroy(); if (!process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly() }
        }
        File(File(fixture).parentFile,"live-results.txt").writeText(results.joinToString("\n"))
        assertTrue("No service reachable through either test profile; see private live-results.txt", reachable > 0)
    }
}
