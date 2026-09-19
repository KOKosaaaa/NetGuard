package com.smarttools.netguard.service

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Remote DNS, SOCKS authentication and TLS validation use the VPN's proxy. */
class SocksServiceProbe(private val port: Int, private val user: String, private val password: String) : AutoCloseable {
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    fun check(target: HealthTarget): Reachability = try {
        if (target == HealthTarget.TELEGRAM) {
            if (telegram(target.host) || telegram("149.154.175.50")) Reachability.AVAILABLE else Reachability.FAILED
        } else http(target)
    } catch (_: Exception) { Reachability.FAILED }

    private fun <T> connected(host: String, remotePort: Int = 443, block: (Socket) -> T): T {
        check(!closed)
        val socket = Socket()
        sockets.add(socket)
        val deadline = deadlines.schedule({ runCatching { socket.close() } }, 9, TimeUnit.SECONDS)
        try {
            check(!closed)
            socket.soTimeout = 7_000
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val authenticated = user.isNotEmpty()
            output.write(byteArrayOf(5, 1, if (authenticated) 2 else 0))
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != (if (authenticated) 2 else 0)) throw IOException("SOCKS authentication method")
            if (authenticated) {
                val u = user.toByteArray(Charsets.UTF_8); val p = password.toByteArray(Charsets.UTF_8)
                require(u.size in 1..255 && p.size in 1..255)
                output.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS authentication failed")
            }
            val h = host.toByteArray(Charsets.US_ASCII)
            require(h.size in 1..255)
            output.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((remotePort shr 8).toByte(), remotePort.toByte()))
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) throw IOException("SOCKS destination failed")
            input.readUnsignedByte()
            val length = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> throw IOException("SOCKS address") }
            input.readFully(ByteArray(length + 2))
            return block(socket)
        } finally {
            deadline.cancel(false)
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun http(target: HealthTarget): Reachability = connected(target.host) { raw ->
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, target.host, 443, true) as SSLSocket
        tls.use {
            tls.soTimeout = 7_000
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            tls.outputStream.write(("GET ${target.path} HTTP/1.1\r\nHost: ${target.host}\r\nUser-Agent: Mozilla/5.0 NetGuard-Health\r\nAccept: */*\r\nRange: bytes=0-1023\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            val input = tls.inputStream
            fun line(): String {
                val b = StringBuilder()
                while (b.length < 4096) { val c = input.read(); if (c < 0) throw IOException("HTTP EOF"); if (c == 10) return b.toString().trimEnd('\r'); b.append(c.toChar()) }
                throw IOException("HTTP header too long")
            }
            val status = line().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("HTTP status")
            var size = 0
            while (true) { val l = line(); if (l.isEmpty()) break; size += l.length; if (size > 16384) throw IOException("HTTP headers too large") }
            val fragment = if (status == 200 || status == 206 || status == 403) {
                val b = ByteArray(1024); val n = input.read(b)
                if (n < 0 && status in listOf(200,206)) throw IOException("Empty service response")
                if (n > 0) String(b, 0, n, Charsets.UTF_8) else ""
            } else ""
            ServiceHealthPolicy.httpStatus(status, fragment)
        }
    }

    // Unauthenticated req_pq_multi checks MTProto without login or messages.
    private fun telegram(host: String): Boolean = try {
        connected(host) { socket ->
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val now = System.currentTimeMillis()
            val messageId = ((now / 1000) shl 32) or (((now % 1000) shl 32) / 1000 and -4L)
            val packet = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0).putLong(messageId).putInt(20).putInt(0xbe7e8ef1.toInt()).put(nonce).array()
            socket.getOutputStream().write(byteArrayOf(0xef.toByte(), 10) + packet)
            val input = DataInputStream(socket.getInputStream())
            var words = input.readUnsignedByte()
            if (words == 0x7f) words = input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or (input.readUnsignedByte() shl 16)
            if (words !in 10..1024) throw IOException("MTProto response length")
            val bytes = ByteArray(words * 4).also { input.readFully(it) }
            val reply = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (reply.long != 0L) throw IOException("MTProto auth key")
            reply.long
            val body = reply.int
            if (body !in 20..(bytes.size - 20) || reply.int != 0x05162463) throw IOException("MTProto resPQ")
            val echoed = ByteArray(16); reply.get(echoed)
            echoed.contentEquals(nonce)
        }
    } catch (_: Exception) { false }

    override fun close() { closed = true; sockets.forEach { runCatching { it.close() } }; sockets.clear() }
    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "service-probe-deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    }
}
