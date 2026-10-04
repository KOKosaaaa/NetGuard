package com.smarttools.netguard.service

import java.io.ByteArrayOutputStream

/** TLS record framing only. Never changes handshake bytes, TCP sequence numbers or TLS identity. */
internal object LocalDpi {
    const val REVISION = 1
    val paths = listOf(SitePath.TLS_RECORD_SNI, SitePath.TLS_RECORD_HEADER)
    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 255) * 256 + (b[p + 1].toInt() and 255)
    private data class Record(val start: Int, val size: Int, val offset: Int)

    /** null means unsupported: caller must keep the original bytes. No partial mutation. */
    fun transform(bytes: ByteArray, path: SitePath): ByteArray? = runCatching {
        if (path !in paths || bytes.size > 32768 || WebHello.inspect(bytes) !is WebHello.Result.Site) return null
        val records = mutableListOf<Record>()
        val hello = ByteArrayOutputStream()
        var p = 0
        var length = Int.MAX_VALUE
        while (hello.size() < length) {
            if (p + 5 > bytes.size || bytes[p] != 22.toByte() || bytes[p + 1] != 3.toByte() ||
                (bytes[p + 2].toInt() and 255) !in 1..3) return null
            val n = u16(bytes, p + 3)
            if (n !in 1..16384 || p + 5 + n > bytes.size) return null
            records.add(Record(p, n, hello.size()))
            hello.write(bytes, p + 5, n)
            p += n + 5
            val b = hello.toByteArray()
            if (b.size >= 4) {
                if (b[0] != 1.toByte()) return null
                length = 4 + ((b[1].toInt() and 255) shl 16) + u16(b, 2)
                if (length !in 42..32768) return null
            }
        }
        val b = hello.toByteArray()
        var i = 38
        val session = b[i].toInt() and 255
        if (session > 32) return null
        i += 1 + session
        val ciphers = u16(b, i)
        if (ciphers < 2 || ciphers % 2 != 0) return null
        i += 2 + ciphers
        val compression = b[i].toInt() and 255
        if (compression < 1) return null
        i += 1 + compression
        val end = i + 2 + u16(b, i)
        if (end != length) return null
        i += 2
        var middle: Int? = null
        val seen = HashSet<Int>()
        while (i + 4 <= end) {
            val type = u16(b, i)
            val n = u16(b, i + 2)
            if (!seen.add(type)) return null
            i += 4
            if (i + n > end) return null
            // ECH and early data remain outside this catalogue. PSK binder bytes
            // are preserved: TLS record headers are not part of the transcript.
            if (type in listOf(0xfe0d, 42)) return null
            if (type == 41) {
                if (i + n != end || n < 44) return null // PSK must be the last extension.
                val identitiesEnd = i + 2 + u16(b, i)
                if (identitiesEnd + 2 > end) return null
                var j = i + 2
                var identities = 0
                while (j < identitiesEnd) {
                    if (j + 6 > identitiesEnd) return null
                    val size = u16(b, j)
                    if (size == 0 || j + 2 + size + 4 > identitiesEnd) return null
                    j += 2 + size + 4; identities++
                }
                val bindersEnd = identitiesEnd + 2 + u16(b, identitiesEnd)
                if (identities == 0 || bindersEnd != end) return null
                j = identitiesEnd + 2
                var binders = 0
                while (j < bindersEnd) {
                    val size = b[j++].toInt() and 255
                    if (size < 32 || j + size > bindersEnd) return null
                    j += size; binders++
                }
                if (identities != binders) return null
            }
            if (type == 0) {
                if (middle != null || n < 6 || u16(b, i) != n - 2 || b[i + 2] != 0.toByte()) return null
                val nameLength = u16(b, i + 3)
                if (nameLength != n - 5 || nameLength < 2) return null
                middle = i + 5 + nameLength / 2
            }
            i += n
        }
        if (i != end || middle == null) return null
        val offset = if (path == SitePath.TLS_RECORD_HEADER) 1 else middle
        val r = records.firstOrNull { offset > it.offset && offset < it.offset + it.size } ?: return null
        val first = offset - r.offset
        val second = r.size - first
        ByteArrayOutputStream(bytes.size + 5).apply {
            write(bytes, 0, r.start + 3)
            write(first shr 8); write(first and 255)
            write(bytes, r.start + 5, first)
            write(bytes, r.start, 3)
            write(second shr 8); write(second and 255)
            write(bytes, r.start + 5 + first, bytes.size - r.start - 5 - first)
        }.toByteArray()
    }.getOrNull()
}
