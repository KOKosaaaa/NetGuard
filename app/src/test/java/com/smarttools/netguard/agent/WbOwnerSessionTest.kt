package com.smarttools.netguard.agent

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.JsonParser

class WbOwnerSessionTest {
    @Test fun endpointCookieWinsOverStaleRootCookie() {
        val storage = """{"device_id":"device","auth_slice":"{\"accessToken\":\"bearer\"}"}"""
        val result = JsonParser.parseString(WbOwnerSession.build("https://stream.wb.ru/room/test", storage,
            listOf("wbx-refresh=endpoint; wbx-refresh=root; unrelated=private", "wbx-refresh=old", "wbx-refresh="))).asJsonObject
        assertEquals("endpoint", result["cookies"].asJsonObject["wbx-refresh"].asString)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun captureErrorsDistinguishStorageFromMissingRefreshWithoutLeakingSecrets() {
        fun reason(storage: String, cookies: List<String> = listOf("wbx-refresh=secret-cookie")): WbOwnerSession.Reason {
            try { WbOwnerSession.build("https://stream.wb.ru/room/test", storage, cookies); fail("invalid session accepted") }
            catch (e: WbOwnerSession.CaptureException) {
                assertFalse(e.message.orEmpty().contains("secret"))
                return e.reason
            }
            error("unreachable")
        }
        assertEquals(WbOwnerSession.Reason.STORAGE, reason("null"))
        assertEquals(WbOwnerSession.Reason.STORAGE, reason("{}"))
        assertEquals(WbOwnerSession.Reason.DEVICE, reason("""{"auth_slice":"secret"}"""))
        assertEquals(WbOwnerSession.Reason.ACCESS, reason("""{"device_id":"device","auth_slice":"secret-malformed"}"""))
        assertEquals(WbOwnerSession.Reason.REFRESH, reason("""{"device_id":"device","auth_slice":"{\"accessToken\":\"secret-token\"}"}""", emptyList()))
    }
    @Test fun onlyOfficialOriginAndRequiredLoginAreExported() {
        val storage = """{"device_id":"device","auth_slice":"{\"accessToken\":\"bearer\"}"}"""
        val result = WbOwnerSession.build("https://stream.wb.ru/room/test", storage,
            listOf("unrelated=private; wbx-refresh=refresh; x_wbaas_token=phone-ip"))
        assertEquals("refresh", JsonParser.parseString(result).asJsonObject["cookies"].asJsonObject["wbx-refresh"].asString)
        assertFalse(result.toString().contains("private"))
        assertFalse(result.toString().contains("phone-ip"))
        for (url in listOf("http://stream.wb.ru", "https://stream.wb.ru.evil/", "https://evil@stream.wb.ru/", "https://stream.wb.ru:8443/")) assertFalse(WbOwnerSession.isWbOrigin(url))
        try { WbOwnerSession.build("https://stream.wb.ru/", storage, emptyList()); fail("guest exported") } catch (_: IllegalArgumentException) { }
    }
}
