package com.smarttools.netguard.service

import com.smarttools.netguard.model.ServerProfile
import kotlinx.coroutines.*

/** A bounded comparison of actual replies, not a scan of the entire subscription. */
internal object FastServerSelectionSearch {
    suspend fun choose(
        profiles: List<ServerProfile>,
        targetCount: Int,
        cached: (ServerProfile) -> ServerQuality?,
        probe: suspend (ServerProfile) -> ServerQuality?,
        totalMs: Long = 12_000,
        candidateMs: Long = 4_500,
        maxFresh: Int = 3,
    ): ServerProfile? {
        currentCoroutineContext().ensureActive()
        val results = profiles.mapNotNull { profile -> cached(profile)?.takeIf { it.responding > 0 }?.let { profile to it } }.toMutableList()
        fun winner() = results.minWithOrNull { a, b -> ServerQuality.bestFirst.compare(a.second, b.second) }
        val enough = minOf(2, targetCount.coerceAtLeast(1))
        winner()?.takeIf { it.second.available >= enough }?.let { return it.first }
        if (totalMs <= 0) return winner()?.first
        // Keep completed measurements if the overall deadline expires. Cancellation
        // by the user must still propagate and must never return a stale winner.
        withTimeoutOrNull(totalMs) {
            var tested = 0
            for (profile in profiles) {
                ensureActive()
                if (profile.protocol.usesRelay || cached(profile) != null) continue
                if (tested >= maxFresh) break
                tested++
                val quality = withTimeoutOrNull(candidateMs) { probe(profile) }
                if (quality != null && quality.responding > 0) results += profile to quality
                if (tested >= 2 && winner()?.second?.available?.let { it >= enough } == true) break
            }
        }
        currentCoroutineContext().ensureActive()
        return winner()?.first
    }
}
