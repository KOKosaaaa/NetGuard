package com.smarttools.netguard

import com.smarttools.netguard.util.GeoLookup
import com.smarttools.netguard.util.GeoResultCache
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** No DNS or public geolocation requests: deterministic retry and name semantics. */
class GeoLookupQaTest {
    @Test fun temporaryFailureRetriesAtBoundaryAndSuccessfulRecoveryHasSeparateLifetime() {
        var now = 10L
        var calls = 0
        val cache = GeoResultCache<String>({ now }, successTtlMs = 100, failureTtlMs = 20)
        assertNull(cache.lookup("host") { calls++; null })
        now = 29
        assertNull(cache.lookup("host") { calls++; "too early" })
        assertEquals(1, calls)
        now = 30
        assertEquals("restored", cache.lookup("host") { calls++; "restored" })
        now = 129
        assertEquals("restored", cache.lookup("host") { calls++; null })
        now = 130
        assertEquals("moved exit", cache.lookup("host") { calls++; "moved exit" })
        assertEquals(3, calls)
    }

    @Test fun lookupTimeDoesNotConsumeItsOwnCacheLifetime() {
        var now = 0L
        val cache = GeoResultCache<String>({ now }, successTtlMs = 100, failureTtlMs = 20)
        assertEquals("slow", cache.lookup("slow") { now = 500; "slow" })
        now = 599
        assertEquals("slow", cache.lookup("slow") { error("Premature expiry after slow resolver") })
        now = 600
        assertEquals("new", cache.lookup("slow") { "new" })
    }

    @Test fun failuresAndSuccessesAreIsolatedByEndpointOrCredentialGeneration() {
        val cache = GeoResultCache<String>({ 0L }, 100, 20)
        assertNull(cache.lookup("gen1:port") { null })
        assertEquals("new-session", cache.lookup("gen2:port") { "new-session" })
        assertEquals("other", cache.lookup("other-host") { "other" })
        assertNull(cache.lookup("gen1:port") { error("Negative cache not retained") })
        assertEquals("new-session", cache.lookup("gen2:port") { error("Successful session cache lost") })
    }

    @Test fun boundedCacheRemainsUsableAfterManyDistinctTemporaryFailures() {
        val cache = GeoResultCache<String>({ 0L }, 100, 20)
        repeat(10_000) { assertNull(cache.lookup("failed-$it") { null }) }
        val entries = cache.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(cache) as Map<*, *>
        assertTrue("Unbounded host cache", entries.size <= 513)
        assertEquals("ok", cache.lookup("current") { "ok" })
    }

    @Test fun serverNamesRecognizeFlagsAirportsAndNumberedCountryTokens() {
        val cases = mapOf("hel · WB Stream" to "FI", "🇩🇪 DE2 / Reality" to "DE", "DE2" to "DE",
            "1. AMS - xhttp" to "NL", "[FRA] grpc" to "DE", "🇯🇵 Tokyo" to "JP",
            "Хельсинки · основной" to "FI", "Warsaw" to "PL", "Finland" to "FI")
        cases.forEach { (name, code) ->
            assertEquals(name, code, GeoLookup.countryCodeFromName(name))
            assertNotNull(name, GeoLookup.fromProfileName(name))
        }
    }

    @Test fun wordsContainingCityOrCountrySubstringsDoNotInventCoordinates() {
        for (name in listOf("CHROME", "SHELL", "NODE2", "Amsterdammer", "PARISIAN", "random-server")) {
            assertNull(name, GeoLookup.countryCodeFromName(name))
            assertNull(name, GeoLookup.fromProfileName(name))
        }
    }

    @Test fun casingIsIndependentOfTurkishDeviceLocaleAndFlagsAreWholeCodepoints() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("FI", GeoLookup.countryCodeFromName("finland"))
            assertEquals("FI", GeoLookup.countryCodeFromName("🇫🇮"))
            assertNull(GeoLookup.countryCodeFromName("🇫"))
            assertNull(GeoLookup.countryCodeFromName("🇿🇿"))
        } finally { Locale.setDefault(before) }
    }
}
