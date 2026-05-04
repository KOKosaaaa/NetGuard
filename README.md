# NetGuard

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Android VPN client built for privacy, security, and stealth. Powered by xray-core.

Licensed under the **Apache License, Version 2.0** - see [`LICENSE`](LICENSE). You are free to use, modify, distribute, and sublicense the code (including for commercial use), provided you preserve the copyright notice. The Apache-2.0 patent grant also protects downstream users from patent claims by contributors.

## What makes NetGuard different

Most VPN clients (v2rayNG, Hiddify, v2rayTUN) are great tools, but they share common weaknesses: credentials sitting on disk, open local SOCKS ports, IP leaks during network switches, and package names that scream "VPN" to any DPI system. NetGuard was designed to fix all of that.

### Zero-leak network switching

When you switch between WiFi and mobile data, typical clients tear down the entire VPN tunnel and reconnect from scratch. During that window your real IP leaks. NetGuard keeps the TUN interface alive and only restarts the internal xray + tun2socks processes. Packets are black-holed until the tunnel is back up - zero leak window.

### Ephemeral authenticated SOCKS5

Every connection generates a fresh random port, a UUID username, and a 32-character random password for the local SOCKS5 bridge between tun2socks and xray. Credentials are cleared from memory on disconnect or network reconnect (they are kept alive during a session so that internal speed-test / service-test requests can reauthenticate through the HTTP bridge). Even if another app scans localhost ports, it cannot authenticate without the ephemeral password.

### Config minimally exposed on disk

The xray JSON config (containing server credentials, UUIDs, passwords) is written to app-private internal storage only long enough for xray to read it, then immediately `unlink()`ed once the SOCKS inbound is up. No config.json is kept across sessions, and it is never visible to other apps. Note that on ext4/F2FS the underlying blocks may persist until overwritten - this is a brief-exposure design, not a never-on-disk one.

### Built-in security self-test

11 automated checks that run against your own device:

- Open SOCKS5 / HTTP proxy ports (10808, 1080, 8080, etc.)
- Own SOCKS5 inbound rejects no-auth handshakes (regression guard for `XrayConfigGenerator.buildInbounds`)
- Xray gRPC API exposure
- Clash REST API exposure
- Wide port scan for known VPN-tooling ports
- `/proc/net/tcp` analysis for unexpected listeners
- VPN transport flag detection
- MTU informational report (non-decisive - see self-test details)
- Package name stealth analysis
- Neighboring VPN clients (informational; flags installed apps that may be vulnerable to the April 2026 local-SOCKS leak)

### Evil Twin WiFi protection

Stores SSID+BSSID pairs for trusted WiFi networks. When connecting to a WiFi with a known SSID but unknown BSSID (possible Evil Twin attack), automatically enables VPN and sends a warning notification.

### Service availability testing

Test which services actually work through each server - YouTube, Telegram, Instagram, ChatGPT, Discord, Google, X/Twitter, Spotify. See a score like "3/8" before you connect.

### Stealth branding

Package name `com.smarttools.netguard`, notification says "Connection active / Network service is running" - no mention of VPN or proxy anywhere visible to system-level inspection.

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
| Languages | 16 languages |
| Backup/Restore | Export/import full config as JSON |
| Log viewer | Real-time xray logs with auto-redaction of credentials |
| Subscription headers | Per-group row in the Servers list shows traffic counter (used / total or used / ∞), clickable globe / Telegram-style icons that open the provider's website / support, and the provider's announcement text (parsed from `subscription-userinfo`, `support-url`, `profile-web-page-url`, `announce` response headers) |
| Sort toggle | Sort by ping; second tap on the same menu item restores the default subscription order |
| Friendly profile names | Built-in subscriptions emit user-readable labels (🚀 Основной, 🇷🇺 Если в РФ не работает, 🛡 Резерв, ☁ Через Cloudflare, ⚡ Быстрый UDP, 🔒 Trojan / Shadowsocks резерв) instead of raw protocol abbreviations |

## Security hardening

- **Not vulnerable to the April 2026 VLESS local-SOCKS leak** affecting Happ, v2rayTUN, Hiddify, v2rayNG, NekoBox and others. No unauthenticated local SOCKS5 inbound is ever exposed - both internal bridges (SOCKS5 for tun2socks, HTTP for internal speed/service tests) require the ephemeral 32-char password. See *Ephemeral authenticated SOCKS5* above.
- **Honest caveat on password surface.** The SOCKS5 username and password are passed to the `tun2socks` helper process via command-line arguments, so they appear in `/proc/<tun2socks_pid>/cmdline`. On modern Android this file is protected by SELinux `app_data_file` contexts and hidepid, so other apps cannot read it, but a rooted attacker or the same-uid process can. The password is ephemeral per session, so disclosure only compromises the current tunnel's local bridge, not the server credentials. Migration to stdin / fd-based credential passing is tracked as a future hardening step.
- EncryptedSharedPreferences via `androidx.security:security-crypto` for small secrets (DB key material placeholder, credentials cache). Full database-level encryption via SQLCipher is on the roadmap - see *Known limitations* below.
- Log redaction - UUIDs, passwords, Bearer tokens masked automatically
- SSRF protection - private/loopback/link-local IPv4 and IPv6 blocked in profile parser
- Tapjacking protection on critical buttons (filterTouchesWhenObscured)
- Deep link validation with confirmation dialog
- Atomic file writes (temp + rename pattern)
- No cleartext traffic (except speed test domains through VPN tunnel)
- No backup (`android:allowBackup="false"`)
- DNS leak prevention - all port 53 traffic forced through proxy
- DNS address validation - loopback, private ranges and garbage strings rejected before they reach xray
- Input size limits on URIs, subscriptions, imports
- Evil Twin WiFi detection (SSID+BSSID pair validation)

