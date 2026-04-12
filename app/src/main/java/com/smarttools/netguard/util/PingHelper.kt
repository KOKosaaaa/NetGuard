package com.smarttools.netguard.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

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
}
