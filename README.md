# NetGuard

Android VPN client built for privacy, security, and stealth. Powered by xray-core.

## What makes NetGuard different

Most VPN clients (v2rayNG, Hiddify, v2rayTUN) are great tools, but they share common weaknesses: credentials sitting on disk, open local SOCKS ports, IP leaks during network switches, and package names that scream "VPN" to any DPI system. NetGuard was designed to fix all of that.

### Zero-leak network switching

When you switch between WiFi and mobile data, typical clients tear down the entire VPN tunnel and reconnect from scratch. During that window your real IP leaks. NetGuard keeps the TUN interface alive and only restarts the internal xray + tun2socks processes. Packets are black-holed until the tunnel is back up — zero leak window.

### Ephemeral authenticated SOCKS5

Every connection generates a fresh random port, a UUID username, and a 32-character cryptographic password for the local SOCKS5 bridge between tun2socks and xray. Credentials are wiped from memory immediately after handshake. Even if another app scans localhost ports, it can't authenticate.

### Config never touches disk

The xray JSON config (containing server credentials, UUIDs, passwords) is written to a temp file, xray reads it, and the file is deleted — all within the same connection sequence. No config.json sitting in `/data/data/` for forensic extraction.

### Built-in security self-test

9 automated checks that run against your own device:

- Open SOCKS5/HTTP proxy ports (10808, 1080, 8080, etc.)
- Xray gRPC API exposure
- Clash REST API exposure
- `/proc/net/tcp` analysis for unexpected listeners
- VPN transport flag detection
- MTU anomaly detection
- Package name stealth analysis

### Evil Twin WiFi protection

Stores SSID+BSSID pairs for trusted WiFi networks. When connecting to a WiFi with a known SSID but unknown BSSID (possible Evil Twin attack), automatically enables VPN and sends a warning notification.

### Service availability testing

Test which services actually work through each server — YouTube, Telegram, Instagram, ChatGPT, Discord, Google, X/Twitter, Spotify. See a score like "3/8" before you connect.

### Stealth branding

Package name `com.smarttools.netguard`, notification says "Connection active / Network service is running" — no mention of VPN or proxy anywhere visible to system-level inspection.

## Features

**Protocols:** VLESS (+ REALITY), VMess, Trojan, Shadowsocks, Hysteria2

**Transports:** TCP, WebSocket, gRPC, HTTP/2, HTTP Upgrade, SplitHTTP, KCP, QUIC

| Feature | Details |
|---------|---------|
| Connection Map | Animated world map showing user-to-server connection arc |
| Speed Test | Download/upload/ping through VPN tunnel (OkHttp + raw SOCKS5) |
| WiFi Auto-Connect | Auto-enable VPN on untrusted WiFi + Evil Twin detection |
| Material You | Dynamic color theme on Android 12+ |
| Per-app routing | Whitelist / Blacklist / Disabled |
| Auto-select best server | TCP ping all servers, connect to fastest |
| Subscription management | Auto-update via WorkManager (6/12/24/48h) |
| QR code | Scan (ML Kit + CameraX) and generate (ZXing) |
| Deep link import | `vless://`, `vmess://`, `trojan://`, `ss://`, `hy2://` |
| Traffic stats | Real-time speed, session/daily/weekly/total counters |
| Home screen widget | One-tap connect/disconnect |
| Quick Settings tile | Android 7.0+ notification panel toggle |
| Boot auto-connect | Reconnect to last server on device restart |
| DNS | Custom primary/secondary, optional DoH through proxy |
| Routing modes | Global proxy / Rule-based (RU direct) / Direct |
| LAN bypass | Access local network devices while connected |
| Themes | Dark, Light, OLED Black, Ocean, Dynamic (Material You) |
| Languages | 17 languages |
| Backup/Restore | Export/import full config as JSON |
| Log viewer | Real-time xray logs with auto-redaction of credentials |

## Security hardening

- **Not vulnerable to the April 2026 VLESS local-SOCKS leak** that hit Happ, v2rayTUN, Hiddify, v2rayNG and NekoBox. NetGuard does not open any unauthenticated local SOCKS5 inbound. Both internal bridges (SOCKS5 for tun2socks, HTTP for internal tests) require the ephemeral 32-char password. See *Ephemeral authenticated SOCKS5* above.
- Encrypted database (SQLCipher AES-256 + EncryptedSharedPreferences)
- Log redaction — UUIDs, passwords, Bearer tokens masked automatically
- SSRF protection — private/loopback/link-local IPv4 and IPv6 blocked in profile parser
- Tapjacking protection on critical buttons (filterTouchesWhenObscured)
- Deep link validation with confirmation dialog
- Atomic file writes (temp + rename pattern)
- No cleartext traffic (except speed test domains through VPN tunnel)
- No backup (`android:allowBackup="false"`)
- DNS leak prevention — all port 53 traffic forced through proxy
- DNS address validation — loopback, private ranges and garbage strings rejected before they reach xray
- Input size limits on URIs, subscriptions, imports
- Evil Twin WiFi detection (SSID+BSSID pair validation)

## Release notes

### v1.1.7 (2026-05-02)

**App-launch trigger mode** — the headline feature. Pick which apps go through the VPN; everything else (banking, maps, your usual browser) keeps using the regular connection. The selected apps have no fallback path: if the tunnel is down, their packets are black-holed in the TUN — they never see your real IP, not even for a moment.

