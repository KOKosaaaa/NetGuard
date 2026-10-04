package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RouteDiagnosticsQaTest {
    private val site = SiteOrigin("example.test", 443, true)
    private val verified = SiteMeasurement(2, 123, "BODY", "VERIFIED", 65536, 200)
    private fun record(path: SitePath = SitePath.VPN) = SiteRouteRecord(site,"203.0.113.77",path,1_000_000L,
        SiteMeasurement(0,44,"TLS","RESET"),verified,SitePath.TLS_RECORD_SNI,verified.copy(outcome="VERIFIED_TWICE"))
    private fun RouteDiagnostics.text(redact: Boolean = false) = render(false,redact,true)

    @Test fun probesDoNotClaimApplicationTrafficAndPinnedPathDoesNotBecomeCandidate() {
        val d=RouteDiagnostics { 2_000_000L }; val token=d.begin(true)
        d.decision(token,record(SitePath.VPN),false)
        val text=d.text()
        assertTrue(text.contains("Verified strategy: TLS_RECORD_SNI; route including session pin: VPN"))
        assertTrue(text.contains("No application traffic observed yet."))
        assertFalse(text.contains("connections=1"))
        assertFalse(text.contains("203.0.113.77"))
    }
    @Test fun actualFallbackVpnDoesNotFabricateTlsTraffic() {
        val d=RouteDiagnostics(); val token=d.begin(true)
        d.decision(token,record(SitePath.TLS_RECORD_SNI),true)
        val flow=d.flow(token,site,"VPN","DPI_DISABLED")
        flow.progress(80,160)
        val text=d.text()
        assertTrue(text.contains("Probe: CACHE")); assertTrue(text.contains("Route reason: DPI_DISABLED"))
        assertTrue(text.contains("VPN: connections=1, active=1, ↑80 B ↓160 B"))
        assertFalse(text.contains("TLS_RECORD_SNI: connections="))
        flow.close(false)
        assertTrue(d.text().contains("VPN: connections=1, active=0, ↑80 B ↓160 B"))
    }
    @Test fun runningProbeIsNotOverwrittenByOldCacheRead() {
        val d=RouteDiagnostics(); val token=d.begin(true)
        d.phase(token,site,"CHECKING")
        d.sample(token,site,"DIRECT",SiteMeasurement(0,5,"TLS","TIMEOUT"))
        d.decision(token,record(),true)
        val text=d.text()
        assertTrue(text.contains("Probe: CHECKING")); assertFalse(text.contains("Probe: CACHE"))
        assertTrue(text.contains("DIRECT: TLS/TIMEOUT")); assertFalse(text.contains("Verified strategy:"))
    }
    @Test fun failedSecondAttemptIsNotVerifiedTwiceOrRealApplicationUse() {
        val d=RouteDiagnostics(); val token=d.begin(true)
        d.phase(token,site,"CHECKING"); d.catalog(token,site,"ATTEMPTED")
        d.sample(token,site,"TLS_RECORD_SNI:1",verified)
        d.sample(token,site,"TLS_RECORD_SNI:2",SiteMeasurement(0,50,"BODY","TRUNCATED",16384,200))
        assertTrue(d.text().contains("Bypass catalog: ATTEMPTED"))
        assertFalse(d.text().contains("Bypass catalog: VERIFIED_TWICE"))
        assertTrue(d.text().contains("No application traffic observed yet."))
    }
    @Test fun delayedOldNetworkProgressStaysHistoricalAndOldProbeCannotOverwriteNewNetwork() {
        val d=RouteDiagnostics(); val old=d.network(d.begin(true))
        val first=d.flow(old,site,"DIRECT","FIRST_OR_CACHE"); first.progress(11,22)
        val fresh=d.network(old)
        val second=d.flow(fresh,site,"VPN","FIRST_OR_CACHE"); second.progress(33,44)
        d.phase(old,site,"SHOULD_NOT_APPEAR"); first.progress(5,7); first.close(true)
        val text=d.text()
        assertTrue(text.contains("example.test:443 [previous network]"))
        assertTrue(text.contains("example.test:443 [current network]"))
        assertTrue(text.contains("DIRECT: connections=1, active=0, ↑16 B ↓29 B, failures=1"))
        assertTrue(text.contains("VPN: connections=1, active=1, ↑33 B ↓44 B"))
        assertFalse(text.contains("SHOULD_NOT_APPEAR")); second.close(false)
    }
    @Test fun lateCallbacksFromPriorSessionCannotChangeNewSession() {
        val d=RouteDiagnostics(); val old=d.begin(true); val flow=d.flow(old,site,"DIRECT","FIRST_OR_CACHE")
        val fresh=d.begin(false); d.flow(fresh,site,"VPN","ONLY_VPN").close(false)
        flow.progress(500,600); flow.close(true); d.sample(old,site,"old",verified); d.stop(old)
        val text=d.text()
        assertTrue(text.contains("ROUTER=ON; AUTO=false")); assertFalse(text.contains("↑500"))
        assertFalse(text.contains("DIRECT: connections=")); assertFalse(text.contains("old: BODY"))
    }
    @Test fun clearDoesNotResurrectOldActiveFlowAndCloseIsIdempotent() {
        val d=RouteDiagnostics(); val token=d.begin(true); val old=d.flow(token,site,"DIRECT","FIRST_OR_CACHE")
        old.progress(100,100); d.clear(); old.progress(100,100); old.close(true); old.close(true)
        assertTrue(d.text().contains("No observations yet."))
        val fresh=d.flow(token,site,"VPN","ONLY_VPN"); fresh.progress(8,9); fresh.close(false); fresh.close(true)
        assertTrue(d.text().contains("VPN: connections=1, active=0, ↑8 B ↓9 B, failures=0"))
        assertFalse(d.text().contains("active=-"))
    }
    @Test fun boundedRowsNeverReappearBecauseEvictedFlowReportsProgress() {
        val d=RouteDiagnostics(); val token=d.begin(true)
        val oldest=d.flow(token,SiteOrigin("first.test",443,true),"DIRECT","FIRST_OR_CACHE")
        repeat(RouteDiagnostics.MAX_ROWS+100) { d.phase(token,SiteOrigin("site$it.test",443,true),"CHECKED") }
        oldest.progress(9,9); oldest.close(true)
        val rows=d.text().lines().filter { it.endsWith("[current network]") }
        assertEquals(RouteDiagnostics.MAX_ROWS,rows.size)
        assertFalse(d.text().contains("first.test")); assertTrue(d.text().contains("site195.test"))
    }
    @Test fun redactedSnapshotDoesNotContainDomainIpTargetOrUrlPayload() {
        val d=RouteDiagnostics(); val token=d.begin(true)
        d.decision(token,record(),false)
        assertFalse(d.text(true).contains("example.test")); assertTrue(d.text(true).contains("site-"))
        assertFalse(d.text().contains("203.0.113.77"))
        d.phase(token,SiteOrigin("https://private.test/path?token=secret",443,true),"CHECKED")
        assertFalse(d.text().contains("token=secret")); assertFalse(d.text().contains("/path"))
    }
    @Test fun numericIpCannotBeRetainedAsValidatedHostname() {
        val d=RouteDiagnostics(); val token=d.begin(true)
        d.phase(token,SiteOrigin("8.8.8.8",443,true),"CHECKED")
        assertFalse("diagnostics promise no destination IP retention",d.text().contains("8.8.8.8"))
    }
    @Test fun concurrentProgressSnapshotsAndDuplicateCloseKeepExactTotals() {
        val d=RouteDiagnostics(); val token=d.begin(true); val flow=d.flow(token,site,"VPN","ONLY_VPN")
        val pool=Executors.newFixedThreadPool(5)
        try {
            val jobs=(1..4).map { pool.submit { repeat(500) { flow.progress(1,2) } } } +
                pool.submit { repeat(20) { assertTrue(d.text().contains("Actual application route:")) } }
            jobs.forEach { it.get(10,TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        flow.close(false); flow.close(false)
        assertTrue(d.text().contains("VPN: connections=1, active=0, ↑2000 B ↓4000 B, failures=0"))
    }
    @Test fun wbLogFloodCannotEvictSeparateDiagnosticEvidence() {
        val d=RouteDiagnostics(); val token=d.begin(true); d.decision(token,record(),false)
        repeat(10_000) { LogBuffer.add(LogBuffer.LogLevel.INFO,"WB transport diagnostic $it") }
        assertTrue(d.text().contains("Verified strategy: TLS_RECORD_SNI"))
        LogBuffer.clear()
    }
}
