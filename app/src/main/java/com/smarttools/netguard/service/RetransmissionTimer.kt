package com.smarttools.netguard.service

/** Smoothed RTT, variance and backoff. Includes the peer's delayed-ACK budget. */
class RetransmissionTimer {
    private var smoothed = 0.0
    private var variance = 0.0
    var timeoutMs = 2500L
        private set
    fun acknowledge(rttMs: Long) {
        val sample = rttMs.coerceAtLeast(1).toDouble()
        if (smoothed == 0.0) { smoothed = sample; variance = sample / 2 }
        else { variance = 0.75 * variance + 0.25 * kotlin.math.abs(smoothed - sample); smoothed = 0.875 * smoothed + 0.125 * sample }
        timeoutMs = (smoothed + 4 * variance + 700).toLong().coerceIn(1500, 30_000)
    }
    fun backoff() { timeoutMs = (timeoutMs * 2).coerceAtMost(30_000) }
}
