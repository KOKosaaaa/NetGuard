package com.smarttools.netguard.util

import com.smarttools.netguard.model.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object PingHelper {

    suspend fun tcpPing(host: String, port: Int, timeoutMs: Int = 3000): Int {
        return withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                (System.currentTimeMillis() - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } catch (_: Exception) {
                -1
            }
        }
    }

    /**
     * ICMP echo via /system/bin/ping (Android's binary is setuid; no root
     * required). Used for UDP-only protocols where TCP connect to the
     * service port can't measure anything. Returns -1 on any failure
     * including hosts that block ICMP.
     */
    suspend fun icmpPing(host: String, timeoutSec: Int = 2): Int {
        return withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder(
                    "/system/bin/ping", "-c", "1", "-W", timeoutSec.toString(), host
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                if (!proc.waitFor((timeoutSec + 1).toLong(), TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    return@withContext -1
                }
                if (proc.exitValue() != 0) return@withContext -1
                // "64 bytes from x.x.x.x: icmp_seq=1 ttl=55 time=21.4 ms"
                val m = Regex("""time=([0-9]+\.?[0-9]*)""").find(out)
                m?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: -1
            } catch (_: Exception) {
                -1
            }
        }
    }

    /**
     * Pick the right probe based on protocol. Hysteria2 runs on QUIC/UDP, so
     * a TCP connect to its port will always fail and the UI would show "-".
     * For UDP-only protocols we fall back to ICMP — measures host RTT, not
     * service RTT, but tells the user whether the server is alive at all.
     */
    suspend fun pingForProfile(host: String, port: Int, protocol: Protocol): Int {
        return when (protocol) {
            Protocol.HYSTERIA2 -> icmpPing(host)
            else -> tcpPing(host, port)
        }
    }
}
