package com.smarttools.netguard.util

import android.content.Context
import com.smarttools.netguard.R

/** Presentation-only mapping of existing probe output. Never changes pass/warning or network behavior. */
object SecurityResultText {
    fun name(context: Context, value: String): String {
        val id = when (value) {
            "Own SOCKS5 auth" -> R.string.security_name_1
            "Neighboring VPNs" -> R.string.security_name_2
            "SOCKS5 Proxy" -> R.string.security_name_3
            "HTTP Proxy" -> R.string.security_name_4
            "Xray API" -> R.string.security_name_5
            "Clash API" -> R.string.security_name_6
            "Port Scan" -> R.string.security_name_7
            "/proc/net/tcp" -> R.string.security_name_8
            "VPN Flag" -> R.string.security_name_9
            "MTU Check" -> R.string.security_name_10
            "Package Name" -> R.string.security_name_11
            else -> return value
        }
        return LocalizedResources.string(context, id)
    }
    private data class Rule(val prefix: String, val suffix: String, val id: Int, val argument: Boolean)
    private val rules = listOf(
        Rule("Tunnel not active, cannot test", "", R.string.security_detail_1, false),
        Rule("Server closed connection on no-auth — good", "", R.string.security_detail_2, false),
        Rule("Unexpected SOCKS version: ", "", R.string.security_detail_3, true),
        Rule("CRITICAL: own SOCKS5 accepts NO AUTH — auth chain is broken!", "", R.string.security_detail_4, false),
        Rule("Own SOCKS5 correctly requires user/pass auth", "", R.string.security_detail_5, false),
        Rule("Own SOCKS5 rejects no-auth (NO ACCEPTABLE METHODS)", "", R.string.security_detail_6, false),
        Rule("Own SOCKS5 returned unexpected method: 0x", "", R.string.security_detail_7, true),
        Rule("Could not test own SOCKS: ", "", R.string.security_detail_8, true),
        Rule("No other known VPN clients installed", "", R.string.security_detail_9, false),
        Rule("Other VPN clients installed (some may be affected by the April 2026 local-SOCKS leak): ", "", R.string.security_detail_10, true),
        Rule("No unauthenticated SOCKS5 found on standard ports", "", R.string.security_detail_11, false),
        Rule("VULNERABLE: Open SOCKS5 without auth on ports: ", "", R.string.security_detail_12, true),
        Rule("No open HTTP CONNECT proxy found on standard ports", "", R.string.security_detail_13, false),
        Rule("VULNERABLE: Open HTTP proxy on ports: ", "", R.string.security_detail_14, true),
        Rule("No Xray gRPC API ports detected", "", R.string.security_detail_15, false),
        Rule("VULNERABLE: Xray API ports open: ", "", R.string.security_detail_16, true),
        Rule("No Clash REST API detected", "", R.string.security_detail_17, false),
        Rule("VULNERABLE: Clash API open on ports: ", " — /connections leaks all IPs", R.string.security_detail_18, true),
        Rule("No known VPN ports open on localhost", "", R.string.security_detail_19, false),
        Rule("Detectable ports open: ", "", R.string.security_detail_20, true),
        Rule("No known VPN ports found in /proc/net/tcp", "", R.string.security_detail_21, false),
        Rule("Detectable in /proc/net/tcp: ", "", R.string.security_detail_22, true),
        Rule("Cannot read /proc/net/tcp (may be restricted)", "", R.string.security_detail_23, false),
        Rule("TRANSPORT_VPN is visible (unavoidable on Android). Apps can detect VPN is active, but cannot see server IP.", "", R.string.security_detail_24, false),
        Rule("No TRANSPORT_VPN flag detected (VPN not active or check ran before connection)", "", R.string.security_detail_25, false),
        Rule("Cannot check NetworkCapabilities", "", R.string.security_detail_26, false),
        Rule("No TUN interface detected", "", R.string.security_detail_27, false),
        Rule("Informational only: ", ". MTU alone is not a reliable VPN indicator.", R.string.security_detail_28, true),
        Rule("Cannot check interface MTU", "", R.string.security_detail_29, false),
        Rule("VULNERABLE: Package name '", "' contains VPN keywords", R.string.security_detail_30, true),
        Rule("Package name '", "' is neutral", R.string.security_detail_31, true)
    )
    fun details(context: Context, value: String): String {
        for (rule in rules) {
            if (!rule.argument && value == rule.prefix) return LocalizedResources.string(context, rule.id)
            if (rule.argument && value.startsWith(rule.prefix) && value.endsWith(rule.suffix) &&
                value.length >= rule.prefix.length + rule.suffix.length) {
                val data = value.substring(rule.prefix.length, value.length - rule.suffix.length)
                return LocalizedResources.string(context, rule.id, data)
            }
        }
        return value // Unknown external error details remain verbatim.
    }
}
