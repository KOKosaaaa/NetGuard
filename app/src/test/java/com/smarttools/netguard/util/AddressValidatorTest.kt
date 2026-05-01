package com.smarttools.netguard.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Unit tests for the SSRF / private-address filter. These run on the JVM
 * (no Android framework), so they exercise the same `InetAddress` and byte
 * checks that ship in the APK.
 */
class AddressValidatorTest {

    @Test
    fun `dotted loopback literal is rejected`() {
        assertTrue(AddressValidator.isPrivateOrReserved("127.0.0.1"))
        assertTrue(AddressValidator.isPrivateOrReserved("127.0.0.2"))
        assertTrue(AddressValidator.isPrivateOrReserved("127.255.255.254"))
    }

    @Test
    fun `rfc1918 ranges are rejected`() {
        assertTrue(AddressValidator.isPrivateOrReserved("10.0.0.1"))
        assertTrue(AddressValidator.isPrivateOrReserved("192.168.1.1"))
        assertTrue(AddressValidator.isPrivateOrReserved("172.16.0.1"))
        assertTrue(AddressValidator.isPrivateOrReserved("172.31.255.254"))
    }

    @Test
    fun `172_15 and 172_32 are public and allowed`() {
        assertFalse(AddressValidator.isPrivateOrReserved("172.15.0.1"))
        assertFalse(AddressValidator.isPrivateOrReserved("172.32.0.1"))
    }

    @Test
    fun `cgnat 100_64_0_0_10 is rejected`() {
        assertTrue(AddressValidator.isPrivateOrReserved("100.64.0.1"))
        assertTrue(AddressValidator.isPrivateOrReserved("100.127.255.254"))
        // 100.128.0.0 is outside CGNAT and is a normal public address.
        assertFalse(AddressValidator.isPrivateOrReserved("100.128.0.1"))
    }

    @Test
    fun `240_0_0_0_4 reserved is rejected`() {
        assertTrue(AddressValidator.isPrivateOrReserved("240.0.0.1"))
        assertTrue(AddressValidator.isPrivateOrReserved("255.255.255.254"))
    }

    @Test
    fun `ipv6 loopback and unspecified are rejected`() {
        assertTrue(AddressValidator.isPrivateOrReserved("::1"))
        assertTrue(AddressValidator.isPrivateOrReserved("::"))
    }

    @Test
    fun `ipv6 ULA fc00 fd00 are rejected`() {
        assertTrue(AddressValidator.isPrivateOrReserved("fc00::1"))
        assertTrue(AddressValidator.isPrivateOrReserved("fd12:3456:789a::1"))
    }

    @Test
    fun `ipv6 link local fe80 is rejected`() {
        assertTrue(AddressValidator.isPrivateOrReserved("fe80::1"))
    }

    @Test
    fun `ipv6 deprecated site-local fec0 is rejected`() {
        assertTrue(AddressValidator.isPrivateOrReserved("fec0::1"))
    }

    @Test
    fun `ipv4_mapped private in hex is rejected`() {
        // ::ffff:c0a8:0101 = 192.168.1.1
        assertTrue(AddressValidator.isPrivateOrReserved("::ffff:c0a8:0101"))
        // ::ffff:7f00:0001 = 127.0.0.1
        assertTrue(AddressValidator.isPrivateOrReserved("::ffff:7f00:0001"))
    }

    @Test
    fun `ipv4_mapped in dotted form is rejected`() {
        assertTrue(AddressValidator.isPrivateOrReserved("::ffff:192.168.1.1"))
        assertTrue(AddressValidator.isPrivateOrReserved("::ffff:127.0.0.1"))
    }

    @Test
    fun `public ipv4 literal is allowed`() {
        assertFalse(AddressValidator.isPrivateOrReserved("1.1.1.1"))
        assertFalse(AddressValidator.isPrivateOrReserved("8.8.8.8"))
        assertFalse(AddressValidator.isPrivateOrReserved("93.184.216.34"))
    }

    @Test
    fun `public ipv6 literal is allowed`() {
        assertFalse(AddressValidator.isPrivateOrReserved("2001:4860:4860::8888"))
        assertFalse(AddressValidator.isPrivateOrReserved("2606:4700:4700::1111"))
    }

    @Test
    fun `hostname is not resolved here and returns false`() {
        // Parser path must not make a DNS lookup. Domain-shaped input should
        // return false so the SubscriptionRepository safe-DNS layer is the
        // actual line of defence at connect time.
        assertFalse(AddressValidator.isPrivateOrReserved("example.com"))
        assertFalse(AddressValidator.isPrivateOrReserved("localhost.attacker.tld"))
    }

    @Test
    fun `inet address overload covers loopback`() {
        assertTrue(AddressValidator.isPrivateOrReserved(InetAddress.getByName("127.0.0.1")))
        assertTrue(AddressValidator.isPrivateOrReserved(InetAddress.getByName("::1")))
    }

    @Test
    fun `bracketed ipv6 is unwrapped`() {
        assertTrue(AddressValidator.isPrivateOrReserved("[::1]"))
        assertTrue(AddressValidator.isPrivateOrReserved("[fc00::1]"))
    }
}
