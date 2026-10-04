package com.smarttools.netguard.util

import java.util.concurrent.ConcurrentHashMap

/** Failures retry shortly; domain/exit coordinates also expire rather than sticking forever. */
internal class GeoResultCache<T>(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val successTtlMs: Long = 6 * 60 * 60 * 1000L,
    private val failureTtlMs: Long = 60_000L
) {
    private data class Entry<T>(val value: T?, val expires: Long)
    private val entries = ConcurrentHashMap<String, Entry<T>>()
    fun lookup(key: String, load: () -> T?): T? {
        val now = nowMs()
        entries[key]?.takeIf { now < it.expires }?.let { return it.value }
        val value = load()
        if (entries.size > 512) entries.entries.removeAll { now >= it.value.expires }
        if (entries.size > 512) entries.clear()
        entries[key] = Entry(value, nowMs() + if(value == null) failureTtlMs else successTtlMs)
        return value
    }
}
