package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test

class AppRoutePolicyTest {
    @Test fun sameWebsiteDifferentAppsDoNotShareOverride() {
        val selected = AppConnection.parse("6|10.10.10.1|41001|203.0.113.2|443")!!
        val other = AppConnection.parse("6|10.10.10.1|41002|203.0.113.2|443")!!
        val policy = AppRoutePolicy(setOf(10001), true) { if (it.local.port == 41001) 10001 else 10002 }
        assertTrue(policy.forceVpn(selected))
        assertFalse(policy.forceVpn(other))
        assertTrue(policy.forceVpn(null))
    }

    @Test fun ipv6UdpAndUnknownOwnersAreHandled() {
        val udp = AppConnection.parse("17|fd00::1|50000|2001:db8::2|443")!!
        assertEquals(17, udp.protocol)
        assertTrue(AppRoutePolicy(setOf(123), true) { 123 }.forceVpn(udp))
        assertFalse(AppRoutePolicy(setOf(123), true) { 124 }.forceVpn(udp))
        assertTrue(AppRoutePolicy(setOf(123), true) { -1 }.forceVpn(udp))
        assertTrue(AppRoutePolicy(setOf(123), true) { throw SecurityException() }.forceVpn(udp))
        assertFalse(AppRoutePolicy(emptySet(), false) { error("must not query") }.forceVpn(null))
    }

    @Test fun invalidMetadataNeverTriggersDnsOrDirectFallback() {
        for (value in listOf("6|example.com|123|1.1.1.1|443", "6|10.0.0.1|0|1.1.1.1|443", "99|10.0.0.1|1|1.1.1.1|443", "bad", "6|127.0.0.1|65536|1.1.1.1|443")) {
            assertNull(AppConnection.parse(value))
        }
    }
}
