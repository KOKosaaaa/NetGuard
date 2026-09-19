package com.smarttools.netguard.service

import com.smarttools.netguard.util.AddressValidator
import java.net.InetAddress

/** Manual exclusions take precedence; unknown geodata rules stay with Xray. */
internal class SiteBypassRules(domains: String, ips: String) {
    private val domainRules = domains.lines().map { it.trim() }.filter { it.isNotEmpty() }
    private val ipRules = ips.lines().map { it.trim() }.filter { it.isNotEmpty() }
    private val unknown = domainRules.any { it.startsWith("geosite:") || it.startsWith("ext:") } ||
        ipRules.any { it.startsWith("geoip:") || it.startsWith("ext:") }
    fun allows(destination: SocksDestination, origin: SiteOrigin): Boolean {
        if (unknown || AddressValidator.isPrivateOrReserved(destination.host)) return false
        if (domainRules.any { rule ->
            when {
                rule.startsWith("full:") -> origin.host.equals(rule.substringAfter(':'), true)
                rule.startsWith("domain:") -> rule.substringAfter(':').lowercase().let { origin.host == it || origin.host.endsWith(".$it") }
                rule.startsWith("regexp:") -> runCatching { Regex(rule.substringAfter(':')).containsMatchIn(origin.host) }.getOrDefault(true)
                else -> origin.host.contains(rule, true)
            }
        }) return false
        return ipRules.none { rule -> runCatching {
            val base = rule.substringBefore('/')
            // SOCKS IP destinations are numeric. Never resolve a hostname just to
            // match a user's CIDR exclusion.
            if (!destination.host.contains(':') && !destination.host.all { it.isDigit() || it == '.' }) return@runCatching false
            val address = InetAddress.getByName(destination.host).address
            val network = InetAddress.getByName(base).address
            val bits = if ('/' in rule) rule.substringAfter('/').toInt() else network.size * 8
            if (address.size != network.size || bits !in 0..network.size * 8) return@runCatching false
            (0 until bits).all { bit ->
                val mask = 1 shl (7 - bit % 8)
                address[bit / 8].toInt() and mask == network[bit / 8].toInt() and mask
            }
        }.getOrDefault(true) }
    }
}
