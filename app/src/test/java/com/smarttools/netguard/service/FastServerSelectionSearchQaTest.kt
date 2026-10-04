package com.smarttools.netguard.service

import com.smarttools.netguard.model.Protocol
import com.smarttools.netguard.model.ServerProfile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class FastServerSelectionSearchQaTest {
    private val profiles = (1L..8L).map { ServerProfile(id=it, name="QA $it", address="qa$it.invalid") }
    private fun quality(available:Int, limited:Int=0, ms:Int=100, ping:Int=50) = ServerQuality(
        HealthTarget.entries.take(available+limited).mapIndexed { i,t ->
            t to ServiceAnswer(if(i<available)Reachability.AVAILABLE else Reachability.LIMITED,ms)
        }.toMap(),ping)

    @Test fun cachedShortcutRanksAllObservationsAndNeverStartsNativeProbe() = runBlocking {
        val cache=mapOf(1L to quality(2,ms=5),2L to quality(6,ms=900),3L to quality(0,6,1))
        val chosen=FastServerSelectionSearch.choose(profiles,6,{cache[it.id]}, {error("Cached winner must avoid fresh startup")})
        assertEquals(2L,chosen?.id)
    }

    @Test fun actualAvailabilityBeatsFastRestrictionsAndComparisonStopsAfterTwo() = runBlocking {
        val called=mutableListOf<Long>()
        val chosen=FastServerSelectionSearch.choose(profiles,6,{null},{
            called+=it.id
            if(it.id==1L)quality(0,6,1,1) else quality(2,ms=700,ping=200)
        })
        assertEquals(2L,chosen?.id)
        assertEquals(listOf(1L,2L),called)
    }

    @Test fun overallDeadlineRetainsOnlyCompletedWinnerAndClosesInFlightProbe() = runBlocking {
        val calls=mutableListOf<Long>();var cancelled=false
        val start=System.nanoTime()
        val chosen=FastServerSelectionSearch.choose(profiles,6,{null},{
            calls+=it.id
            if(it.id==1L){delay(10);quality(1)} else try {awaitCancellation()} finally {cancelled=true}
        },totalMs=180,candidateMs=2000)
        assertEquals(1L,chosen?.id)
        assertEquals(listOf(1L,2L),calls)
        assertTrue(cancelled)
        assertTrue("Overall deadline ignored",(System.nanoTime()-start)/1_000_000<1500)
    }

    @Test fun candidateTimeoutDoesNotPreventLaterWorkingCandidate() = runBlocking {
        var firstClosed=false;val called=mutableListOf<Long>()
        val chosen=FastServerSelectionSearch.choose(profiles,6,{null},{
            called+=it.id
            if(it.id==1L)try {awaitCancellation()} finally {firstClosed=true} else quality(2)
        },totalMs=1500,candidateMs=100)
        assertEquals(2L,chosen?.id)
        assertTrue(firstClosed)
        assertEquals(listOf(1L,2L),called)
    }

    @Test fun explicitCancellationAfterPartialSuccessCannotPublishWinner() = runBlocking {
        val pending=CompletableDeferred<Unit>();var closed=false;var returned=false
        val job=launch {
            FastServerSelectionSearch.choose(profiles,6,{null},{
                if(it.id==1L)quality(1) else try {pending.complete(Unit);awaitCancellation()} finally {closed=true}
            },totalMs=2000,candidateMs=1000)
            returned=true
        }
        withTimeout(1000){pending.await()}
        job.cancelAndJoin()
        assertTrue(closed)
        assertFalse("Cancelled selection published earlier partial winner",returned)
    }

    @Test fun parentTimeoutIsNotMistakenForOwnPartialResultDeadline() = runBlocking {
        var closed=false;var returned=false
        try {
            withTimeout(120) {
                FastServerSelectionSearch.choose(profiles,6,{null},{
                    if(it.id==1L)quality(1) else try {awaitCancellation()} finally {closed=true}
                },totalMs=2000,candidateMs=1000)
                returned=true
            }
            fail("Parent timeout disappeared")
        } catch(_:TimeoutCancellationException) { }
        assertTrue(closed);assertFalse(returned)
    }

    @Test fun freshLimitIncludesFailuresAndNeverSpeculativelyJoinsConference() = runBlocking {
        val relay=ServerProfile(id=100,protocol=Protocol.WBSTREAM,address="qa.invalid")
        val called=mutableListOf<Long>()
        val chosen=FastServerSelectionSearch.choose(listOf(relay)+profiles,6,{null},{
            assertFalse(it.protocol.usesRelay);called+=it.id;ServerQuality(emptyMap())
        },maxFresh=3)
        assertNull(chosen);assertEquals(listOf(1L,2L,3L),called)
    }

    @Test fun alreadyCancelledCallerCannotTakeCachedShortcut() = runBlocking {
        val returned=AtomicBoolean(false)
        val job=launch(start=CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            FastServerSelectionSearch.choose(profiles,6,{quality(6)},{error("Unexpected fresh probe")})
            returned.set(true)
        }
        job.join()
        assertFalse("Cached shortcut bypassed cancellation",returned.get())
    }

    @Test fun cachedPartialStillCompetesAfterFailedFreshCandidates() = runBlocking {
        val chosen=FastServerSelectionSearch.choose(profiles,6,{if(it.id==1L)quality(1,ms=800) else null},
            {quality(0,limited=6,ms=1,ping=1)},maxFresh=3)
        assertEquals(1L,chosen?.id)
    }

    @Test fun quickPartialCannotEraseRicherFreshObservationButCompleteFailureCan() {
        val profile=ServerProfile(id=980041,address="qa-cache-rich.invalid")
        val settings=com.smarttools.netguard.model.AppSettings()
        val targets=HealthTarget.entries.toSet()
        val full=quality(6,ms=400)
        ServerQualityCache.put(profile,"qa-fast-cache",targets,full,settings)
        ServerQualityCache.put(profile,"qa-fast-cache",targets,quality(2,ms=5),settings)
        assertEquals(full,ServerQualityCache.get(profile,"qa-fast-cache",targets,settings))
        val failed=ServerQuality(targets.associateWith{ServiceAnswer(Reachability.FAILED,800)})
        ServerQualityCache.put(profile,"qa-fast-cache",targets,failed,settings)
        assertEquals("A complete new outage must not retain the old healthy cache",failed,
            ServerQualityCache.get(profile,"qa-fast-cache",targets,settings))
    }

    @Test fun newerContradictingPartialInvalidatesRicherStaleStatesWithoutInventingUnknownAnswers() {
        val profile=ServerProfile(id=980042,address="qa-cache-contradiction.invalid")
        val settings=com.smarttools.netguard.model.AppSettings()
        val targets=HealthTarget.entries.toSet()
        val failed=ServerQuality(targets.associateWith{ServiceAnswer(Reachability.FAILED,800)})
        val recovered=quality(2,ms=100)
        ServerQualityCache.put(profile,"qa-contradiction",targets,failed,settings)
        ServerQualityCache.put(profile,"qa-contradiction",targets,recovered,settings)
        assertEquals(recovered,ServerQualityCache.get(profile,"qa-contradiction",targets,settings))
        ServerQualityCache.put(profile,"qa-contradiction",targets,quality(6),settings)
        val newFailure=ServerQuality(mapOf(HealthTarget.TELEGRAM to ServiceAnswer(Reachability.FAILED,900)))
        ServerQualityCache.put(profile,"qa-contradiction",targets,newFailure,settings)
        val observed=ServerQualityCache.get(profile,"qa-contradiction",targets,settings)!!
        assertEquals(newFailure,observed)
        assertEquals("Unmeasured services must remain unknown after replacing contradicted evidence",setOf(HealthTarget.TELEGRAM),observed.answers.keys)
    }
}
