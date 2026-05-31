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
     * When true (default) trigger mode behaves as strict allow-list: ONLY the
     * apps in triggerApps route through the tunnel; they are blackholed when
     * the tunnel is down (no real-IP leak). When false, trigger apps merely
     * act as launch triggers — opening any of them brings the tunnel up with
     * the user's normal perAppMode/perAppList rules; routing is then governed
     * by per-app/global settings, not by triggerApps.
     */
    val triggerStrictMode: Boolean = true,
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
    val autoBypassRuPackages: Boolean = false,
    /**
     * Expert mode. Default OFF keeps the UI approachable for non-technical
     * users: the Logs tab, multi-hop chains, trigger mode, manual port/SNI
     * and other power features stay hidden until this is enabled
     * (Settings → Expert mode). Gson defaults the missing field to false,
     * so existing installs start in Simple mode.
     */
    val expertMode: Boolean = false,
    /**
     * Experimental: for multi-room Telemost profiles, split each connection's
     * bytes across ALL rooms (striping) instead of pinning a connection to one
     * room (round-robin). Lets a single big transfer use the rooms' aggregate
     * bandwidth, but needs the stripe-server deployed on the exit. Default OFF
     * keeps the proven round-robin path; exposed only under Expert mode.
     */
    val telemostStriping: Boolean = false
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
    FSOCIETY,
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
