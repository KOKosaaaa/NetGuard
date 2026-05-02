package com.smarttools.netguard.core

import android.util.Log
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.util.AddressValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cheap reachability check before [com.smarttools.netguard.service.TunnelVpnService]
 * spends ~30 s standing up xray + tun2socks against a server that is dead.
 *
 * For TCP-transport profiles we open a raw socket to `address:port` and
 * measure the connect latency; for UDP-only profiles (Hysteria2) we fall
 * back to a DNS resolution check, since we cannot meaningfully ping
 * UDP without speaking the protocol.
 *
 * The check is intentionally short: a 2 s budget per server keeps the
 * UI responsive and lets [com.smarttools.netguard.service.TunnelVpnService]
 * decide quickly whether to try the next failover candidate.
 */
object ServerPreflight {

    private const val TAG = "ServerPreflight"
    private const val DNS_TIMEOUT_MS = 1500
    private const val TCP_TIMEOUT_MS = 2000

    sealed class Result {
        data class Ok(val rttMs: Long) : Result()
        data class Slow(val rttMs: Long) : Result()
        data class Dead(val reason: String) : Result()
    }

    private fun isUdpOnly(profile: ServerProfile): Boolean {
        return profile.protocol == com.smarttools.netguard.model.Protocol.HYSTERIA2
    }

    suspend fun check(profile: ServerProfile, slowThresholdMs: Long = 500): Result =
        withContext(Dispatchers.IO) {
            val address = profile.address
            if (address.isEmpty()) return@withContext Result.Dead("Empty address")

            // DNS first — covers both transports and rejects garbage hostnames
            // before we burn the TCP budget on a name that does not resolve.
            val resolved = try {
                InetAddress.getByName(address)
            } catch (e: Exception) {
                return@withContext Result.Dead("DNS: ${e.message ?: "unresolvable"}")
            }

            // DNS-rebinding guard: ProfileParser only validates the textual
            // literal, so a hostname that resolves to a private/loopback
            // address slips through. Re-check the resolved IP before we touch
            // the wire — preflight is the last gate before TCP-SYN goes out
            // with the user's real IP, untunneled.
            if (AddressValidator.isPrivateOrReserved(resolved)) {
                return@withContext Result.Dead("Resolved to private/reserved address")
            }

            if (isUdpOnly(profile)) {
                // UDP path — we cannot speak Hysteria2 without xray, so DNS
                // success is the strongest cheap signal we have.
                return@withContext Result.Ok(0)
            }

            val socket = Socket()
            val started = System.currentTimeMillis()
            try {
                socket.connect(InetSocketAddress(resolved, profile.port), TCP_TIMEOUT_MS)
                val rtt = System.currentTimeMillis() - started
                if (rtt > slowThresholdMs) Result.Slow(rtt) else Result.Ok(rtt)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Log.d(TAG, "TCP preflight to ${profile.address}:${profile.port} failed: $reason")
                Result.Dead("TCP: $reason")
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
}
