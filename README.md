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
| Connection Map | Animated world map showing user-to-server connection line |
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

- Encrypted database (SQLCipher AES-256 + EncryptedSharedPreferences)
- Log redaction — UUIDs, passwords, Bearer tokens masked automatically
- SSRF protection — private/loopback/link-local IPv4 and IPv6 blocked in profile parser
- Tapjacking protection on critical buttons (filterTouchesWhenObscured)
- Deep link validation with confirmation dialog
- Atomic file writes (temp + rename pattern)
- No cleartext traffic (except speed test domains through VPN tunnel)
- No backup (`android:allowBackup="false"`)
- DNS leak prevention — all port 53 traffic forced through proxy
- Input size limits on URIs, subscriptions, imports
- Evil Twin WiFi detection (SSID+BSSID pair validation)

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
