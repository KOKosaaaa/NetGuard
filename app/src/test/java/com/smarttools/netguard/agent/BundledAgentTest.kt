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
            "amd64" to (25313442 to "179eaa0f61f1f0ef5df5d396ad761f61763e607d40daaf4a8f10a5108e6c49bb"),
            "arm64" to (23724194 to "04335140f6f8c23c31db6d457fc9ab172c0088e267872abf6f9516c3ab60bb1d")
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
