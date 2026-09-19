package com.smarttools.netguard.service

import java.io.ByteArrayOutputStream

/** Reads only the routing metadata in HTTP Host / TLS ClientHello. No TLS interception. */
internal object WebHello {
    sealed interface Result {
        data object More : Result
        data object Opaque : Result
        data class Site(val host: String, val tls: Boolean) : Result
    }
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 255) * 256 + (b[i + 1].toInt() and 255)

    fun inspect(bytes: ByteArray): Result = try {
        if (bytes.isEmpty()) Result.More
        else if (bytes[0] == 22.toByte()) tls(bytes)
        else http(bytes)
    } catch (_: Exception) { Result.Opaque }

    private fun tls(bytes: ByteArray): Result {
        val hello = ByteArrayOutputStream()
        var p = 0
        while (p < bytes.size) {
            if (p + 5 > bytes.size) return Result.More
            if (bytes[p] != 22.toByte() || bytes[p + 1] != 3.toByte()) return Result.Opaque
            val size = u16(bytes, p + 3)
            if (size > 18432) return Result.Opaque
            if (p + 5 + size > bytes.size) return Result.More
            hello.write(bytes, p + 5, size)
            p += 5 + size
            val b = hello.toByteArray()
            if (b.size < 4) continue
            if (b[0] != 1.toByte()) return Result.Opaque
            val length = ((b[1].toInt() and 255) shl 16) or u16(b, 2)
            if (length > 32764) return Result.Opaque
            if (b.size < length + 4) continue
            var i = 4 + 2 + 32
            i += 1 + (b[i].toInt() and 255)
            i += 2 + u16(b, i)
            i += 1 + (b[i].toInt() and 255)
            val end = i + 2 + u16(b, i)
            if (end > length + 4) return Result.Opaque
            i += 2
            var host: String? = null
            while (i + 4 <= end) {
                val type = u16(b, i)
                val n = u16(b, i + 2)
                i += 4
                if (i + n > end) return Result.Opaque
                // ECH's outer SNI isn't the site being opened. Keep its original
                // destination and route through VPN rather than guessing.
                if (type == 0xfe0d) return Result.Opaque
                if (type == 16) {
                    val alpnEnd = i + 2 + u16(b, i)
                    if (alpnEnd != i + n) return Result.Opaque
                    var j = i + 2
                    var web = false
                    while (j < alpnEnd) {
                        val len = b[j++].toInt() and 255
                        if (len == 0 || j + len > alpnEnd) return Result.Opaque
                        val protocol = String(b, j, len, Charsets.US_ASCII)
                        if (protocol == "h2" || protocol == "http/1.1" || protocol == "http/1.0") web = true
                        j += len
                    }
                    if (!web) return Result.Opaque
                }
                if (type == 0 && n >= 5) {
                    val listEnd = i + 2 + u16(b, i)
                    if (listEnd > i + n) return Result.Opaque
                    var j = i + 2
                    while (j + 3 <= listEnd) {
                        val t = b[j].toInt() and 255
                        val len = u16(b, j + 1)
                        j += 3
                        if (j + len > listEnd) return Result.Opaque
                        if (t == 0) host = SiteRoutingPolicy.host(String(b, j, len, Charsets.US_ASCII))
                        j += len
                    }
                }
                i += n
            }
            return host?.let { Result.Site(it, true) } ?: Result.Opaque
        }
        return Result.More
    }

    private fun http(bytes: ByteArray): Result {
        val text = String(bytes, Charsets.ISO_8859_1)
        val methods = listOf("GET ", "HEAD ", "POST ", "PUT ", "DELETE ", "PATCH ", "OPTIONS ")
        if (methods.none { text.startsWith(it) || it.startsWith(text) }) return Result.Opaque
        val end = text.indexOf("\r\n\r\n")
        if (end < 0) return if (bytes.size < 16384) Result.More else Result.Opaque
        val hosts = text.substring(0, end).split("\r\n").drop(1)
            .filter { it.substringBefore(':').equals("host", true) }
        if (hosts.size != 1) return Result.Opaque
        val authority = hosts.single().substringAfter(':').trim()
        val host = SiteRoutingPolicy.host(authority.substringBefore(':')) ?: return Result.Opaque
        return Result.Site(host, false)
    }
}
