# NetGuard

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Android VPN client built for privacy, security, and stealth. Powered by xray-core.

Licensed under the **Apache License, Version 2.0** — see [`LICENSE`](LICENSE). You are free to use, modify, distribute, and sublicense the code (including for commercial use), provided you preserve the copyright notice. The Apache-2.0 patent grant also protects downstream users from patent claims by contributors.

## What makes NetGuard different

Most VPN clients (v2rayNG, Hiddify, v2rayTUN) are great tools, but they share common weaknesses: credentials sitting on disk, open local SOCKS ports, IP leaks during network switches, and package names that scream "VPN" to any DPI system. NetGuard was designed to fix all of that.

### Zero-leak network switching

When you switch between WiFi and mobile data, typical clients tear down the entire VPN tunnel and reconnect from scratch. During that window your real IP leaks. NetGuard keeps the TUN interface alive and only restarts the internal xray + tun2socks processes. Packets are black-holed until the tunnel is back up — zero leak window.

### Ephemeral authenticated SOCKS5

Every connection generates a fresh random port, a UUID username, and a 32-character random password for the local SOCKS5 bridge between tun2socks and xray. Credentials are cleared from memory on disconnect or network reconnect (they are kept alive during a session so that internal speed-test / service-test requests can reauthenticate through the HTTP bridge). Even if another app scans localhost ports, it cannot authenticate without the ephemeral password.

### Config minimally exposed on disk

The xray JSON config (containing server credentials, UUIDs, passwords) is written to app-private internal storage only long enough for xray to read it, then immediately `unlink()`ed once the SOCKS inbound is up. No config.json is kept across sessions, and it is never visible to other apps. Note that on ext4/F2FS the underlying blocks may persist until overwritten — this is a brief-exposure design, not a never-on-disk one.

### Built-in security self-test

9 automated checks that run against your own device:

- Open SOCKS5/HTTP proxy ports (10808, 1080, 8080, etc.)
- Xray gRPC API exposure
- Clash REST API exposure
- `/proc/net/tcp` analysis for unexpected listeners
- VPN transport flag detection
- MTU informational report (non-decisive — see self-test details)
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
- **Honest caveat on password surface.** The SOCKS5 username and password are passed to the `tun2socks` helper process via command-line arguments, so they appear in `/proc/<tun2socks_pid>/cmdline`. On modern Android this file is protected by SELinux `app_data_file` contexts and hidepid, so other apps cannot read it, but a rooted attacker or the same-uid process can. The password is ephemeral per session, so disclosure only compromises the current tunnel's local bridge, not the server credentials. Migration to stdin / fd-based credential passing is tracked as a future hardening step.
- EncryptedSharedPreferences via `androidx.security:security-crypto` for small secrets (DB key material placeholder, credentials cache). Full database-level encryption via SQLCipher is on the roadmap — see *Known limitations* below.
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

## Known limitations

- **Room database is currently plain**, despite earlier wording. The SQLCipher dependency was declared but never wired as the Room `SupportFactory`, and the app deletes any previously-encrypted DB on first launch of a new version (see `AppDatabase.deleteEncryptedIfNeeded`). Profile data is already re-fetchable from subscriptions, so this is low-impact, but a proper SQLCipher integration is tracked as future work.
- **`androidx.security:security-crypto` was deprecated by Google in 2024.** The 1.1.0-alpha06 release still works on current Android, but Android 15/16 may change backing-store behaviour without backward-compatibility guarantees. Migration path: move to `java.security.KeyStore.getInstance("AndroidKeyStore")` directly, generate the AES-256 key via `KeyGenerator`, and store ciphertext in plain `SharedPreferences`. Tracked, not urgent.
- **tun2socks credential exposure.** See *Security hardening → honest caveat on password surface* above.

## Acknowledgements

NetGuard is composed of a small original glue layer wired around a stack of open-source components that do the heavy lifting. The honest credit list — without these projects, this app could not exist.

### Tunnel core (native)

