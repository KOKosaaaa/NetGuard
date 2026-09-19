package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test

class TrafficAndHealthTest {
    @Test fun ratesUseElapsedTimeAndSurviveNativeRestart() {
        val counter = TrafficRateCounter()
        counter.sample(100, 50, 0)
        val sample = counter.sample(4100, 2050, 2_000_000_000)
        assertEquals(2000L, sample.rxPerSecond)
        assertEquals(1000L, sample.txPerSecond)
        val reset = counter.sample(600, 300, 5_000_000_000)
        assertEquals(4600L, reset.rxBytes)
        assertEquals(200L, reset.rxPerSecond)
    }
    @Test fun isolatedLossAndChallengesDoNotSwitch() {
        val p = ServiceHealthPolicy()
        // A permanently blocked X/Reddit must not kick a working GPT Remote
        // session off the VPN, even after many unsuccessful probe rounds.
        repeat(20) {
            assertTrue(p.record(mapOf(HealthTarget.X to Reachability.FAILED,
                HealthTarget.REDDIT to Reachability.FAILED,
                HealthTarget.CHATGPT to Reachability.AVAILABLE), it * 120_000L).isEmpty())
        }
        repeat(5) { assertTrue(p.record(mapOf(HealthTarget.CHATGPT to Reachability.FAILED), it * 120_000L).isEmpty()) }
        assertTrue(p.record(mapOf(HealthTarget.CHATGPT to Reachability.LIMITED)).isEmpty())
    }

    @Test fun onlySustainedTotalLossTriggersSwitchAndRecoveryResetsIt() {
        val p = ServiceHealthPolicy()
        val outage = HealthTarget.entries.associateWith { Reachability.FAILED }
        assertTrue(p.record(outage, 0).isEmpty())
        assertTrue(p.record(outage, 45_000).isEmpty())
        assertEquals(HealthTarget.defaults, p.record(outage, 90_000).map { it.name }.toSet())
        assertTrue(p.record(outage + (HealthTarget.CHATGPT to Reachability.LIMITED), 120_000).isEmpty())
        assertTrue(p.record(outage, 150_000).isEmpty())
        assertTrue(p.record(outage, 180_000).isEmpty())
    }

    @Test fun realTrafficKeepsSessionsAliveEvenIfAllProbesFail() {
        val p = ServiceHealthPolicy()
        val outage = HealthTarget.entries.associateWith { Reachability.FAILED }
        p.record(outage, 0); p.record(outage, 45_000)
        assertTrue(p.record(outage, 90_000, receivingTraffic = true).isEmpty())
        assertTrue(p.record(outage, 120_000).isEmpty())
        assertTrue(p.record(outage, 150_000).isEmpty())
        p.reset() // underlying network changed
        assertTrue(p.record(outage, 300_000).isEmpty())
    }
    @Test fun unrelatedServicesDoNotShareFailureStreaks() {
        val p = ServiceHealthPolicy()
        p.record(mapOf(HealthTarget.X to Reachability.FAILED))
        assertTrue(p.record(mapOf(HealthTarget.REDDIT to Reachability.FAILED)).isEmpty())
        assertTrue(p.record(mapOf(HealthTarget.X to Reachability.FAILED)).isEmpty())
        assertEquals(6, HealthTarget.defaults.size)
    }
    @Test fun distinguishesNetworkFailureFromAntiBotChallenge() {
        assertEquals(Reachability.LIMITED, ServiceHealthPolicy.httpStatus(403, "Cloudflare challenge"))
        assertEquals(Reachability.LIMITED, ServiceHealthPolicy.httpStatus(429))
        assertEquals(Reachability.FAILED, ServiceHealthPolicy.httpStatus(403, "unsupported_country_region_territory"))
        assertEquals(Reachability.AVAILABLE, ServiceHealthPolicy.httpStatus(204))
        assertEquals(Reachability.FAILED, ServiceHealthPolicy.httpStatus(502))
    }
    @Test fun retryTimerBacksOffAndAcceptsMeasuredRtt() {
        val timer = RetransmissionTimer()
        assertTrue(timer.timeoutMs > 700)
        val initial = timer.timeoutMs
        timer.backoff(); assertEquals(initial * 2, timer.timeoutMs)
        repeat(20) { timer.backoff() }; assertEquals(30_000L, timer.timeoutMs)
        timer.acknowledge(800); assertTrue(timer.timeoutMs in 1500..5000)
    }
}
