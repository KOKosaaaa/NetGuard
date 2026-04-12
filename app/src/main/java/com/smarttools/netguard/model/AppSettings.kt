package com.smarttools.netguard.model

data class AppSettings(
    val routingMode: RoutingMode = RoutingMode.GLOBAL_PROXY,
    val primaryDns: String = "1.1.1.1",
    val secondaryDns: String = "8.8.8.8",
    val dohEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val language: String = "system",
    val bypassLan: Boolean = true,
    val enableIpv6: Boolean = true,
    val perAppMode: PerAppMode = PerAppMode.DISABLED,
    val perAppList: Set<String> = emptySet(),
    val showSpeedInNotification: Boolean = true
)

enum class RoutingMode {
    GLOBAL_PROXY,
    RULE_BASED,
    DIRECT
}

enum class ThemeMode {
    DARK,
    LIGHT,
    OLED,
    OCEAN
}

enum class PerAppMode {
    DISABLED,
    WHITELIST,
    BLACKLIST
}
