package com.smarttools.netguard.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smarttools.netguard.core.XrayConfigGenerator
import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.model.ServerProfile

/** No CredentialManager.generate(): probing must not replace the live tunnel's credentials. */
internal object CandidateProbeConfig {
    fun generate(profile: ServerProfile, settings: AppSettings, endpoint: LocalSocks): String {
        require(!profile.protocol.usesRelay)
        val root = JsonParser.parseString(XrayConfigGenerator.generate(profile, settings, false).json).asJsonObject
        root.add("inbounds", JsonArray().apply { add(JsonObject().apply {
            addProperty("tag", "health-in")
            addProperty("listen", "127.0.0.1")
            addProperty("port", endpoint.port)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "password")
                addProperty("udp", false)
                add("accounts", JsonArray().apply { add(JsonObject().apply {
                    addProperty("user", endpoint.user); addProperty("pass", endpoint.password)
                }) })
            })
        }) })
        return root.toString()
    }
}
