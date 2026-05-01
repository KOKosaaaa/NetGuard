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
    val showSpeedInNotification: Boolean = false,
    val showConnectionMap: Boolean = true,
    val showSpeedTest: Boolean = true,
    val autoConnectWifi: Boolean = false,
    val trustedWifiList: Set<String> = emptySet(),
    val tlsFragmentEnabled: Boolean = false,
    val tlsFragmentPackets: String = "tlshello",
    val tlsFragmentLength: String = "100-200",
    val tlsFragmentInterval: String = "10-20",
    val bypassDomains: String = "",
    val bypassIps: String = "",
    val trafficStatsMode: TrafficStatsMode = TrafficStatsMode.SIMPLE,
    val triggerEnabled: Boolean = false,
    val triggerApps: Set<String> = emptySet(),
    val triggerAutoStop: Boolean = false,
    /**
     * uTLS fingerprint override. When `random`, XrayConfigGenerator picks a
     * random one from the supported list per connection so that DPI systems
     * cannot correlate sessions by ClientHello shape.
     */
    val tlsFingerprintMode: TlsFingerprintMode = TlsFingerprintMode.CHROME,
    /**
     * If true, every PACKAGE_ADDED broadcast for a non-system package whose
     * packageName starts with "ru." appends it to perAppList automatically,
     * matching the one-shot "Exclude all Russian apps" button.
     */
    val autoBypassRuPackages: Boolean = false
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
    OCEAN,
    DYNAMIC
}

enum class PerAppMode {
    DISABLED,
    WHITELIST,
    BLACKLIST
}

enum class TrafficStatsMode {
    CHART,
    SIMPLE,
    HIDDEN
}

enum class TlsFingerprintMode {
    CHROME,
    FIREFOX,
    SAFARI,
    IOS,
    EDGE,
    RANDOM
}
