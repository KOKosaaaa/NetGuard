package com.smarttools.netguard

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.model.ThemeMode
import com.smarttools.netguard.util.LauncherIconBackup
import org.junit.Assert.*
import org.junit.Test

class LauncherIconBackupQaTest {
    private val gson = Gson()

    // Round-trip through the same outer Gson envelope used by both backup entry points.
    // Merely checking the in-memory JsonObject would miss Gson dropping explicit nulls.
    private fun wire(settings: AppSettings): String = gson.toJson(mapOf("settings" to LauncherIconBackup.forExport(settings)))

    private fun restored(current: AppSettings, wire: String): AppSettings {
        val incoming = JsonParser.parseString(wire).asJsonObject.getAsJsonObject("settings")
        val merged = gson.toJsonTree(current).asJsonObject
        for ((key, value) in incoming.entrySet()) {
            merged.add(key, if (key == "launcherIconTheme") LauncherIconBackup.restoreValue(value) else value)
        }
        return gson.fromJson(merged, AppSettings::class.java)
    }

    @Test fun automaticBackupOverridesExistingManualIconAfterRealSerialization() {
        val automatic = AppSettings(themeMode = ThemeMode.OCEAN, launcherIconTheme = null)
        val actualWire = wire(automatic)
        val settings = JsonParser.parseString(actualWire).asJsonObject.getAsJsonObject("settings")
        assertTrue("Automatic choice must survive the outer serializer", settings.has("launcherIconTheme"))
        val after = restored(AppSettings(themeMode = ThemeMode.LIGHT, launcherIconTheme = ThemeMode.FSOCIETY), actualWire)
        assertNull(after.launcherIconTheme)
        assertEquals(ThemeMode.OCEAN, after.launcherIconTheme ?: after.themeMode)
    }

    @Test fun legacyBackupWithoutNewFieldPreservesUsersCurrentManualIcon() {
        val before = AppSettings(launcherIconTheme = ThemeMode.OLED)
        val after = restored(before, """{"settings":{"themeMode":"OCEAN"}}""")
        assertEquals(ThemeMode.OLED, after.launcherIconTheme)
        assertEquals(ThemeMode.OCEAN, after.themeMode)
    }

    @Test fun everyManualChoiceRoundTripsWithoutChangingTheAppTheme() {
        for (theme in ThemeMode.values()) {
            val selected = AppSettings(themeMode = ThemeMode.DARK, launcherIconTheme = theme)
            val after = restored(AppSettings(launcherIconTheme = null), wire(selected))
            assertEquals(theme, after.launcherIconTheme)
            assertEquals(ThemeMode.DARK, after.themeMode)
        }
    }

    @Test fun explicitNullAndForeignValuesAreNotCoercedToATheme() {
        assertSame(JsonNull.INSTANCE, LauncherIconBackup.restoreValue(JsonNull.INSTANCE))
        for (value in listOf(JsonPrimitive(17), JsonPrimitive(false), JsonPrimitive("FOLLOW_THEME_typo"),
            JsonParser.parseString("{}"), JsonParser.parseString("[]"))) {
            assertSame(value, LauncherIconBackup.restoreValue(value))
        }
        assertNull(restored(AppSettings(launcherIconTheme = ThemeMode.LIGHT),
            """{"settings":{"launcherIconTheme":null}}""").launcherIconTheme)
    }

    @Test fun exportingAnIconDoesNotMutateOrDropUnrelatedSettings() {
        val original = AppSettings(launcherIconTheme = null, alwaysVpnApps = setOf("fixture.app"),
            triggerEnabled = true, primaryDns = "9.9.9.9")
        val exported = LauncherIconBackup.forExport(original)
        assertNull(original.launcherIconTheme)
        assertTrue(exported.get("triggerEnabled").asBoolean)
        assertEquals("9.9.9.9", exported.get("primaryDns").asString)
        assertEquals("fixture.app", exported.getAsJsonArray("alwaysVpnApps").single().asString)
    }
}
