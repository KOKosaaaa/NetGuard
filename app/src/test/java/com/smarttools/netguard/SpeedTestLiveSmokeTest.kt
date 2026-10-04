package com.smarttools.netguard

import com.smarttools.netguard.service.LocalSocks
import com.smarttools.netguard.util.SpeedTestEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in manual test against an isolated real WB room. Never runs by default. */
class SpeedTestLiveSmokeTest {
    @Test fun completedTransfersThroughRealWb() = runBlocking {
        val port = System.getenv("NETGUARD_SPEED_WB_PORT")?.toIntOrNull()
        assumeTrue("Requires an owned isolated WB fixture", port != null)
        val result = SpeedTestEngine(LocalSocks(port!!, "", ""), log = { println("LIVE $it") })
            .run { println("LIVE stage=$it") }
        println("LIVE downloadMbps=${result.downloadMbps} uploadMbps=${result.uploadMbps} httpLatencyMs=${result.latencyMs}")
        assertTrue("Download did not complete", result.downloadMbps > 0)
        assertTrue("Upload not acknowledged", result.uploadMbps > 0)
        assertTrue("Latency not measured", result.latencyMs >= 0)
    }
}