- **[xray-core](https://github.com/XTLS/Xray-core)** — Mozilla Public License 2.0. Project X. The actual proxy engine that speaks VLESS / VMess / Trojan / Shadowsocks / Hysteria2 and handles TLS / REALITY / uTLS fingerprinting. Shipped as `libxray.so` in `jniLibs/arm64-v8a/`.
- **[badvpn / tun2socks](https://github.com/ambrop72/badvpn)** — BSD-3-Clause. Ambroz Bizjak. Userspace TUN-to-SOCKS5 helper that turns the Android `VpnService` TUN file descriptor into TCP/UDP streams that xray can consume. Shipped as `libtun2socks.so`.

### Android libraries

- **[AndroidX](https://developer.android.com/jetpack/androidx)** (Core, AppCompat, ConstraintLayout, SwipeRefreshLayout, Navigation, Lifecycle, Room, Preference, WorkManager, CameraX, Security-Crypto) — Apache-2.0. Google.
- **[Material Components for Android](https://github.com/material-components/material-components-android)** — Apache-2.0. Google. Material You theming, dialogs, bottom-nav, dynamic colors.
- **[OkHttp](https://github.com/square/okhttp)** — Apache-2.0. Square. HTTP client used for subscription fetching, certificate pinning, and DNS-rebinding-safe resolution.
- **[Gson](https://github.com/google/gson)** — Apache-2.0. Google. JSON serialisation for xray config generation and profile import/export.
- **[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)** — Apache-2.0. JetBrains. Async runtime for `TunnelVpnService`, watchdogs, network callbacks.
- **[ZXing Core](https://github.com/zxing/zxing)** — Apache-2.0. Generates QR codes for sharing profiles.
- **[ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)** — Apache-2.0 (Google Play Services component). Scans QR-coded subscription / profile URIs.
- **[JUnit 4](https://github.com/junit-team/junit4)** — EPL-1.0. Test runner.

### Inspiration / prior art

The architecture of NetGuard's `TunnelVpnService` (TUN file descriptor handover via Unix-domain socket, watchdog loop, no-leak network switching) draws from the broad Android-VPN-with-xray prior art established by:

- **[v2rayNG](https://github.com/2dust/v2rayNG)** — GPL-3.0. The reference implementation for an Android Xray client; defined many of the patterns we still use.
- **[Hiddify](https://github.com/hiddify/hiddify-app)** — GPL-3.0. Subscription / profile parser conventions.
- **[NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)** — GPL-3.0. UI patterns for per-app routing and traffic stats.

NetGuard does **not** reuse code from these projects directly (NetGuard is Apache-2.0; copying GPL-3.0 code would force a relicense), but ideas and protocol-level conventions are owed to them.

### Tooling

- **[Claude Code](https://claude.com/claude-code)** — Anthropic. Performed the v1.1.4 / v1.1.5 / v1.1.6 security audit, wrote the `ServerPreflight` / `PackageInstallReceiver` / TLS-rotation / MTU-probing changes, the JVM unit-test suite, and most of this README. AI-assisted commits are tagged with `Co-Authored-By: Claude` in their trailer when material code was generated by the assistant.

If we missed your project here, please open an issue — credit is the one thing we can give back, and we want the list to be complete.

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

### v1.1.6 — 2026-04-30

**Security audit (P0/P1 fixes)**
- **No more HTTP fallback in geo-IP lookup.** `GeoLookup.kt` previously fell back to `http://ip-api.com/json/` when the HTTPS `ipwho.is` provider was unreachable. Any on-path observer would have seen the real source IP in plaintext during that fallback. The HTTP path is now removed entirely; if HTTPS lookup fails, the result is simply `null`.
- **Geo cache moved to `EncryptedSharedPreferences`.** User coordinates were previously cached in plain `SharedPreferences` (`/data/data/<pkg>/shared_prefs/geo_cache.xml`). A privileged co-resident process or root could read them. The cache is now stored under `geo_cache_enc` with `MasterKey` AES-256-GCM, with a one-shot migration that wipes the legacy plaintext on first launch.
- **Profile validation before xray config build.** `XrayConfigGenerator.validateProfile()` now rejects `port` outside `1..65535` and any control-character (`\x00..\x1f`, `\x7f`) in `address`, `host`, `path`, `sni`, `serviceName`, `authority`, `publicKey`, `shortId`, `alpn` *before* the JSON is fed to xray. A corrupt stored profile no longer kills the tunnel through the watchdog loop; instead a single readable error surfaces.
- **No more `Thread.sleep` on Dispatchers.IO.** `sendTunFd()` and `startTun2socksProcess()` are now `suspend` and use `delay()` while waiting for the tun2socks Unix-domain socket. The previous `Thread.sleep(200)` calls were blocking IO worker threads up to 2 s per startup.
- **Concurrency: `@Volatile` on cross-thread service state.** `vpnFd`, `serviceScope`, `trafficMonitor`, `xrayProcess`, `tun2socksProcess`, `networkCallback`, `currentProfileId`, `showSpeedNotification`, `xrayWatchdogJob`, `tun2socksWatchdogJob`, `lastNotificationUpdate` are now all `@Volatile` (or `synchronized(fdLock)` for `vpnFd`). Writes from the main thread are now visible immediately to the watchdogs running on `Dispatchers.IO`, so a watchdog cannot see a stale `null` and skip recovery.
- **Faster network-switch reconnect.** WiFi↔LTE handover debounce reduced from 2000 ms to 500 ms — the TUN black-hole window during a clean handover is now sub-second.
- **`ACCESS_FINE_LOCATION` capped at API 32.** `NEARBY_WIFI_DEVICES` (`neverForLocation`) covers SSID/BSSID reads on Android 13+, so the privacy-sensitive permission is no longer requested on devices where it is no longer needed.

**VPN-detection bypass (per-app)**
- **New "Exclude all Russian apps" / "Исключить приложения РФ" button** in *Settings → Per-app routing*. One tap sweeps every installed non-system app whose `packageName` starts with `ru.` and adds them to the per-app list, plus a curated set of Russian apps that publish under `com.*` / `io.*` (Tinkoff, Sberbank, Otkritie, Wildberries, Ozon, ICBC, AliExpress AER, the full Yandex stack, etc.). When `PerAppMode.BLACKLIST` is active, those apps see the real underlying network instead of the tunnel — their IP / GeoIP / `TRANSPORT_VPN` flag stays consistent with the SIM/Wi-Fi region. This is the only client-side mitigation available without root against the published "VPN/Proxy detection on client devices" methodology, which relies on `ConnectivityManager.NetworkCapabilities.TRANSPORT_VPN`, `VpnTransportInfo`, and `dumpsys vpn_management` — none of which can be hidden by an app from another app at the OS level. The button is locale-gated (visible only on `ru` / `en`) so non-Russian-locale users do not see an unhelpful action.
- **Auto-bypass for newly installed RU apps.** A `PACKAGE_ADDED` `BroadcastReceiver` (registered dynamically because Android 8+ disallows static manifest `PACKAGE_ADDED` for regular apps) appends every freshly-installed non-system app whose `packageName` starts with `ru.` to `perAppList`, but only when the new `autoBypassRuPackages` toggle is on. Without this the one-shot button only protects apps that exist at the moment the user taps it.

**Tunnel reliability**
- **MTU-probing.** `VpnService.Builder.setMtu()` and `tun2socks --tunmtu` are now derived from `ConnectivityManager.getLinkProperties().getMtu()` of the active non-VPN network, capped to `[1280, 1500]`. On many mobile carriers the LTE/5G link MTU is 1428 or 1450 — the previous hard-coded `1500` caused silent IP fragmentation and a 5–15 % throughput drop.
- **Pre-flight reachability check.** Before xray + tun2socks are spun up, `ServerPreflight` does a 1.5 s DNS + 2 s TCP probe of the target address. A dead server short-circuits to `ConnectionState.Error` in <2 s instead of burning the full 30 s tunnel-establishment budget. UDP-only protocols (Hysteria2) fall back to DNS-success-only since we cannot meaningfully ping UDP without speaking the protocol.
- **Auto-failover.** When `startTunnel()` fails (timeout, preflight dead, xray crash, tun2socks exit) the service automatically tries the next untried profile from the same subscription (or the next one in the database if the current profile has no subscription), with a budget of 3 attempts. The ledger resets on a successful `Connected` state and on user-initiated `stopTunnel`.
- **TLS fingerprint randomisation.** New `TlsFingerprintMode` setting (`CHROME` / `FIREFOX` / `SAFARI` / `IOS` / `EDGE` / `RANDOM`) feeds the uTLS fingerprint string used for VLESS / VMess / Trojan TLS and REALITY outbounds. In `RANDOM` mode a fresh fingerprint is picked per connect, so DPI systems cannot correlate consecutive sessions by ClientHello shape. Per-profile override (set by `ProfileParser` when the URL carries `fp=…`) still wins over the global setting. Fixed a long-standing bug where `ServerProfile.fingerprint` defaulted to `"chrome"` instead of `""`, which made any global rotation a no-op for stored profiles.

**Tests**
- New JVM unit tests under `app/src/test/java/com/smarttools/netguard/core/`:
  - `XrayConfigValidationTest` — port range, control-character rejection in 9 string fields, fingerprint resolver (default / global / random / per-profile override), SOCKS port + credentials sanity (11 cases).
  - `ServerPreflightTest` — empty / unresolvable / closed-port / listening-port / UDP-only paths (5 cases).
- `app/build.gradle.kts` enables `testOptions.unitTests.isReturnDefaultValues = true` so `android.util.Log` calls in code under test do not throw `Method d in android.util.Log not mocked.`

### v1.1.4 — 2026-04-18

**Security**
- **SSRF hardening.** All private / loopback / link-local / CGNAT (`100.64.0.0/10`) / reserved (`240.0.0.0/4`) / IPv4-mapped-IPv6 / hex-and-octal IPv4 literals (`0x7f.0.0.1`, `0177.0.0.1`) are now rejected by a single `AddressValidator`. The old spot checks on `startsWith("10.")`, `"192.168."` etc. were trivially defeated by alternative textual forms; the new validator resolves literals numerically via `InetAddress` and inspects the resulting bytes.
- **DNS rebinding guard.** Subscription fetches now go through a custom OkHttp `Dns` resolver that re-validates every address returned at connect time, so an attacker who controls the subscription host's DNS cannot swap the validated public IP for a private LAN IP between validation and connection.
- **Ephemeral SOCKS credentials.** The local SOCKS5 / HTTP bridge credentials are now stored as `CharArray` inside `CredentialManager` and actively zeroed on `clear()`, instead of lingering as immutable `String` heap residue until GC.
- **No-leak network switching.** `VpnService.setUnderlyingNetworks()` is now pinned to the active non-VPN network both at tunnel establishment and on every WiFi↔LTE switch, eliminating the narrow window where xray's outbound socket could transiently route over the old interface.
- **Honest README.** Claims that were not strictly true in the code — "credentials wiped from memory immediately after handshake", "config never touches disk", "encrypted database" — have been rewritten to reflect what actually happens. The matching *Known limitations* section documents tracked items (SQLCipher integration, `security-crypto` deprecation, tun2socks credential argv exposure).

**Platform compat**
- **Android 14 FGS.** Foreground-service type migrated to `specialUse | systemExempted` with the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declaration. The previous `specialUse` on Android 14 raised a runtime `SecurityException` at `startForeground()`; a naive migration to `connectedDevice` also fails because that type requires holding one of BLUETOOTH/USB/NFC permissions that a VPN legitimately cannot claim.
- **Android 13+ WiFi.** `NEARBY_WIFI_DEVICES` permission (with `neverForLocation`) is now declared and requested at runtime when the user opens the Trusted WiFi dialog or flips the auto-connect toggle; without it, SSID read silently returned `<unknown ssid>` on stock Android 13+.
- **IPv6 route is now conditional on the `Enable IPv6` setting.** Previously `addRoute("::", 0)` was claimed unconditionally — on IPv4-only servers, AAAA-bound traffic disappeared into tun2socks instead of falling back gracefully.

**WiFi auto-connect reliability**
- SSID/BSSID lookup falls back to `WifiManager.connectionInfo` when Android strips `transportInfo` from background callbacks.
- Events are de-duplicated by `(SSID, BSSID)` so the throttled `onCapabilitiesChanged` firehose (RSSI / link-speed / validation updates) no longer spams `TunnelVpnService.start()`.
- If the first capability event arrives with `<unknown ssid>` (Wi-Fi still associating), the manager retries at 2 s / 5 s / 10 s via `WifiManager` instead of losing the event.
- An initial probe runs after `registerNetworkCallback` so sessions that start while already on Wi-Fi are handled, not only subsequent network switches.
- Trusted WiFi dialog works even before enabling auto-connect — it uses a transient manager instance to read SSID/BSSID.

**Code quality**
- New `Security self-test`: "Neighboring VPNs" check scans for other installed VPN clients (v2rayNG, Hiddify, Happ, SFA, Karing, WireGuard, OpenVPN, strongSwan, …) that may be vulnerable to the April 2026 local-SOCKS leak.
- `MTU` self-test is now correctly reported as informational: flagging `MTU != 1500` as a "VPN anomaly" was both false-positive on well-behaved VPNs and contradicted NetGuard's own stealth choice of MTU=1500.
- Speed-test URLs switched to HTTPS where the endpoint supports it; cleartext exception narrowed to `speedtest.tele2.net` only (was three domains).
- Removed unused `ConfigBuilder.kt` wrapper, cleaned out imaginary `com.github.nicknob.*` entries from `KNOWN_VPN_PACKAGES`, removed the dead `SQLCipher` dependency (was declared but never wired into Room).
- First unit tests: `AddressValidatorTest` covers the SSRF-critical logic (16 cases, including CGNAT, hex/octal IPv4, IPv4-mapped IPv6).
- CI: GitHub Actions workflow runs `./gradlew testDebugUnitTest` on push / PR.
- `SECURITY.md` with responsible-disclosure policy.
- `User-Agent` now reads from `BuildConfig.VERSION_NAME` instead of the hard-coded `"NetGuard/1.0"`.

### v1.1.3 — 2026-04-17

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

## Setup

1. Clone the repository.
2. Drop the libXray AAR into `app/libs/` — see [`app/libs/README.md`](app/libs/README.md) for the exact download link and version. Without this step the build will fail with unresolved native symbols.
3. (Release builds only) copy `keystore.properties.template` → `keystore.properties` and point it at your signing key. Debug builds auto-sign with the SDK debug key and do not need this.
4. Build:

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
