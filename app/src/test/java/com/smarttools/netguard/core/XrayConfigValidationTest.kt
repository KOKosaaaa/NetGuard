package com.smarttools.netguard.core

import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.model.TlsFingerprintMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for the XrayConfigGenerator audit fixes:
 *  - validateProfile() must reject port out of range and control characters
 *    in any field that ends up inside the JSON config (audit P1 #11).
 *  - resolveFingerprint() must honour the global TlsFingerprintMode setting
 *    when the profile has no override, and the per-profile override always
 *    wins (audit P2 #8).
 *
 * These do not require an Android device; they run under
 * `./gradlew testReleaseUnitTest`.
 */
class XrayConfigValidationTest {

    private fun baseProfile() = ServerProfile(
        id = 1,
        name = "TEST",
        address = "example.com",
        port = 443,
        uuid = "00000000-0000-0000-0000-000000000000",
    )

    @Test
    fun `valid profile generates without throwing`() {
        XrayConfigGenerator.generate(baseProfile(), AppSettings())
    }

    @Test
    fun `port zero is rejected`() {
        try {
            XrayConfigGenerator.generate(baseProfile().copy(port = 0), AppSettings())
            fail("Expected IllegalArgumentException for port=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("port"))
        }
    }

    @Test
    fun `port out of range is rejected`() {
        try {
            XrayConfigGenerator.generate(baseProfile().copy(port = 70000), AppSettings())
            fail("Expected IllegalArgumentException for port=70000")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("port"))
        }
    }

    @Test
    fun `null byte in address is rejected`() {
        try {
            XrayConfigGenerator.generate(
                baseProfile().copy(address = "evil\u0000.com"),
                AppSettings(),
            )
            fail("Expected IllegalArgumentException for control char in address")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("address"))
        }
    }

    @Test
    fun `newline in path is rejected`() {
        try {
            XrayConfigGenerator.generate(
                baseProfile().copy(path = "/api\n/admin"),
                AppSettings(),
            )
            fail("Expected IllegalArgumentException for control char in path")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("path"))
        }
    }

    @Test
    fun `delete char 0x7f in sni is rejected`() {
        try {
            XrayConfigGenerator.generate(
                baseProfile().copy(sni = "example\u007f.com"),
                AppSettings(),
            )
            fail("Expected IllegalArgumentException for 0x7f in sni")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sni"))
        }
    }

    @Test
    fun `default fingerprint mode is chrome`() {
        val cfg = XrayConfigGenerator.generate(
            baseProfile().copy(
                security = com.smarttools.netguard.model.SecurityType.TLS,
            ),
            AppSettings(),
        )
        assertTrue("expected chrome in $cfg", cfg.json.contains("\"fingerprint\":\"chrome\""))
    }

    @Test
    fun `firefox fingerprint setting overrides default`() {
        val cfg = XrayConfigGenerator.generate(
            baseProfile().copy(
                security = com.smarttools.netguard.model.SecurityType.TLS,
            ),
            AppSettings(tlsFingerprintMode = TlsFingerprintMode.FIREFOX),
        )
        assertTrue("expected firefox in $cfg", cfg.json.contains("\"fingerprint\":\"firefox\""))
    }

    @Test
    fun `random fingerprint produces one of the supported set`() {
        val supported = setOf("chrome", "firefox", "safari", "ios", "edge")
        // 50 generations should sample most of the set; we just verify each
        // result is from the allow-list — that is the security-relevant
        // invariant.
        repeat(50) {
            val cfg = XrayConfigGenerator.generate(
                baseProfile().copy(
                    security = com.smarttools.netguard.model.SecurityType.TLS,
                ),
                AppSettings(tlsFingerprintMode = TlsFingerprintMode.RANDOM),
            )
            val match = supported.firstOrNull { cfg.json.contains("\"fingerprint\":\"$it\"") }
            assertTrue("fingerprint not from allow-list: $cfg", match != null)
        }
    }

    @Test
    fun `profile fingerprint override wins over global setting`() {
        val cfg = XrayConfigGenerator.generate(
            baseProfile().copy(
                security = com.smarttools.netguard.model.SecurityType.TLS,
                fingerprint = "ios",
            ),
            AppSettings(tlsFingerprintMode = TlsFingerprintMode.CHROME),
        )
        assertTrue("expected ios override in $cfg", cfg.json.contains("\"fingerprint\":\"ios\""))
    }

    @Test
    fun `socks port and credentials are non-empty for tun inbound`() {
        val cfg = XrayConfigGenerator.generate(baseProfile(), AppSettings(), useSocksInbound = true)
        assertTrue("expected positive port: ${cfg.socksPort}", (cfg.socksPort ?: 0) > 0)
        assertEquals("user must be 36 chars (UUID)", 36, cfg.socksUser?.length ?: 0)
        assertEquals("pass must be 32 chars", 32, cfg.socksPass?.length ?: 0)
    }
}
