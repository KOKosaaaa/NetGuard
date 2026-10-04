package com.smarttools.netguard.util

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.smarttools.netguard.model.AppSettings

/** Distinguishes an explicitly automatic icon in new backups from an absent legacy field. */
object LauncherIconBackup {
    private const val FOLLOW_THEME = "FOLLOW_THEME"

    fun forExport(settings: AppSettings): JsonObject = Gson().toJsonTree(settings).asJsonObject.apply {
        // Gson's default writer omits JSON nulls, including nulls in a JsonObject.
        // A string survives the outer export serializer without changing all null handling.
        if (settings.launcherIconTheme == null) addProperty("launcherIconTheme", FOLLOW_THEME)
    }

    fun restoreValue(value: JsonElement): JsonElement =
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString == FOLLOW_THEME)
            JsonNull.INSTANCE
        else value
}