- New `ForegroundAppWatcher` service polls `UsageStatsManager` (250ms) to detect when a trigger app comes to the foreground.
- Pre-warm: when trigger mode is enabled, the tunnel comes up immediately and stays ready, so opening a trigger app is instant — no "Connecting…" delay.
- Optional auto-stop on background to save battery (xray killed, TUN keeps the apps black-holed).
- Boot-survival: `BootReceiver` re-arms the watcher and tunnel after device reboot.
- Auto-recover: if xray crashes, up to 3 silent restarts before any user-visible failure. In trigger mode the TUN is preserved as a quarantine even after xray gives up — never falls back to "no VPN" for trigger apps.
- Network sanity: skipping activation when no underlying network is available, surfacing it as "Нет сети" / "No network" instead of a stale "Connecting…".
- UI: dedicated screen with Material 3 switches, collapsible explainer, search box, system-app filter, save/cancel. Big red banner explains the Always-on VPN sweet spot ("turn ON Always-on, leave Block-without-VPN OFF").

**Bug fixes**
- Backup / Restore: profiles now keep their subscription link. Old format (v1, plain URI list) is still accepted; new exports use v2 with `{uri, subscription}` per profile.
- Settings: scroll position is preserved when you navigate into a sub-screen and back.
- Settings: bottom-nav tap always returns to the root of the tab — no more "I went into Trigger then tapped Servers and came back to Trigger with the wrong tab highlighted".
- Quick Settings tile: when there is no saved profile, opening the tile now triggers auto-select (best non-RU server) instead of silently doing nothing.
- DNS: when a non-empty Per-App blacklist is active, the VPN no longer overrides system DNS for the excluded apps. Previously they were forced onto Cloudflare/Google DNS, which is filtered by some Russian ISPs and broke their resolution.
- Connect button now triggers auto-select when no profile is picked, instead of doing nothing.
- Per-App routing description rewritten to make the relationship to Trigger mode obvious; toggling Trigger automatically disables Per-App routing to avoid silent conflict.

**Internals**
- Tunnel start re-ordered: TUN comes up before xray now, eliminating the leak window during start.
- Faster activation: tighter polling in `sendTunFd` and `waitForPort`, 100ms watcher tick, 50ms post-establish delay — saves ~600-700ms on cold open of a trigger app.
- `setUnderlyingNetworks` now passes ALL non-VPN networks (cellular + wifi if both available) instead of just the first one.

### v1.1.3 (2026-04-17)

**Security**
- Dropped the unauthenticated local SOCKS5 inbound (`speedtest-in`). That same pattern was disclosed as a leak for Happ, v2rayTUN, Hiddify and v2rayNG in April 2026: any app on the device could reach `127.0.0.1` and tunnel traffic through the VPN to learn the real server IP. Internal speed/service tests now talk to the authenticated `http-in` bridge instead.
- Restored DNS validation on the settings save path. It had regressed when the explicit "Save" button was removed, so `127.0.0.1`, `localhost`, private ranges and random typos were being silently accepted.
- TLS fragment and routing fields reject malformed input with a short toast instead of passing garbage through to xray.

**New features**
- **TLS Fragment.** Splits the TLS ClientHello into smaller pieces to slip past DPI that matches on SNI. Packets / length / interval are configurable in Settings.
- **Favorites.** Star a server to pin it to the top of the list. Room migrates v2 → v3 on first launch.
- **Domain / IP bypass list.** Lines of domains and IPs that go direct, regardless of the global routing mode.
- **Traffic statistics graph.** 7-day chart on the Home tab. 30 days of history retained.
- **Widget.** The home-screen widget is now 3×1 and shows the selected server + connection state. Tap anywhere on it to toggle the VPN.

**UI / cosmetic**
- Subscription list: the Share / Update / Delete buttons no longer overlap the subscription name and URL. Icons are theme-tinted so they stay visible on both light and dark backgrounds.
- Flat vector favorite stars in place of the legacy 3D Android star drawable.
- Connection map: adapts to light theme (bitmap is grayscale-inverted, land/ocean tints are swapped) and the dashed connection arc animates from the user to the server while the VPN is up.

**Reliability**
- The widget no longer runs a blocking Room query on the main broadcast thread. It reads the selected server name from a small SharedPreferences cache that's refreshed on profile select and on VPN start. Removes an ANR risk when the DB was locked during a subscription sync.
- Traffic history cleanup is one `apply()` per day archive (was 30) and the cleanup window is wide enough to recover history after the app was idle for months.

## Build

```bash
# Requires Android Studio with JBR (JetBrains Runtime)
JAVA_HOME="/path/to/android-studio/jbr" ./gradlew assembleDebug
```

**Requirements:**
- Android SDK 34
- Kotlin 1.9+
- Min SDK 26 (Android 8.0)

## Architecture

```
xray-core (VLESS/VMess/Trojan/SS/Hy2)
    |
    | authenticated SOCKS5 (random port, ephemeral creds)
    |
badvpn-tun2socks
    |
    | TUN file descriptor
    |
Android VpnService (TUN interface)
    |
    | per-app routing rules
    |
apps
```

## License

This project is provided as-is for personal use.
