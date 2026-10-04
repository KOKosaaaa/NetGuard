package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test

class WifiSecurityPolicyQaTest {
    private val home = "Home|aa:bb:cc:dd:ee:01"

    @Test fun redactedIdentityNeverBypassesVpnOrClaimsEvilTwin() {
        for (name in listOf(null, "", "<unknown ssid>", "0x"))
            assertEquals(WifiSecurityPolicy.Decision.UNKNOWN,
                WifiSecurityPolicy.classify(name, "aa:bb:cc:dd:ee:01", listOf(home)))
        for (address in listOf(null, "02:00:00:00:00:00", "00:00:00:00:00:00", "invalid"))
            for (entries in listOf(listOf(home), listOf("Home")))
                assertEquals(WifiSecurityPolicy.Decision.UNKNOWN,
                    WifiSecurityPolicy.classify("Home", address, entries))
    }

    @Test fun readableKnownAndDifferentNetworksRemainDistinguishable() {
        assertEquals(WifiSecurityPolicy.Decision.TRUSTED,
            WifiSecurityPolicy.classify("\"Home\"", "AA:BB:CC:DD:EE:01", listOf(home)))
        assertEquals(WifiSecurityPolicy.Decision.DIFFERENT_BSSID,
            WifiSecurityPolicy.classify("Home", "aa:bb:cc:dd:ee:02", listOf(home)))
        assertEquals(WifiSecurityPolicy.Decision.UNTRUSTED,
            WifiSecurityPolicy.classify("Cafe", "aa:bb:cc:dd:ee:01", listOf(home)))
        assertEquals(WifiSecurityPolicy.Decision.TRUSTED,
            WifiSecurityPolicy.classify("Home", "aa:bb:cc:dd:ee:02", listOf("Home")))
        assertEquals(WifiSecurityPolicy.Decision.UNTRUSTED,
            WifiSecurityPolicy.classify("Home", "aa:bb:cc:dd:ee:02", listOf("Home|02:00:00:00:00:00")))
    }

    @Test fun oldNetworkLossCannotCancelNewAssociation() {
        val owner = WifiEventOwner()
        val old = owner.observe("network-A")
        val current = owner.observe("network-B")
        owner.lost("network-A")
        assertFalse(owner.current(old))
        assertTrue(owner.canAttempt(current, "unknown", 0))
        owner.record(old, "unknown", true, 0)
        assertTrue(owner.canAttempt(current, "unknown", 0))
    }

    @Test fun failedServiceAdmissionIsNotRememberedAsSuccess() {
        val owner = WifiEventOwner()
        val token = owner.observe("wifi")
        owner.record(token, "unknown", false, 100)
        assertFalse(owner.canAttempt(token, "unknown", 30_099))
        assertTrue(owner.canAttempt(token, "unknown", 30_100))
        owner.record(token, "unknown", true, 30_100)
        assertFalse(owner.canAttempt(token, "unknown", 90_000))
        assertTrue(owner.canAttempt(token, "new-readable-identity", 90_000))
    }

    @Test fun lostOrUnregisteredWorkCannotStartEvenAfterSameNetworkReturns() {
        val owner = WifiEventOwner()
        val first = owner.observe("wifi")
        owner.record(first, "Home", true, 0)
        owner.lost("wifi")
        val second = owner.observe("wifi")
        assertFalse(owner.canAttempt(first, "Cafe", 60_000))
        assertTrue(owner.canAttempt(second, "Home", 60_000))
        owner.reset()
        assertFalse(owner.canAttempt(second, "Home", 60_000))
        assertTrue(owner.canAttempt(owner.observe("wifi"), "Home", 60_000))
    }
}
