package com.smarttools.netguard.service

import java.net.InetAddress
import java.net.InetSocketAddress

/** Original kernel socket tuple, supplied by our authenticated native TUN reader. */
internal data class AppConnection(val protocol: Int, val local: InetSocketAddress, val remote: InetSocketAddress) {
    companion object {
        fun parse(value: String): AppConnection? = runCatching {
            val parts = value.split('|')
            require(parts.size == 5)
            val protocol = parts[0].toInt()
            require(protocol == 6 || protocol == 17)
            fun endpoint(host: String, port: String): InetSocketAddress {
                // Numeric IP only: metadata must never trigger a DNS lookup.
                require(host.isNotEmpty() && host.all { it in "0123456789abcdefABCDEF:.%" })
                val p = port.toInt(); require(p in 1..65535)
                return InetSocketAddress(InetAddress.getByName(host), p)
            }
            AppConnection(protocol, endpoint(parts[1], parts[2]), endpoint(parts[3], parts[4]))
        }.getOrNull()
    }
}

internal class AppRoutePolicy(
    private val forcedUids: Set<Int>,
    private val configured: Boolean,
    private val ownerUid: (AppConnection) -> Int
) {
    fun forceVpn(connection: AppConnection?): Boolean {
        if (!configured) return false
        // Older Android / vendor lookup failures must never allow a selected app
        // to use a direct cached route. Unknown owners conservatively use VPN.
        if (connection == null) return true
        val uid = runCatching { ownerUid(connection) }.getOrDefault(-1)
        return uid < 0 || uid in forcedUids
    }
}
