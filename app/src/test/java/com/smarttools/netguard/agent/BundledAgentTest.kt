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
            "amd64" to (26972320 to "9cc19250d58fcb13b555b7cc9d57b3d631e6bb659ab2450ffac43f7302bcd83b"),
            "arm64" to (25165984 to "586be19e4d5b6c14ac144b868c3da777355af4c0c020e245512344df80235446")
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
