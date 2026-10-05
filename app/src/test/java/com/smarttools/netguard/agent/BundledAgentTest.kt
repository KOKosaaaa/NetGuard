package com.smarttools.netguard.agent

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BundledAgentTest {
    private fun asset(arch: String) = File(System.getProperty("netguard.agentAssetsDir"), "netguard-agent-$arch")

    @Test fun bothOfflineInstallersRemainByteIdenticalAfterDecoding() {
        val expected = mapOf(
            "amd64" to (26972320 to "0dbb0c58d4c6ee04d30013ac566af8f8d950288259ff872abc514e25cddaaab0"),
            "arm64" to (25165984 to "c830f594bea656598dd3fb062ad0779a8b8253ea6a653188d66dfc1c6a118b69")
        )
        for ((arch, fingerprint) in expected) {
            val decoded = asset(arch).inputStream().use { BundledAgent.decode(it) }
            assertEquals(arch, fingerprint.first, decoded.size)
            val hash = MessageDigest.getInstance("SHA-256").digest(decoded).joinToString("") { "%02x".format(it) }
            assertEquals(arch, fingerprint.second, hash)
            assertArrayEquals(decoded, BundledAgent.decode(decoded.inputStream()))
        }
    }

    @Test fun corruptedOrOversizedInstallerIsNeverUploaded() {
        val packed = asset("amd64").readBytes()
        try {
            BundledAgent.decode(packed.inputStream(), maxBytes = 1024)
            fail("Oversized installer accepted")
        } catch (_: IOException) { }
        packed[packed.lastIndex] = (packed.last().toInt() xor 1).toByte()
        try {
            BundledAgent.decode(packed.inputStream())
            fail("Corrupted installer accepted")
        } catch (_: IOException) { }
    }
}
