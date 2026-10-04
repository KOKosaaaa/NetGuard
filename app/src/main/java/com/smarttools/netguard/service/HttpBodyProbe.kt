package com.smarttools.netguard.service

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Bounded, independent GET response. No redirects, decompression, cookies or user URL. */
internal object HttpBodyProbe {
    const val MAX_BODY = 256 * 1024
    const val MIN_VERIFIED = 32 * 1024
    data class Result(val status: Int, val bytes: Int, val complete: Boolean, val sha256: String)

    fun read(input: InputStream, stage: (String) -> Unit = {}, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        var overhead = 0
        fun line(): String {
            val b = StringBuilder()
            while (b.length <= 8192 && ++overhead <= 32768) {
                val c = input.read()
                if (c < 0) throw EOFException("HTTP framing incomplete")
                if (c == 13) {
                    if (input.read() != 10) throw IOException("HTTP line ending")
                    overhead++
                    return b.toString()
                }
                if (c == 10 || c == 0) throw IOException("HTTP line ending")
                b.append(c.toChar())
            }
            throw IOException("HTTP framing limit")
        }
        fun headers(trailers: Boolean = false): Map<String, String> {
            val result = mutableMapOf<String, String>()
            while (true) {
                val l = line()
                if (l.isEmpty()) return result
                val name = l.substringBefore(':')
                if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) || ':' !in l) throw IOException("HTTP header")
                val key = name.lowercase(java.util.Locale.ROOT)
                if (trailers && key in setOf("content-length", "transfer-encoding", "host", "trailer")) throw IOException("Forbidden HTTP trailer")
                if (result.containsKey(key) && key in setOf("content-length", "transfer-encoding")) throw IOException("Ambiguous HTTP framing")
                val value = l.substringAfter(':')
                if (value.any { (it.code < 32 && it != '\t') || it.code == 127 }) throw IOException("HTTP header control")
                result[key] = value.trim()
            }
        }
        stage("HEADERS")
        var status: Int
        var h: Map<String, String>
        var interim = 0
        do {
            val l = line()
            if (!l.matches(Regex("HTTP/1\\.[01] [0-9]{3}( .*)?"))) throw IOException("HTTP status")
            status = l.substring(9, 12).toInt()
            h = headers()
            if (++interim > 5) throw IOException("HTTP interim limit")
        } while (status in 100..199 && status != 101)
        if (status !in 200..599) throw IOException("HTTP protocol switch")
        val length = h["content-length"]?.let {
            if (!it.matches(Regex("[0-9]+"))) throw IOException("HTTP content length")
            it.toLongOrNull() ?: throw IOException("HTTP content length")
        }
        val chunked = h["transfer-encoding"]?.let {
            if (!it.equals("chunked", true) || length != null) throw IOException("Unsupported HTTP framing")
            true
        } ?: false
        stage("BODY")
        progress(status, 0)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0
        val buffer = ByteArray(8192)
        fun consume(count: Long): Boolean {
            var left = count
            while (left > 0) {
                if (total == MAX_BODY) return false
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left, (MAX_BODY - total).toLong()).toInt())
                if (n < 0) throw EOFException("HTTP body incomplete")
                if (n == 0) throw IOException("HTTP no progress")
                digest.update(buffer, 0, n); total += n; left -= n
                progress(status, total)
            }
            return true
        }
        val complete = when {
            status == 204 || status == 304 -> true
            chunked -> {
                var done = false
                while (!done) {
                    val size = line().substringBefore(';')
                    if (!size.matches(Regex("[0-9a-fA-F]{1,15}"))) throw IOException("HTTP chunk length")
                    val n = size.toLong(16)
                    if (n == 0L) { headers(trailers = true); done = true }
                    else {
                        if (!consume(n)) break
                        if (line().isNotEmpty()) throw IOException("HTTP chunk delimiter")
                    }
                }
                done
            }
            length != null -> consume(length)
            else -> {
                // EOF-delimited messages cannot prove completeness after a silent truncation.
                while (total < MAX_BODY) {
                    val n = input.read(buffer, 0, minOf(buffer.size, MAX_BODY - total))
                    if (n < 0) break
                    if (n == 0) throw IOException("HTTP no progress")
                    digest.update(buffer, 0, n); total += n
                    progress(status, total)
                }
                false
            }
        }
        return Result(status, total, complete, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
