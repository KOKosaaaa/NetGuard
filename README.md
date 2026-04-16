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

- **Not vulnerable to the April 2026 VLESS local-SOCKS leak** affecting Happ, v2rayTUN, Hiddify, v2rayNG, NekoBox and others. No unauthenticated local SOCKS5 inbound is ever exposed — both internal bridges (SOCKS5 for tun2socks, HTTP for internal speed/service tests) require the ephemeral 32-char password. See *Ephemeral authenticated SOCKS5* above.
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

### v1.1.3 — 2026-04-17

**Security**
- Removed the unauthenticated local SOCKS5 inbound (`speedtest-in`) that was used for internal speed/service tests. This closes the same class of leak disclosed for Happ/v2rayTUN/Hiddify/v2rayNG in April 2026, where any app on the device could reach `127.0.0.1:<port>` and tunnel traffic to learn the real VPN server IP. Internal tests now go through the authenticated HTTP bridge (`http-in`).
- DNS field validation restored on the settings-save path (was silently accepting loopback / private / garbage strings after the old explicit "Save" button was removed).
- TLS-fragment and routing fields gain format validation with a "rejected values" toast instead of silently saving whatever was typed.

**New features**
- **TLS Fragment** — bypass DPI by splitting the TLS ClientHello into smaller pieces. Configurable packets / length / interval in Settings.
- **Favorites** — star any server to pin it to the top of the list. Room schema v2→v3 migration.
- **Domain / IP Bypass List** — free-form lists of domains and IPs that should go direct instead of through the VPN (independent of the global routing mode).
- **Traffic Statistics Graph** — 7-day history chart on the Home tab. 30 days of daily history retained.
- **Widget Enhancement** — the home-screen widget grew from a 1×1 icon to a 3×1 card showing the selected server name and connection status. Tap anywhere on the widget to toggle the VPN.

**UI / cosmetic**
- Subscription list: Share / Update / Delete buttons no longer overlap the subscription name and URL; icons are theme-tinted so they stay visible on light and dark backgrounds.
- Server favorites: flat vector stars replace the Android 2.x glossy 3D star drawable.
- Connection map: adapts to light theme (bitmap is grayscale-inverted on the fly, land/ocean tints swap), and the dashed connection arc now animates from the user to the server while the VPN is connected.

**Reliability**
- Home-screen widget no longer runs a blocking Room query on the main broadcast thread (potential ANR if the DB was locked during a subscription sync). The selected server name is read from a small SharedPreferences cache that's updated on profile select and VPN start.
- Traffic-history cleanup is batched into a single `SharedPreferences.apply()` (was 30 individual commits per day archive) and the cleanup window is wide enough to recover history after the app hasn't been opened for >60 days.

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
