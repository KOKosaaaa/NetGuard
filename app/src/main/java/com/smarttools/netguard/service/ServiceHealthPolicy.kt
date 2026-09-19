package com.smarttools.netguard.service

enum class HealthTarget(val title: String, val host: String, val path: String = "/") {
    TELEGRAM("Telegram", "149.154.167.51"),
    YOUTUBE("YouTube", "www.youtube.com", "/generate_204"),
    X("X", "x.com"),
    DISCORD("Discord", "discord.com", "/api/v10/gateway"),
    CHATGPT("ChatGPT", "chatgpt.com"),
    REDDIT("Reddit", "www.reddit.com");
    companion object { val defaults = entries.map { it.name }.toSet() }
}

enum class Reachability { AVAILABLE, LIMITED, FAILED }

/** A partial outage must not tear down working calls, streams or Remote sessions. */
class ServiceHealthPolicy {
    private var targets = emptySet<HealthTarget>()
    private var firstFailureAt: Long? = null
    private var failedRounds = 0
    fun reset() { targets = emptySet(); firstFailureAt = null; failedRounds = 0 }

    fun record(
        results: Map<HealthTarget, Reachability>,
        nowMs: Long = System.nanoTime() / 1_000_000,
        receivingTraffic: Boolean = false,
    ): Set<HealthTarget> {
        if (targets != results.keys) { reset(); targets = results.keys.toSet() }
        // Require independent destinations. One blocked service (or a CAPTCHA)
        // is not evidence that every connection through this server is broken.
        if (results.size < 2 || receivingTraffic || results.values.any { it != Reachability.FAILED }) {
            firstFailureAt = null; failedRounds = 0
            return emptySet()
        }
        if (firstFailureAt == null || nowMs < firstFailureAt!!) firstFailureAt = nowMs
        failedRounds++
        return if (failedRounds >= 3 && nowMs - firstFailureAt!! >= 90_000) targets else emptySet()
    }
    companion object {
        fun httpStatus(code: Int, body: String = ""): Reachability = when {
            code == 403 && (body.contains("unsupported_country", true) || body.contains("country_not_supported", true)) -> Reachability.FAILED
            code in 200..399 -> Reachability.AVAILABLE
            code in setOf(401, 403, 404, 405, 429) -> Reachability.LIMITED
            else -> Reachability.FAILED
        }
    }
}
