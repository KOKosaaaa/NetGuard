package com.smarttools.netguard.service

import java.util.Locale

/** Missing location-sensitive metadata is not evidence of a trusted network or an evil twin. */
internal object WifiSecurityPolicy {
    enum class Decision { TRUSTED, UNKNOWN, UNTRUSTED, DIFFERENT_BSSID }

    fun ssid(value: String?): String? = value?.removeSurrounding("\"")?.takeIf {
        it.isNotEmpty() && !it.equals("<unknown ssid>", true) && it != "0x"
    }

    fun bssid(value: String?): String? = value?.lowercase(Locale.ROOT)?.takeIf {
        it.matches(Regex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")) &&
            it !in setOf("02:00:00:00:00:00", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff")
    }

    fun classify(name: String?, address: String?, trusted: Collection<String>): Decision {
        val currentName = ssid(name) ?: return Decision.UNKNOWN
        val currentAddress = bssid(address) ?: return Decision.UNKNOWN
        var known = false
        for (entry in trusted) {
            val separator = entry.indexOf('|')
            val savedName = if (separator < 0) entry else entry.substring(0, separator)
            if (savedName != currentName) continue
            // Preserve old SSID-only entries, but never trust redacted current metadata.
            if (separator < 0) return Decision.TRUSTED
            val savedAddress = bssid(entry.substring(separator + 1)) ?: continue
            known = true
            if (savedAddress == currentAddress) return Decision.TRUSTED
        }
        return if (known) Decision.DIFFERENT_BSSID else Decision.UNTRUSTED
    }
}

/** An association owns its delayed work. Failed admission is retryable, not a successful dedupe. */
internal class WifiEventOwner {
    data class Token(val network: String, val generation: Long)
    private var generation = 0L
    private var token: Token? = null
    private var handled: String? = null
    private var retryAfter = 0L

    @Synchronized fun observe(network: String): Token {
        if (token?.network != network) {
            reset()
            token = Token(network, generation)
        }
        return requireNotNull(token)
    }
    @Synchronized fun current(candidate: Token) = token == candidate
    @Synchronized fun lost(network: String) { if (token?.network == network) reset() }
    @Synchronized fun reset() { generation++; token = null; handled = null; retryAfter = 0 }
    @Synchronized fun canAttempt(candidate: Token, key: String, now: Long) =
        current(candidate) && handled != key && now >= retryAfter
    @Synchronized fun record(candidate: Token, key: String, admitted: Boolean, now: Long) {
        if (!current(candidate)) return
        if (admitted) { handled = key; retryAfter = 0 } else retryAfter = now + 30_000
    }
}
