package com.smarttools.netguard.service

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** A slow first room gets the full deadline. Pool collection starts only AFTER
 * a usable room exists. The owner must stop/cancel every unselected instance. */
internal suspend fun <T : Any> awaitRelayPool(
    attempts: List<Deferred<T?>>,
    firstTimeoutMs: Long,
    poolGraceMs: Long
): List<T> {
    val pending = attempts.toMutableList()
    val first = withTimeoutOrNull(firstTimeoutMs) {
        var ready: T? = null
        while (pending.isNotEmpty() && ready == null) {
            val (attempt, result) = select<Pair<Deferred<T?>, T?>> {
                pending.forEach { d -> d.onAwait { d to it } }
            }
            pending.remove(attempt)
            ready = result
        }
        ready
    } ?: return emptyList()
    if (pending.isNotEmpty() && poolGraceMs > 0) {
        withTimeoutOrNull(poolGraceMs) { pending.awaitAll() }
    }
    return listOf(first) + pending.filter { it.isCompleted }.mapNotNull { it.await() }
}
