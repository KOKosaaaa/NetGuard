package com.smarttools.netguard.service

import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.model.AppSettings
import java.util.concurrent.ConcurrentHashMap

internal data class ServiceAnswer(val state: Reachability, val elapsedMs: Int)
internal data class ServerQuality(val answers: Map<HealthTarget, ServiceAnswer>, val pingMs: Int = -1) {
    val available get() = answers.values.count { it.state == Reachability.AVAILABLE }
    val responding get() = answers.values.count { it.state != Reachability.FAILED }
    val responseMs get() = answers.values.filter { it.state != Reachability.FAILED }.map { it.elapsedMs }.sorted()
        .let { if (it.isEmpty()) Int.MAX_VALUE else it[it.size / 2] }
    // Availability dominates latency. HTTP restrictions never equal full service access.
    val latencyCost: Long get() = responseMs.toLong() * 3 + (if (pingMs >= 0) pingMs else responseMs).toLong()
    companion object {
        val bestFirst = compareByDescending<ServerQuality> { it.available }
            .thenByDescending { it.responding }.thenBy { it.latencyCost }
    }
}

/** Short-lived, network/settings/profile-specific observations, never a historical TCP ping winner. */
internal object ServerQualityCache {
    private data class Key(val profile: ServerProfile, val network: String, val targets: Set<HealthTarget>, val settings: AppSettings)
    private data class Entry(val quality: ServerQuality, val at: Long)
    private val values = ConcurrentHashMap<Key, Entry>()
    fun put(profile: ServerProfile, network: String, targets: Set<HealthTarget>, quality: ServerQuality, settings: AppSettings) {
        if (values.size >= 128) values.clear()
        val key = Key(profile.copy(lastPingMs = -1, isSelected = false), network, targets.toSet(), settings)
        values.compute(key) { _, old ->
            // A quick partial sample cannot erase a richer fresh health round.
            // Do not merge/re-date old answers: they retain their original TTL.
            if (old != null && System.nanoTime() - old.at in 0..120_000_000_000L &&
                old.quality.answers.size > quality.answers.size && old.quality.answers.keys.containsAll(quality.answers.keys) &&
                quality.answers.all { (target, answer) -> old.quality.answers[target]?.state == answer.state }) old
            else Entry(quality, System.nanoTime())
        }
    }
    fun get(profile: ServerProfile, network: String, targets: Set<HealthTarget>, settings: AppSettings): ServerQuality? {
        val key = Key(profile.copy(lastPingMs = -1, isSelected = false), network, targets, settings)
        val entry = values[key] ?: return null
        if (System.nanoTime() - entry.at !in 0..120_000_000_000L) { values.remove(key, entry); return null }
        return entry.quality
    }
}
