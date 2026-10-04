package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Independent adversarial fixtures: no production parser used to construct expected bytes. */
class LocalDpiQaTest {
    private fun encoded(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(write)
    }.toByteArray()
    private fun extension(type: Int, payload: ByteArray) = encoded { writeShort(type); writeShort(payload.size); write(payload) }
    private fun sni(host: String = "example.test") = extension(0, encoded {
        val name = host.toByteArray(Charsets.US_ASCII)
        writeShort(name.size + 3); writeByte(0); writeShort(name.size); write(name)
    })
    private fun hello(extra: ByteArray = byteArrayOf(), sessionSize: Int = 0,
                      ciphers: ByteArray = byteArrayOf(0x13, 1), compression: ByteArray = byteArrayOf(0)): ByteArray {
        val body = encoded {
            writeShort(0x0303); write(ByteArray(32) { it.toByte() })
            writeByte(sessionSize); write(ByteArray(sessionSize))
            writeShort(ciphers.size); write(ciphers)
            writeByte(compression.size); write(compression)
            val exts = sni() + extra
            writeShort(exts.size); write(exts)
        }
        return byteArrayOf(1, (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
    }
    private fun record(payload: ByteArray, type: Int = 22, version: Int = 0x0301) = encoded {
        writeByte(type); writeShort(version); writeShort(payload.size); write(payload)
    }
    private fun payloads(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); var p = 0
        while (p < bytes.size) {
            assertTrue("complete record header", bytes.size - p >= 5)
            val n = ((bytes[p + 3].toInt() and 255) shl 8) or (bytes[p + 4].toInt() and 255)
            assertTrue("non-empty bounded record", n in 1..16384)
            assertTrue("record payload available", p + 5 + n <= bytes.size)
            out.write(bytes, p + 5, n); p += 5 + n
        }
        return out.toByteArray()
    }
    private fun rejected(bytes: ByteArray) = LocalDpi.paths.forEach {
        assertNull("unsupported must not be rewritten: $it", LocalDpi.transform(bytes, it))
    }

    @Test fun bothStrategiesPreserveHandshakeAndInputExactly() {
        val message = hello(); val wire = record(message); val saved = wire.copyOf()
        for (path in LocalDpi.paths) {
            val changed = requireNotNull(LocalDpi.transform(wire, path))
            assertEquals(wire.size + 5, changed.size)
            assertArrayEquals(message, payloads(changed))
            assertArrayEquals(saved, wire)
            assertEquals(0x03, changed[1].toInt()); assertEquals(0x01, changed[2].toInt())
        }
    }
    @Test fun headerStrategySplitsAfterExactlyOneHandshakeByte() {
        val changed = requireNotNull(LocalDpi.transform(record(hello()), SitePath.TLS_RECORD_HEADER))
        assertArrayEquals(byteArrayOf(22, 3, 1, 0, 1, 1, 22, 3, 1), changed.copyOfRange(0, 9))
    }
    @Test fun prefragmentedHelloPreservesEveryPayloadAndTrailingRecords() {
        val h = hello(); val tail = record(byteArrayOf(1), 20, 0x0303) + record(byteArrayOf(9, 8, 7), 23, 0x0303)
        val wire = record(h.copyOfRange(0, 7)) + record(h.copyOfRange(7, h.size), version = 0x0303) + tail
        LocalDpi.paths.forEach { path ->
            val changed = requireNotNull(LocalDpi.transform(wire, path))
            assertArrayEquals(payloads(wire), payloads(changed))
            assertArrayEquals(tail, changed.takeLast(tail.size).toByteArray())
        }
    }
    @Test fun alreadySplitHeaderBoundaryIsUnsupportedRatherThanCorrupted() {
        val h = hello(); val wire = record(h.copyOfRange(0, 1)) + record(h.copyOfRange(1, h.size))
        assertNull(LocalDpi.transform(wire, SitePath.TLS_RECORD_HEADER))
        assertArrayEquals(h, payloads(requireNotNull(LocalDpi.transform(wire, SitePath.TLS_RECORD_SNI))))
    }
    @Test fun incompleteRecordsAndEveryTruncatedHelloAreRejected() {
        val wire = record(hello())
        for (n in wire.indices) rejected(wire.copyOf(n))
    }
    @Test fun echPskAndEarlyDataAreNeverModified() {
        for (type in listOf(0xfe0d, 41, 42)) rejected(record(hello(extension(type, byteArrayOf()))))
    }
    @Test fun duplicateSniAndBadSniBoundsAreRejected() {
        rejected(record(hello(sni())))
        val h = hello(); h[h.size - "example.test".length - 1] = 100
        rejected(record(h))
    }
    @Test fun wrongRecordTypeVersionAndImpossibleHandshakeLengthAreRejected() {
        rejected(record(hello(), 23)); rejected(record(hello(), version = 0x0304))
        val h = hello(); h[1] = 0x7f; rejected(record(h))
    }
    @Test fun invalidClientHelloVectorsAreRejected() {
        rejected(record(hello(sessionSize = 33)))
        rejected(record(hello(ciphers = byteArrayOf())))
        rejected(record(hello(ciphers = byteArrayOf(0x13, 1, 0))))
        rejected(record(hello(compression = byteArrayOf())))
    }
    @Test fun duplicatedExtensionsAreNotACompatibleClientHello() {
        val supportedVersions = extension(43, byteArrayOf(2, 3, 4))
        rejected(record(hello(supportedVersions + supportedVersions)))
    }
    @Test fun excessSizeAndNonStrategyInputsAreRejectedWithoutMutation() {
        rejected(ByteArray(32769))
        assertNull(LocalDpi.transform(record(hello()), SitePath.DIRECT))
        assertNull(LocalDpi.transform(record(hello()), SitePath.VPN))
        rejected("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".toByteArray())
    }
}
