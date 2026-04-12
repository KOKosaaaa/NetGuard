package com.smarttools.netguard.core

import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.model.ServerProfile

object ConfigBuilder {

    fun buildAndStart(profile: ServerProfile, settings: AppSettings): XrayConfigGenerator.GeneratedConfig {
        return XrayConfigGenerator.generate(
            profile = profile,
            settings = settings,
            useSocksInbound = true
        )
    }
}
