package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class HttpBodyProbeQaTest {
    private fun response(headers: String, body: ByteArray = byteArrayOf(), status: String = "200 OK") =
        "HTTP/1.1 $status\r\n$headers\r\n".toByteArray(Charsets.ISO_8859_1) + body
    private fun parse(bytes: ByteArray) = HttpBodyProbe.read(ByteArrayInputStream(bytes))
    private fun rejected(bytes: ByteArray) {
        try { parse(bytes); fail("Malformed/truncated HTTP must be rejected") }
        catch (_: IOException) { }
    }
    private fun digest(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val body = ByteArray(65539) { ((it * 29 + 17) % 251).toByte() }

    @Test fun completeContentLengthHashesEveryByteAcrossShortReads() {
        val input = object : ByteArrayInputStream(response("Content-Length: ${body.size}\r\n", body)) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 7))
        }
        val result = HttpBodyProbe.read(input)
        assertTrue(result.complete); assertEquals(body.size, result.bytes); assertEquals(digest(body), result.sha256)
    }
    @Test fun partialBodiesAtFourAndSixteenKiBNeverPass() {
        for (n in listOf(0, 4096, 16384, body.size - 1))
            rejected(response("Content-Length: ${body.size}\r\n", body.copyOf(n)))
    }
    @Test fun eofFramingIsLimitedEvenIfBodyLooksLongEnough() {
        val result = parse(response("Connection: close\r\n", body))
        assertFalse(result.complete); assertEquals(body.size, result.bytes); assertEquals(digest(body), result.sha256)
    }
    @Test fun exactBodyLimitIsCompleteButOneByteOverIsLimited() {
        val full = ByteArray(HttpBodyProbe.MAX_BODY) { it.toByte() }
        val exact = parse(response("Content-Length: ${full.size}\r\n", full))
        assertTrue(exact.complete); assertEquals(digest(full), exact.sha256)
        val limited = parse(response("Content-Length: ${full.size + 1}\r\n", full + byteArrayOf(1)))
        assertFalse(limited.complete); assertEquals(full.size, limited.bytes); assertEquals(digest(full), limited.sha256)
    }
    @Test fun chunkedBodyIncludesOnlyDecodedPayloadInLengthAndHash() {
        val split = 16384
        val chunks = "4000;test=\"valid\"\r\n".toByteArray() + body.copyOfRange(0, split) +
            "\r\n${(body.size - split).toString(16)}\r\n".toByteArray() + body.copyOfRange(split, body.size) +
            "\r\n0\r\nX-Checksum: synthetic\r\n\r\n".toByteArray()
        val result = parse(response("Transfer-Encoding: chunked\r\n", chunks))
        assertTrue(result.complete); assertEquals(body.size, result.bytes); assertEquals(digest(body), result.sha256)
    }
    @Test fun chunkedMustContainZeroChunkAndCompleteTrailerTerminator() {
        for (ending in listOf("", "\r\n", "\r\n0", "\r\n0\r\n", "\r\n0\r\nX-Test: yes\r\n"))
            rejected(response("Transfer-Encoding: chunked\r\n", "1\r\na$ending".toByteArray()))
    }
    @Test fun invalidChunkLengthsDelimitersAndOverflowAreRejected() {
        for (chunks in listOf("-1\r\na\r\n0\r\n\r\n", "0x1\r\na\r\n0\r\n\r\n", "1\r\naX\r\n0\r\n\r\n", "FFFFFFFFFFFFFFFF\r\n"))
            rejected(response("Transfer-Encoding: chunked\r\n", chunks.toByteArray()))
    }
    @Test fun ambiguousFramingAndInvalidLengthsAreRejected() {
        for (headers in listOf("Content-Length: 1\r\ncontent-length: 1\r\n", "Content-Length: +1\r\n",
            "Content-Length: 9223372036854775808\r\n", "Content-Length: 1\r\nTransfer-Encoding: chunked\r\n",
            "Transfer-Encoding: gzip, chunked\r\n", "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n"))
            rejected(response(headers, "a".toByteArray()))
    }
    @Test fun protocolSwitchAndTooManyInterimResponsesAreRejected() {
        rejected(response("Upgrade: h2c\r\n", status = "101 Switching Protocols"))
        rejected("HTTP/1.1 100 Continue\r\n\r\n".repeat(6).toByteArray() + response("Content-Length: 0\r\n"))
        val ok = parse("HTTP/1.1 103 Early Hints\r\n\r\n".toByteArray() + response("Content-Length: ${body.size}\r\n", body))
        assertTrue(ok.complete); assertEquals(body.size, ok.bytes)
    }
    @Test fun headerLimitsAndBadLineEndingsAreEnforced() {
        rejected(response("X: " + "x".repeat(8200) + "\r\n"))
        rejected("HTTP/1.1 200 OK\nContent-Length: 0\n\n".toByteArray())
        rejected(response("Bad Header: value\r\nContent-Length: 0\r\n"))
    }
    @Test fun forbiddenFramingTrailersAndControlCharactersAreRejected() {
        for (trailer in listOf("Content-Length: 0", "Transfer-Encoding: chunked"))
            rejected(response("Transfer-Encoding: chunked\r\n", "1\r\na\r\n0\r\n$trailer\r\n\r\n".toByteArray()))
        rejected(response("X-Test: bad\u0001value\r\nContent-Length: 0\r\n"))
    }
    @Test fun zeroProgressBodyReadDoesNotSpin() {
        val prefix = ByteArrayInputStream(response("Content-Length: 10\r\n"))
        val input = object : InputStream() {
            override fun read(): Int = prefix.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        try { HttpBodyProbe.read(input); fail("zero progress must fail") } catch (_: IOException) { }
    }
}
