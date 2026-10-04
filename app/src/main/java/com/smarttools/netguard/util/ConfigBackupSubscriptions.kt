package com.smarttools.netguard.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Backup-local identities; display names are neither unique nor stable identifiers. */
internal object ConfigBackupSubscriptions {
    data class Subscription(val id: Long, val name: String, val url: String)
    data class Imported(val key: String?, val name: String, val url: String)

    fun export(profiles: List<Pair<String, Long>>, subscriptions: List<Subscription>, settings: JsonElement): JsonObject {
        val keys = subscriptions.mapIndexed { index, sub -> sub.id to "sub-${index + 1}" }.toMap()
        return JsonObject().apply {
            addProperty("version", 3)
            add("subscriptions", JsonArray().apply {
                subscriptions.forEach { sub -> add(JsonObject().apply {
                    addProperty("key", keys.getValue(sub.id))
                    addProperty("name", sub.name)
                    addProperty("url", sub.url)
                }) }
            })
            add("profiles", JsonArray().apply {
                profiles.forEach { (uri, subId) -> add(JsonObject().apply {
                    addProperty("uri", uri)
                    keys[subId]?.let { addProperty("subscriptionKey", it) }
                }) }
            })
            add("settings", settings)
        }
    }

    fun subscriptions(root: JsonObject): List<Imported> {
        val keys = mutableSetOf<String>()
        return root.getAsJsonArray("subscriptions")?.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = obj.get("name")?.asString ?: return@mapNotNull null
            val url = obj.get("url")?.asString ?: return@mapNotNull null
            val key = obj.get("key")?.takeUnless { it.isJsonNull }?.asString
            require(key == null || (key.isNotBlank() && keys.add(key))) { "Duplicate or empty subscription key" }
            Imported(key, name, url)
        }.orEmpty()
    }

    /** v1 strings and v2 name references remain readable; an explicit v3 key never falls back to a name. */
    fun profileGroups(root: JsonObject, idsByKey: Map<String, Long>, idsByLegacyName: Map<String, Long>): Map<Long, List<String>> {
        val groups = linkedMapOf<Long, MutableList<String>>()
        root.getAsJsonArray("profiles")?.forEach { element ->
            val uri: String
            val id: Long
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                uri = obj.get("uri")?.asString ?: return@forEach
                id = if (obj.has("subscriptionKey")) {
                    obj.get("subscriptionKey")?.takeUnless { it.isJsonNull }?.asString?.let(idsByKey::get) ?: 0L
                } else obj.get("subscription")?.asString?.let(idsByLegacyName::get) ?: 0L
            } else if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                uri = element.asString
                id = 0L
            } else return@forEach
            groups.getOrPut(id) { mutableListOf() }.add(uri)
        }
        return groups
    }
}