## Known limitations

- **Room database is currently plain**, despite earlier wording. The SQLCipher dependency was declared but never wired as the Room `SupportFactory`, and the app deletes any previously-encrypted DB on first launch of a new version (see `AppDatabase.deleteEncryptedIfNeeded`). Profile data is already re-fetchable from subscriptions, so this is low-impact, but a proper SQLCipher integration is tracked as future work.
- **`androidx.security:security-crypto` was deprecated by Google in 2024.** The 1.1.0-alpha06 release still works on current Android, but Android 15/16 may change backing-store behaviour without backward-compatibility guarantees. Migration path: move to `java.security.KeyStore.getInstance("AndroidKeyStore")` directly, generate the AES-256 key via `KeyGenerator`, and store ciphertext in plain `SharedPreferences`. Tracked, not urgent.
- **tun2socks credential exposure.** See *Security hardening → honest caveat on password surface* above.

## Acknowledgements

NetGuard is composed of a small original glue layer wired around a stack of open-source components that do the heavy lifting. The honest credit list - without these projects, this app could not exist.

### Tunnel core (native)

- **[xray-core](https://github.com/XTLS/Xray-core)** - Mozilla Public License 2.0. Project X. The actual proxy engine that speaks VLESS / VMess / Trojan / Shadowsocks / Hysteria2 and handles TLS / REALITY / uTLS fingerprinting. Shipped as `libxray.so` in `jniLibs/arm64-v8a/`.
- **[badvpn / tun2socks](https://github.com/ambrop72/badvpn)** - BSD-3-Clause. Ambroz Bizjak. Userspace TUN-to-SOCKS5 helper that turns the Android `VpnService` TUN file descriptor into TCP/UDP streams that xray can consume. Shipped as `libtun2socks.so`.

### Android libraries

- **[AndroidX](https://developer.android.com/jetpack/androidx)** (Core, AppCompat, ConstraintLayout, SwipeRefreshLayout, Navigation, Lifecycle, Room, Preference, WorkManager, CameraX, Security-Crypto) - Apache-2.0. Google.
- **[Material Components for Android](https://github.com/material-components/material-components-android)** - Apache-2.0. Google. Material You theming, dialogs, bottom-nav, dynamic colors.
- **[OkHttp](https://github.com/square/okhttp)** - Apache-2.0. Square. HTTP client used for subscription fetching, certificate pinning, and DNS-rebinding-safe resolution.
- **[Gson](https://github.com/google/gson)** - Apache-2.0. Google. JSON serialisation for xray config generation and profile import/export.
- **[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)** - Apache-2.0. JetBrains. Async runtime for `TunnelVpnService`, watchdogs, network callbacks.
- **[ZXing Core](https://github.com/zxing/zxing)** - Apache-2.0. Generates QR codes for sharing profiles.
- **[ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)** - Apache-2.0 (Google Play Services component). Scans QR-coded subscription / profile URIs.
- **[JUnit 4](https://github.com/junit-team/junit4)** - EPL-1.0. Test runner.

### Inspiration / prior art

The architecture of NetGuard's `TunnelVpnService` (TUN file descriptor handover via Unix-domain socket, watchdog loop, no-leak network switching) draws from the broad Android-VPN-with-xray prior art established by:

- **[v2rayNG](https://github.com/2dust/v2rayNG)** - GPL-3.0. The reference implementation for an Android Xray client; defined many of the patterns we still use.
- **[Hiddify](https://github.com/hiddify/hiddify-app)** - GPL-3.0. Subscription / profile parser conventions.
- **[NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)** - GPL-3.0. UI patterns for per-app routing and traffic stats.

NetGuard does **not** reuse code from these projects directly (NetGuard is Apache-2.0; copying GPL-3.0 code would force a relicense), but ideas and protocol-level conventions are owed to them.

### Tooling

- **[Claude Code](https://claude.com/claude-code)** - Anthropic. Performed the v1.1.4 / v1.1.5 / v1.1.6 security audit, wrote the `ServerPreflight` / `PackageInstallReceiver` / TLS-rotation / MTU-probing changes, the JVM unit-test suite, and most of this README. v1.1.7 (trigger mode), v1.1.8 (onboarding wizard, flexible trigger routing), v1.1.9 (P0+P1 sprint), v1.1.91 (UX follow-up), and v1.2.0 (subscription headers, group rows, sort toggle, ping fixes) were also produced through the same assistant. AI-assisted commits are tagged with `Co-Authored-By: Claude` in their trailer when material code was generated by the assistant.

If we missed your project here, please open an issue - credit is the one thing we can give back, and we want the list to be complete.

## Release notes

### v1.2.15 (2026-05-05) — БС-mode (Russian whitelist) hardening

Russia turned on "white-list mode" for mobile data on May 5 (until May 9). Under that
regime the operator's edge router drops every TCP SYN that targets a non-whitelisted
IP **before** ТСПУ DPI looks at SNI — so a plain TCP preflight to a foreign VPS gets
RST-injected even though the actual VLESS+Reality TLS handshake (with `SNI=max.ru`
or another whitelisted domain) would pass through.

- **`failoverMaxAttempts` 3 → 14.** Try the entire 16-profile subscription before
  surfacing an error. Previously the budget exhausted before reaching CF tunnel
  IPs that sometimes overlap with whitelisted ranges.
- **`ServerPreflight` is now non-fatal.** Log a warning instead of throwing
  `IllegalStateException`. xray attempts the real Reality handshake; the protocol
  passes the operator's L7 check even when L4 SYN gets RST-ed by ТСПУ. v2rayTUN
  doesn't preflight and works in this scenario — match its behavior.
- **Whitelist hint in error message.** Detect ECONNREFUSED + timeout pattern
  across multiple failovers, surface human-readable
  *"БС-режим? VPN не пройдёт пока оператор не снимет белые списки. Попробуй Wi-Fi."*
  instead of the abstract `TCP: ECONNREFUSED`.
- DNS rebinding guard (private/reserved address rejection) inside preflight
  retained — security boundary unchanged.
- `versionCode` 46, `versionName` "1.2.15".

### v1.2.0 (2026-05-03)

Big UI + plumbing release. The `Servers` tab now shows subscription metadata
(traffic, support / website icons, announcement) and a real subscription
header per group. Several long-standing bugs around selection state, ping,
and frame stability fixed.

**Subscription headers + metadata**
- New `Subscription` fields: `usedBytes`, `totalBytes`, `supportUrl`,
  `webPageUrl`, `announce`. Parsed from the standard subscription response
  headers - `subscription-userinfo` (upload/download/total), `support-url`,
  `profile-web-page-url`, `announce` (plain UTF-8 or `base64:...` prefixed).
- DB migration `5 → 6`: `ALTER TABLE subscriptions` adds the five columns.
- `ProfileAdapter` rebuilt as a multi-viewType list: each subscription gets a
  row of its own (`item_subscription_header.xml`) before its profile rows. The
  header shows the subscription name (left, ellipsizes if too long), a traffic
  capsule centered in the gap (used / total or used / ∞ for unlimited
  quotas), and clickable globe / paper-plane icons on the right that open the
  provider's website / support chat. Icons hide automatically when the
  matching URL is empty.
- `SubscriptionGroupDecoration` simplified: text and icons live in the header
  row now; the decoration only paints the colored frame around each group.
  Frame is stable during scroll - `top` / `bottom` extrapolate past the
  viewport when one end of the group is offscreen, so it no longer collapses
  onto the visible subset.
- Item-change animations disabled on the servers list - selecting a profile
  or refreshing pings no longer makes the group frame "jump".

**Bug fixes**
- "Server not selected" while VPN is running. `replaceSubscriptionProfiles`
  now preserves `isSelected` and `isFavorite` across a subscription refresh
  by matching old → new profiles on the stable identity tuple
  (`protocol + address + port + uuid + password`). Previously the auto-update
  worker wiped the selected server and the home screen showed "no profile
  selected" while the tunnel kept running on the now-orphaned config.
- Hysteria2 / UDP ping. `PingHelper.pingForProfile` dispatches by protocol:
  TCP-handshake for VLESS / VMess / Trojan / Shadowsocks, ICMP echo (via
  Android's setuid `/system/bin/ping`) for Hysteria2 (QUIC over UDP).
  Hy2 servers no longer always show `-` in the ping column.
- Subscription title decoder. `decodeProfileTitle` now tries the standard
  base64 alphabet before URL-safe; some providers (GruVPN among them) send
  `base64:` payloads containing `/`, which the URL-safe decoder rejected and
  the subscription name silently fell back to the host.

**Servers tab UX**
- Sort menu item is a toggle. First tap sorts by ping; while sorted by ping
  the menu item reads *Sort by Subscription* and a second tap restores the
  default order (grouped by subscription, profiles in the original order the
  server returned them).
- Friendly profile names. The labels we generate for our own subscriptions
  are now user-readable ("🚀 Основной - быстрый", "🇷🇺 Если в РФ не работает",
  "🛡 Резерв 1 (Reality)", "☁ Через Cloudflare 1", "⚡ Быстрый UDP",
  "🔒 Trojan / Shadowsocks (резерв)") instead of the previous "DE Reality" /
  "CDN WS WARP" abbreviations.

**Theming + status bar**
- Light theme now sets `windowLightStatusBar=true` and (API 27+)
  `windowLightNavigationBar=true`, so the system clock / battery icons
  switch to dark on the white status bar instead of disappearing.
- Card backgrounds for selected / unselected profiles are theme-tied via two
  new attrs (`cardNormalBackground`, `cardSelectedBackground`); each theme
  (Default Dark / Light / OLED Black / Ocean / Dynamic) supplies values that
  stay readable on its own surface palette. Previously the dark-theme defaults
  rendered as semi-transparent slivers on the light theme.

**Internals**
- New string keys: `sort_by_subscription` (en + ru). Other locales fall
  through to English.
- New drawables: `ic_globe.xml`, `ic_paper_plane.xml`,
  `bg_traffic_capsule.xml`, `capsule_progress_drawable.xml`. Tints follow
  `?android:attr/textColorPrimary` so a single asset works on every theme.
- `versionCode` 40, `versionName` "1.2.0".

### v1.1.91 (2026-05-03)

UX + plumbing follow-up to v1.1.9. No new security claims - every change here either widens what the app accepts as input, makes existing state visible, or hides a confusing error behind a clearer one.

- **Onboarding accepts subscription URLs.** Previously the first-server step only parsed `vless:` / `vmess:` / `trojan:` / `ss:` / `hy2:` URIs and rejected `https://` subscription links with a `Couldn't parse` error. The wizard now detects an `http(s)://` input, validates it through the same `SubscriptionRepository.validateUrl` guard, inserts it as a subscription, and triggers a real `updateSubscription` so the profile list is fetched in-place.
- **TLS errors translated.** `chain validation failed` / `trust anchor for certification path not found` / `certPathValidator` / `notBefore` / `notAfter` / `expired` / `not yet valid` are mapped to a one-liner about device date/time, which is the actual cause 90% of the time. `Certificate pinning failure` is rephrased as a cert-rotation hint, `unable to find acceptable trust anchor` as a server chain config issue.
- **Trigger menu redesign.** Header section with switches, explainer and dual-app warning is now collapsed by default behind a Material 3 card that shows the current state at a glance: `Disabled · tap to set up` or `Enabled · strict · 5 apps`. The card has a chevron icon that rotates 180° on expand. Replaces the easy-to-miss `Hide info` / `Show info & switches` text button - the apps list now occupies most of the screen.
- **Onboarding wizard skipped on upgrade.** First launch of a fresh install still shows the wizard. An *upgrade* from any pre-1.1.8 version is detected by checking SharedPreferences for any non-`onboarding_done` key - a returning user has saved settings, so we set `onboarding_done=true` and go straight to the main UI.
- **Subscription name auto-fills from server.** `updateSubscription` now parses the `profile-title` response header (plain UTF-8 or `base64:…` prefixed, URL-safe + standard alphabets, missing padding allowed). When the server doesn't expose that header, the URL fragment (`#MyName`) is used - same convention as `vless://…#NodeName` URIs. Hostname is the final fallback. The previous behaviour of stamping every subscription with the literal string "Subscription" is gone.
- **Long-press to rename.** Long-press a subscription row in the list to rename it. Sets `Subscription.userRenamed=true`, which protects the user-chosen name from being overwritten on the next refresh. Manual entry in the Add Subscription dialog also flips the flag.
- **Add Subscription dialog clearer.** Name field hint now reads `Name (optional)` with a small subtext explaining that the provider's `profile-title` will fill it in. Empty input falls back to the URL host instead of the literal "Subscription".
- **Trigger settings switches auto-save.** v1.1.8 patch - Enable / Strict / AutoStop now persist immediately on toggle via `viewModel.updateSettings`, no longer requiring the user to scroll past the apps list to find the Save button (the button still applies the apps-list selection + VPN permission flow).
- **DB migration 4 → 5.** `subscriptions.userRenamed INTEGER NOT NULL DEFAULT 0`. Existing rows pick up the auto-fill behaviour on next refresh.
- **Internals.** New string keys: `onb_profile_subscription_fetching`, `import_subscription_confirm`, `add_subscription_name_optional_hint`, `add_subscription_name_optional_help`, `subscription_rename_title`, `subscription_name_hint`, `trigger_options_card_title`, `trigger_options_summary_off/on`, `trigger_mode_strict/flexible`. EN + RU translated; other locales fall through to English. New drawable `ic_chevron_down`. `versionCode` 34, `versionName` "1.1.91".

### v1.1.9 (2026-05-02)

Sprint-1 + Sprint-2 of the post-v1.1.8 code-review fix list. Closes 6 P0 (critical) and 9 P1 (high) findings around SSRF guards, network-level fingerprint, log redaction, and confirmation flows.

**P0 - SSRF / leak surface**
- `parseVmess` now runs the same `AddressValidator.requirePublicAddress` check as the other URI parsers, plus `1..65535` port range. The base64-encoded JSON could previously slip in `127.0.0.1` / private / CGNAT addresses that the rest of the app trusted.
- `ProfileEditViewModel.isValidAddress` delegates to `AddressValidator.isPrivateOrReserved` and validates IPv6 via `InetAddress.getByName("[…]")`. Manual save of `127.0.0.1`, `192.168.x`, `100.64.x`, hex/octal IPv4 or IPv4-mapped IPv6 is now rejected.
- `ServerPreflight.check` re-validates the resolved IP after `InetAddress.getByName`. Closes a DNS-rebinding window where a hostname could resolve to a private IP between profile parsing and TCP probe.
- `MainViewModel.autoSelectAndConnect` no longer fires N parallel TCP SYNs from the user's real IP. Uses cached `lastPingMs` if any profile has a fresh value; otherwise probes only 3 random candidates sequentially with an early stop on `<200ms`. Eliminates the "burst of SYNs to scattered cloud IPs" fingerprint that ISP/corp DPI uses to flag VPN clients in the auto-select phase.
- xray and tun2socks output is now run through `LogBuffer.redactPublic` *before* `Log.d`, not just inside the in-app Log viewer. ProGuard now strips `Log.d` / `Log.v` from release builds entirely.
- QR scan no longer imports profiles or subscriptions silently. Mirrors `MainActivity.handleDeepLink`'s confirmation dialog: VPN URIs go through `ProfileParser.parseSingleUri` + a `MaterialAlertDialog`; HTTPS subscriptions show a confirmation with the host preview; HTTP and unrecognised QR content are rejected with a Toast.

**P1 - high-priority hardening**
- `LogBuffer.redact` rebuilt: covers `password`/`passwd`/`pwd`/`pass`/`secret`/`api_key`/`token`/`bearer`/`authorization` (case-insensitive, both `=` and `:`, optional quotes), JSON form `"key":"value"`, Reality `pbk`/`sid` long base64, Shadowsocks URL base64 between `://` and `@`, hostname/IP in network-error lines (`dial`/`connect to`/`dns`/`resolve`). Quick-check keywords removed (the `-` keyword matched almost every line).
- `LogBuffer` switched from `ArrayList.removeAt(0)` to `ArrayDeque.removeFirst()` (O(1) vs O(n)). The "throttle" flag was a no-op; replaced with a real CONFLATED `Channel` debounced at 120ms for `StateFlow` updates.
- `SubscriptionRepository.validateUrl` is now public, called *before* DB insert in `SubscriptionViewModel.addSubscription` and `SettingsViewModel.importConfig`. Junk subscription URLs no longer persist and are not retried by the periodic `SubscriptionUpdateWorker`. Added 256-char limit to subscription names (mirrors `ProfileParser.MAX_NAME_LENGTH`).
- `ProfileEditViewModel.testConnection` refuses to TCP-ping when the tunnel is down. When connected, performs a SOCKS5 CONNECT through the local authenticated bridge and times the handshake. No more direct probe from the user's real IP.
- `HomeFragment` skips `GeoLookup.fetchUserLocation()` when the tunnel is down - the `ipwho.is` request previously went out from the user's real IP on every Home open, and once a week even with a warm cache.
- Subscription list masks the URL: shown as `host/…/abcd` (last 4 chars of path). The full token-bearing URL is no longer rendered into a `RecyclerView` where any screenshot leaks it.
- `SettingsFragment.isValidDns` now goes through `AddressValidator.isPrivateOrReserved`, covering CGNAT, IPv6, hex/octal IPv4, IPv4-mapped IPv6 - all of which the previous regex missed.
- `SecuritySelfTest` adds an "Own SOCKS5 auth" check that connects to our own ephemeral SOCKS port and asserts that the no-auth handshake is rejected. Catches future regressions in `XrayConfigGenerator.buildInbounds` automatically.
- `ServiceTester` returns uniform error results when the tunnel is down instead of testing youtube.com / openai.com / instagram.com / discord.com from the user's real IP. `buildClient()` now returns `null` if credentials aren't ready - there's no direct-connection fallback path left.
- All interactive elements in the onboarding wizard (Back / Next / Grant VPN / Grant Usage Stats / Paste / Import / Skip) carry `android:filterTouchesWhenObscured="true"`. Onboarding is the highest-stakes UI flow (VPN permission, mode selection, first profile) and was the only place README's tapjacking-protection claim didn't apply.

**Internals**
- New strings: `import_subscription_confirm` (en + ru). Other locales fall back to English via Android's locale resolution.
- `versionCode` 33, `versionName` "1.1.9".

### v1.1.8 (2026-05-02)

**First-launch onboarding wizard** - the app now opens with a 7-step setup flow on first install: language → welcome (with feature cards) → routing-mode picker → VPN permission → Usage Stats permission (only when Trigger mode is picked) → first-server import → done. State is persisted in `SharedPreferences` (`onboarding_done`), so the wizard is shown exactly once. Subsequent launches go straight to the main UI. Profile import accepts `vless://` / `vmess://` / `trojan://` / `ss://` / `hy2://` links via paste-from-clipboard or manual paste, with an explicit "Add later" skip button if the user has no profile yet. The bottom progress bar uses Material 3 `LinearProgressIndicator` with `trackCornerRadius`, animated via `ObjectAnimator` between steps; transitions use a slide+fade choreography (180ms out, 220ms in).

- **Live language switching, no flicker.** `OnboardingActivity` declares `configChanges="locale|layoutDirection|uiMode|fontScale"` and overrides `onConfigurationChanged` so a locale switch via `AppCompatDelegate.setApplicationLocales` does NOT recreate the activity. Instead, a `refreshMap` of `(TextView, R.string.X)` pairs is walked and every translated string is re-set in place - including the `TextInputLayout` hint and the bottom Back/Next button labels. Result: pick a language, the entire wizard updates instantly without a single frame of black.
- **Selection cards rebuilt without `isCheckable`.** `MaterialCardView` with `isCheckable=true` + `checkedIcon=null` crashes on Android 14 with NPE in `c5.b.onAnimationUpdate` (Material's checked-icon animator tries to set alpha on a null `Drawable`). The language cards are now plain `MaterialCardView` with manual stroke-color toggling (`colorOutlineVariant` ↔ `colorPrimary`); selection is conveyed by the `RadioButton` plus the colored outline. No checkmark icon, no crash.
- **Translations.** All onboarding + trigger-mode strings have been added to **all 16 supported locales**: `ar`, `de`, `en`, `es`, `fr`, `hi`, `in`, `it`, `ja`, `ko`, `pt`, `ru`, `th`, `tr`, `vi`, `zh-rCN`. Around 50 new strings × 14 non-base translations = ~700 new translations.

**Flexible trigger routing mode** - new `triggerStrictMode` setting (default `true` to preserve v1.1.7 behavior). When `false`, the trigger watcher acts as a launcher only: opening a trigger app brings up the regular global tunnel governed by the user's normal `perAppMode` (`DISABLED` = global, `WHITELIST`, `BLACKLIST`); closing it (with `triggerAutoStop`) calls `TunnelVpnService.stop()`. Strict mode keeps the v1.1.7 allow-list semantics (only trigger apps route through the VPN, blackholed when down). The new switch is in *Settings → Trigger apps* with an inline explainer.

- `App.onCreate`: skips `startQuarantine` when flexible mode is enabled.
- `App.updateTriggerWatcher`: only pre-warms when strict.
- `ForegroundAppWatcher.onForegroundChanged`: branches between `activateTrigger`/`deactivateTrigger` (strict) and `start(profileId)`/`stop()` (flexible).
- `TriggerAppsFragment.save`: stops force-disabling per-app routing and stops the BLACKLIST overlap auto-cleanup when flexible mode is on - composing trigger detection with per-app rules is the whole point.
- Always-on auto-start path in `TunnelVpnService.onStartCommand` also respects the flag.

**Dual-app warning surface.** Cloned variants of an app (Telegram via MIUI Dual Apps, Samsung Dual Messenger, Parallel Space) cannot be added to the VPN allow list - Android's public `addAllowedApplication(packageName)` only resolves the calling user's UID, while the clone runs in a separate user space (UID ≥ 999000 on MIUI). The Trigger Apps screen now shows a red Material 3 card explaining this, with a "Why?" dialog detailing the limitation and three workarounds (use original Telegram, disable "Block connections without VPN" in system settings, or add the cloned package separately if the OEM exposes it).

**Internals**
- `OnboardingActivity` lives in `ui/onboarding/`; registered in the manifest with `singleTask` launch mode and the configChanges flags above.
- `MainActivity.onCreate` short-circuits to `OnboardingActivity` on first launch (`onboarding_done` pref absent), and respects an `EXTRA_OPEN_TRIGGER` extra to navigate directly to the trigger picker after onboarding when the user picked Trigger mode.
- Wizard state (current step, picked routing mode, picked language, profile-import counter) survives configuration-change events via `onSaveInstanceState`.
- `versionCode` 32, `versionName` "1.1.8".

### v1.1.7 (2026-05-02)

**App-launch trigger mode** - the headline feature. Pick which apps go through the VPN; everything else (banking, maps, your usual browser) keeps using the regular connection. The selected apps have no fallback path: if the tunnel is down, their packets are black-holed in the TUN - they never see your real IP, not even for a moment.

- New `ForegroundAppWatcher` service polls `UsageStatsManager` (250ms) to detect when a trigger app comes to the foreground.
- Pre-warm: when trigger mode is enabled, the tunnel comes up immediately and stays ready, so opening a trigger app is instant - no "Connecting…" delay.
- Optional auto-stop on background to save battery (xray killed, TUN keeps the apps black-holed).
- Boot-survival: `BootReceiver` re-arms the watcher and tunnel after device reboot.
- Auto-recover: if xray crashes, up to 3 silent restarts before any user-visible failure. In trigger mode the TUN is preserved as a quarantine even after xray gives up - never falls back to "no VPN" for trigger apps.
- Network sanity: skipping activation when no underlying network is available, surfacing it as "Нет сети" / "No network" instead of a stale "Connecting…".
- UI: dedicated screen with Material 3 switches, collapsible explainer, search box, system-app filter, save/cancel. Big red banner explains the Always-on VPN sweet spot ("turn ON Always-on, leave Block-without-VPN OFF").

**Bug fixes**
- Backup / Restore: profiles now keep their subscription link. Old format (v1, plain URI list) is still accepted; new exports use v2 with `{uri, subscription}` per profile.
- Settings: scroll position is preserved when you navigate into a sub-screen and back.
- Settings: bottom-nav tap always returns to the root of the tab - no more "I went into Trigger then tapped Servers and came back to Trigger with the wrong tab highlighted".
- Quick Settings tile: when there is no saved profile, opening the tile now triggers auto-select (best non-RU server) instead of silently doing nothing.
- DNS: when a non-empty Per-App blacklist is active, the VPN no longer overrides system DNS for the excluded apps. Previously they were forced onto Cloudflare/Google DNS, which is filtered by some Russian ISPs and broke their resolution.
- Connect button now triggers auto-select when no profile is picked, instead of doing nothing.
- Per-App routing description rewritten to make the relationship to Trigger mode obvious; toggling Trigger automatically disables Per-App routing to avoid silent conflict.

**Internals**
- Tunnel start re-ordered: TUN comes up before xray now, eliminating the leak window during start.
- Faster activation: tighter polling in `sendTunFd` and `waitForPort`, 100ms watcher tick, 50ms post-establish delay - saves ~600-700ms on cold open of a trigger app.
- `setUnderlyingNetworks` now passes ALL non-VPN networks (cellular + wifi if both available) instead of just the first one.

### v1.1.6 - 2026-04-30

**Security audit (P0/P1 fixes)**
- **No more HTTP fallback in geo-IP lookup.** `GeoLookup.kt` previously fell back to `http://ip-api.com/json/` when the HTTPS `ipwho.is` provider was unreachable. Any on-path observer would have seen the real source IP in plaintext during that fallback. The HTTP path is now removed entirely; if HTTPS lookup fails, the result is simply `null`.
- **Geo cache moved to `EncryptedSharedPreferences`.** User coordinates were previously cached in plain `SharedPreferences` (`/data/data/<pkg>/shared_prefs/geo_cache.xml`). A privileged co-resident process or root could read them. The cache is now stored under `geo_cache_enc` with `MasterKey` AES-256-GCM, with a one-shot migration that wipes the legacy plaintext on first launch.
- **Profile validation before xray config build.** `XrayConfigGenerator.validateProfile()` now rejects `port` outside `1..65535` and any control-character (`\x00..\x1f`, `\x7f`) in `address`, `host`, `path`, `sni`, `serviceName`, `authority`, `publicKey`, `shortId`, `alpn` *before* the JSON is fed to xray. A corrupt stored profile no longer kills the tunnel through the watchdog loop; instead a single readable error surfaces.
- **No more `Thread.sleep` on Dispatchers.IO.** `sendTunFd()` and `startTun2socksProcess()` are now `suspend` and use `delay()` while waiting for the tun2socks Unix-domain socket. The previous `Thread.sleep(200)` calls were blocking IO worker threads up to 2 s per startup.
- **Concurrency: `@Volatile` on cross-thread service state.** `vpnFd`, `serviceScope`, `trafficMonitor`, `xrayProcess`, `tun2socksProcess`, `networkCallback`, `currentProfileId`, `showSpeedNotification`, `xrayWatchdogJob`, `tun2socksWatchdogJob`, `lastNotificationUpdate` are now all `@Volatile` (or `synchronized(fdLock)` for `vpnFd`). Writes from the main thread are now visible immediately to the watchdogs running on `Dispatchers.IO`, so a watchdog cannot see a stale `null` and skip recovery.
- **Faster network-switch reconnect.** WiFi↔LTE handover debounce reduced from 2000 ms to 500 ms - the TUN black-hole window during a clean handover is now sub-second.
- **`ACCESS_FINE_LOCATION` capped at API 32.** `NEARBY_WIFI_DEVICES` (`neverForLocation`) covers SSID/BSSID reads on Android 13+, so the privacy-sensitive permission is no longer requested on devices where it is no longer needed.

**VPN-detection bypass (per-app)**
- **New "Exclude all Russian apps" / "Исключить приложения РФ" button** in *Settings → Per-app routing*. One tap sweeps every installed non-system app whose `packageName` starts with `ru.` and adds them to the per-app list, plus a curated set of Russian apps that publish under `com.*` / `io.*` (Tinkoff, Sberbank, Otkritie, Wildberries, Ozon, ICBC, AliExpress AER, the full Yandex stack, etc.). When `PerAppMode.BLACKLIST` is active, those apps see the real underlying network instead of the tunnel - their IP / GeoIP / `TRANSPORT_VPN` flag stays consistent with the SIM/Wi-Fi region. This is the only client-side mitigation available without root against the published "VPN/Proxy detection on client devices" methodology, which relies on `ConnectivityManager.NetworkCapabilities.TRANSPORT_VPN`, `VpnTransportInfo`, and `dumpsys vpn_management` - none of which can be hidden by an app from another app at the OS level. The button is locale-gated (visible only on `ru` / `en`) so non-Russian-locale users do not see an unhelpful action.
- **Auto-bypass for newly installed RU apps.** A `PACKAGE_ADDED` `BroadcastReceiver` (registered dynamically because Android 8+ disallows static manifest `PACKAGE_ADDED` for regular apps) appends every freshly-installed non-system app whose `packageName` starts with `ru.` to `perAppList`, but only when the new `autoBypassRuPackages` toggle is on. Without this the one-shot button only protects apps that exist at the moment the user taps it.

**Tunnel reliability**
- **MTU-probing.** `VpnService.Builder.setMtu()` and `tun2socks --tunmtu` are now derived from `ConnectivityManager.getLinkProperties().getMtu()` of the active non-VPN network, capped to `[1280, 1500]`. On many mobile carriers the LTE/5G link MTU is 1428 or 1450 - the previous hard-coded `1500` caused silent IP fragmentation and a 5–15 % throughput drop.
- **Pre-flight reachability check.** Before xray + tun2socks are spun up, `ServerPreflight` does a 1.5 s DNS + 2 s TCP probe of the target address. A dead server short-circuits to `ConnectionState.Error` in <2 s instead of burning the full 30 s tunnel-establishment budget. UDP-only protocols (Hysteria2) fall back to DNS-success-only since we cannot meaningfully ping UDP without speaking the protocol.
- **Auto-failover.** When `startTunnel()` fails (timeout, preflight dead, xray crash, tun2socks exit) the service automatically tries the next untried profile from the same subscription (or the next one in the database if the current profile has no subscription), with a budget of 3 attempts. The ledger resets on a successful `Connected` state and on user-initiated `stopTunnel`.
- **TLS fingerprint randomisation.** New `TlsFingerprintMode` setting (`CHROME` / `FIREFOX` / `SAFARI` / `IOS` / `EDGE` / `RANDOM`) feeds the uTLS fingerprint string used for VLESS / VMess / Trojan TLS and REALITY outbounds. In `RANDOM` mode a fresh fingerprint is picked per connect, so DPI systems cannot correlate consecutive sessions by ClientHello shape. Per-profile override (set by `ProfileParser` when the URL carries `fp=…`) still wins over the global setting. Fixed a long-standing bug where `ServerProfile.fingerprint` defaulted to `"chrome"` instead of `""`, which made any global rotation a no-op for stored profiles.

**Tests**
- New JVM unit tests under `app/src/test/java/com/smarttools/netguard/core/`:
  - `XrayConfigValidationTest` - port range, control-character rejection in 9 string fields, fingerprint resolver (default / global / random / per-profile override), SOCKS port + credentials sanity (11 cases).
  - `ServerPreflightTest` - empty / unresolvable / closed-port / listening-port / UDP-only paths (5 cases).
- `app/build.gradle.kts` enables `testOptions.unitTests.isReturnDefaultValues = true` so `android.util.Log` calls in code under test do not throw `Method d in android.util.Log not mocked.`

### v1.1.4 - 2026-04-18

**Security**
- **SSRF hardening.** All private / loopback / link-local / CGNAT (`100.64.0.0/10`) / reserved (`240.0.0.0/4`) / IPv4-mapped-IPv6 / hex-and-octal IPv4 literals (`0x7f.0.0.1`, `0177.0.0.1`) are now rejected by a single `AddressValidator`. The old spot checks on `startsWith("10.")`, `"192.168."` etc. were trivially defeated by alternative textual forms; the new validator resolves literals numerically via `InetAddress` and inspects the resulting bytes.
- **DNS rebinding guard.** Subscription fetches now go through a custom OkHttp `Dns` resolver that re-validates every address returned at connect time, so an attacker who controls the subscription host's DNS cannot swap the validated public IP for a private LAN IP between validation and connection.
- **Ephemeral SOCKS credentials.** The local SOCKS5 / HTTP bridge credentials are now stored as `CharArray` inside `CredentialManager` and actively zeroed on `clear()`, instead of lingering as immutable `String` heap residue until GC.
- **No-leak network switching.** `VpnService.setUnderlyingNetworks()` is now pinned to the active non-VPN network both at tunnel establishment and on every WiFi↔LTE switch, eliminating the narrow window where xray's outbound socket could transiently route over the old interface.
- **Honest README.** Claims that were not strictly true in the code - "credentials wiped from memory immediately after handshake", "config never touches disk", "encrypted database" - have been rewritten to reflect what actually happens. The matching *Known limitations* section documents tracked items (SQLCipher integration, `security-crypto` deprecation, tun2socks credential argv exposure).

**Platform compat**
- **Android 14 FGS.** Foreground-service type migrated to `specialUse | systemExempted` with the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declaration. The previous `specialUse` on Android 14 raised a runtime `SecurityException` at `startForeground()`; a naive migration to `connectedDevice` also fails because that type requires holding one of BLUETOOTH/USB/NFC permissions that a VPN legitimately cannot claim.
- **Android 13+ WiFi.** `NEARBY_WIFI_DEVICES` permission (with `neverForLocation`) is now declared and requested at runtime when the user opens the Trusted WiFi dialog or flips the auto-connect toggle; without it, SSID read silently returned `<unknown ssid>` on stock Android 13+.
- **IPv6 route is now conditional on the `Enable IPv6` setting.** Previously `addRoute("::", 0)` was claimed unconditionally - on IPv4-only servers, AAAA-bound traffic disappeared into tun2socks instead of falling back gracefully.

**WiFi auto-connect reliability**
- SSID/BSSID lookup falls back to `WifiManager.connectionInfo` when Android strips `transportInfo` from background callbacks.
- Events are de-duplicated by `(SSID, BSSID)` so the throttled `onCapabilitiesChanged` firehose (RSSI / link-speed / validation updates) no longer spams `TunnelVpnService.start()`.
- If the first capability event arrives with `<unknown ssid>` (Wi-Fi still associating), the manager retries at 2 s / 5 s / 10 s via `WifiManager` instead of losing the event.
- An initial probe runs after `registerNetworkCallback` so sessions that start while already on Wi-Fi are handled, not only subsequent network switches.
- Trusted WiFi dialog works even before enabling auto-connect - it uses a transient manager instance to read SSID/BSSID.

**Code quality**
- New `Security self-test`: "Neighboring VPNs" check scans for other installed VPN clients (v2rayNG, Hiddify, Happ, SFA, Karing, WireGuard, OpenVPN, strongSwan, …) that may be vulnerable to the April 2026 local-SOCKS leak.
- `MTU` self-test is now correctly reported as informational: flagging `MTU != 1500` as a "VPN anomaly" was both false-positive on well-behaved VPNs and contradicted NetGuard's own stealth choice of MTU=1500.
- Speed-test URLs switched to HTTPS where the endpoint supports it; cleartext exception narrowed to `speedtest.tele2.net` only (was three domains).
- Removed unused `ConfigBuilder.kt` wrapper, cleaned out imaginary `com.github.nicknob.*` entries from `KNOWN_VPN_PACKAGES`, removed the dead `SQLCipher` dependency (was declared but never wired into Room).
- First unit tests: `AddressValidatorTest` covers the SSRF-critical logic (16 cases, including CGNAT, hex/octal IPv4, IPv4-mapped IPv6).
- CI: GitHub Actions workflow runs `./gradlew testDebugUnitTest` on push / PR.
- `SECURITY.md` with responsible-disclosure policy.
- `User-Agent` now reads from `BuildConfig.VERSION_NAME` instead of the hard-coded `"NetGuard/1.0"`.

### v1.1.3 - 2026-04-17

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
2. Drop the libXray AAR into `app/libs/` - see [`app/libs/README.md`](app/libs/README.md) for the exact download link and version. Without this step the build will fail with unresolved native symbols.
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
