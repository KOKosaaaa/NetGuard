package com.smarttools.netguard.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Central SSRF / private-address filter.
 *
 * The previous spot checks (textual `startsWith("10.")` etc.) were defeated by
 * hex/octal IPv4 literals (`0x7f.0.0.1`, `0177.0.0.1`) and by IPv6-mapped forms
 * written without dotted decimal (`::ffff:c0a8:0101` == `192.168.1.1`). This
 * validator relies on `InetAddress.getByName` to perform the numeric resolution
 * once, then inspects the resulting bytes — so alternative textual
 * representations of the same address can't slip past.
 *
 * Callers should invoke [requirePublicAddress] before any network egress is
 * bound to a host value taken from untrusted input (subscription URLs, profile
 * URIs, DNS lookups inside OkHttp).
 */
object AddressValidator {

    /**
     * Returns true if [host] is a textual IP literal that points at a
     * private, reserved, CGNAT, or other non-routable address.
     *
     * We deliberately *do not* perform DNS resolution here — this method is
     * called from the profile / URI parser, which may run on the main thread
     * and must not leak the imported hostname to a DNS server before the
     * user has even decided to connect. The DNS-time defence lives in
     * [SubscriptionRepository.safeDns], which re-checks each resolved IP via
     * [isPrivateOrReserved] at connect time.
     *
     * Hostnames that do not look like IP literals return false from this
     * call; they are expected to be validated again by the safe-DNS layer
     * when an actual network connection is made.
     */
    fun isPrivateOrReserved(host: String): Boolean {
        val cleaned = host.removeSurrounding("[", "]")
        val literal = parseLiteral(cleaned) ?: return false
        return isPrivateOrReserved(literal)
    }

    /**
     * Accept only purely numeric IPv4/IPv6 textual forms. We route through
     * [InetAddress.getByName], but only after confirming the input cannot
     * trigger DNS (no letters other than those valid in hex IPv6 groups and
     * no top-level domain shape). `InetAddress.getByName` is happy to parse
     * hex-and-octal IPv4 like `0x7f.0.0.1` and `0177.0.0.1`, which is the
     * whole point — textual matches on `"127."` would miss those.
     */
    private fun parseLiteral(host: String): InetAddress? {
        if (host.isEmpty()) return null
        val looksLikeIpv6 = host.contains(':')
        val looksLikeIpv4 = !looksLikeIpv6 && host.all {
            it.isDigit() || it == '.' || it == 'x' || it == 'X'
        } && host.contains('.')
        if (!looksLikeIpv4 && !looksLikeIpv6) return null
        return try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
    }

    fun isPrivateOrReserved(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) return true

        return when (addr) {
            is Inet4Address -> isReservedIpv4(addr)
            is Inet6Address -> isReservedIpv6(addr)
            else -> false
        }
    }

    /**
     * Convenience wrapper used at the point of parsing. Throws
     * [IllegalArgumentException] so it fits existing ProfileParser error flows,
     * callers that expect `IOException` can map the exception themselves.
     */
    fun requirePublicAddress(host: String) {
        if (isPrivateOrReserved(host)) {
            throw IllegalArgumentException("Private/reserved address not allowed: $host")
        }
    }

    private fun isReservedIpv4(addr: Inet4Address): Boolean {
        val bytes = addr.address
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff

        // 100.64.0.0/10 — CGNAT. Widely used by RU mobile carriers; a stolen
        // device would happily tunnel the attacker straight into the carrier
        // internal network if we let CGNAT addresses through.
        if (b0 == 100 && b1 in 64..127) return true
        // 224.0.0.0/4 — multicast (already covered by isMulticastAddress, kept
        // here for explicit documentation).
        if (b0 in 224..239) return true
        // 240.0.0.0/4 — reserved (includes 255.255.255.255 broadcast).
        if (b0 >= 240) return true
        // Everything below is redundant with the built-in checks above, but is
        // spelled out so the intent survives future platform changes:
        // 0.0.0.0/8 (this network)
        if (b0 == 0) return true
        // 127.0.0.0/8 (loopback) — `isLoopbackAddress` only matches 127.0.0.1
        // on some JVM builds; enforce the full /8 here.
        if (b0 == 127) return true
        return false
    }

    private fun isReservedIpv6(addr: Inet6Address): Boolean {
        val bytes = addr.address
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff

        // fec0::/10 — deprecated site-local, still filtered out defensively
        // (isSiteLocalAddress in java.net only covers IPv4 private ranges).
        if (b0 == 0xfe && (b1 and 0xc0) == 0xc0) return true
        // fc00::/7 — unique local. isSiteLocalAddress does NOT cover this range
        // despite the intuitive naming.
        if ((b0 and 0xfe) == 0xfc) return true
        // IPv4-mapped (::ffff:0:0/96) — re-check the embedded v4 address so
        // forms like ::ffff:c0a8:0101 (192.168.1.1 in hex) still get blocked.
        if (isIpv4Mapped(bytes)) {
            val mapped = Inet4Address.getByAddress(bytes.copyOfRange(12, 16)) as Inet4Address
            if (isPrivateOrReserved(mapped)) return true
        }
        // IPv4-compatible (::0:0/96) is deprecated but we block the same way.
        if (isIpv4Compatible(bytes)) {
            val compat = Inet4Address.getByAddress(bytes.copyOfRange(12, 16)) as Inet4Address
            if (isPrivateOrReserved(compat)) return true
        }
        return false
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        for (i in 0..9) if (bytes[i].toInt() != 0) return false
        return bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
    }

    private fun isIpv4Compatible(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        for (i in 0..11) if (bytes[i].toInt() != 0) return false
        // Distinguish from the actual unspecified address (::) by requiring
        // at least one non-zero byte in the trailing v4 part.
        return bytes.copyOfRange(12, 16).any { it.toInt() != 0 }
    }
}
