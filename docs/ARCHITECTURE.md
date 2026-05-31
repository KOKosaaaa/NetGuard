# NetGuard — Architecture & Navigation Map

> Developer map for fast lookup. Updated 2026-05-31. ~21.5k LoC Kotlin (app)
> + a Go agent (server-side). Package root: `com.smarttools.netguard`.
> applicationId `com.smarttools.netguard`, minSdk 26, 16 locales.

## "Where do I find…?"

| I want to change… | Look in |
|---|---|
| The VPN tunnel lifecycle (start/stop/handover/trigger) | `service/TunnelVpnService.kt` (the big one, ~2.1k lines) |
| xray config JSON we feed the tunnel | `core/XrayConfigGenerator.kt` |
| Parsing `vless://`/`vmess://`/`telemost://`/subscription URIs | `core/ProfileParser.kt` |
| Telemost multi-channel relay (joiner side) | `core/TelemostRelayManager.kt`, `core/SocksRoundRobinLb.kt` (+ native `jniLibs/arm64-v8a/librelay.so`) |
| The "My Servers" / agent feature (v2.0.0) | `ui/managed/**` + `agent/**` |
| Home screen (connect button, map, status) | `ui/home/HomeFragment.kt`, `widget/ConnectionMapView.kt` |
| Settings / per-app / trigger | `ui/settings/**` |
| Onboarding wizard | `ui/onboarding/OnboardingActivity.kt` |
| DB schema / migrations | `database/AppDatabase.kt` (Room) |
| Encrypted DB key | `core/DatabaseKeyManager.kt` |
| Geo lookup for the map | `util/GeoLookup.kt` |
| Strings (16 locales) | `res/values*/strings.xml`. NOTE: `stage_*` are resolved dynamically via `getIdentifier(Stage.labelKey)` — not unused. |

## Package layout (app/src/main/java/.../netguard)

- **service/** — long-running Android services (12 files). `TunnelVpnService` (VpnService: TUN, tun2socks, xray child process, network handover, trigger/quarantine modes), `ForegroundAppWatcher` (trigger-mode foreground-app polling), `WifiAutoConnectManager`, `NotificationHelper`.
- **core/** — protocol/config logic. `XrayConfigGenerator`, `ProfileParser`, `TelemostRelayManager`, `SocksRoundRobinLb`, `DatabaseKeyManager`.
- **agent/** — the **NetGuard Agent** client (manage your own VPS). `AgentApiClient` (OkHttp + custom X509TrustManager for the agent's self-signed cert), `AgentApi` (DTOs), `SshBootstrap`/`SshBootstrapJsch`/`SshBootstrapTrilead` (3 SSH libs tried in order — banner-timeout fallback chain), `ManagedServer*` (Room), `Chain*` (multi-hop), `AgentErrorMessages`.
- **ui/managed/** — agent UI: add server, server detail, create profile (Reality wizard), create chain, create Telemost, rooms.
- **ui/home, ui/settings, ui/profiles, ui/subscription, ui/onboarding, ui/logs** — screens.
- **model/, repository/, viewmodel/, database/** — data layer (Room + repositories + VMs).
- **util/** — `GeoLookup`, `SpeedTester`, `SecuritySelfTest`, helpers.
- **widget/** — home-screen + custom views (`ConnectionMapView` world-map arc, `ConnectionMapView`/chart). App widgets live here too.
- **worker/** — WorkManager (background server status sync).

## The Agent subsystem (app ↔ server)

```
Android (agent/AgentApiClient)  ──HTTPS+bearer──►  netguard-agent (Go, :9443)
   SshBootstrap (one-shot install) ──SSH──►        systemd service on the VPS
                                                    manages: xray / Telemost / sing-box
```

### Why Go (agent language choice)

The agent is uploaded over SSH to an **arbitrary fresh VPS** and must just run
there. That single constraint drives the language choice:

- **One static binary, zero runtime deps.** Go compiles to a self-contained
  executable — no interpreter, no shared libs, no package manager on the
  server. Deploy is literally `scp binary && ./binary`. Python would need an
  interpreter + `pip install` on every box (a bootstrap nightmare on a bare
  VPS where even `curl` may be missing); Node needs node + node_modules; a
  JVM agent needs a JRE. Go sidesteps all of it.
- **Trivial cross-compilation.** We build Linux binaries for both arches from
  the Windows/dev machine with `GOOS=linux GOARCH=amd64|arm64 go build` —
  no cross-toolchain, sysroots, or linkers (the pain point with C/Rust).
  Both arch binaries ship inside the APK; `SshBootstrap` picks one by
  `uname -m`.
- **Stdlib covers exactly this job**, no heavy framework: `net/http` +
  `crypto/tls` (HTTPS control-plane with a self-signed cert), `os/exec`
  (shell out to `systemctl`/`apt`/`xray`), `//go:embed` (the Telemost
  creator binary is embedded per-arch), goroutines (the async task model —
  deploy/scale/purge/provision all run in the background).
- **Memory-safe + statically typed + small RAM footprint** — important on the
  cheap low-RAM VPSes users actually buy, and on a remote box you can't easily
  debug (no segfaults like C, lighter than a JVM).

Rust would also fit technically (static binary, cross-compiles), but Go is
faster to write and its stdlib maps 1:1 onto this control-plane glue.

- **Server-side agent is a separate Go program**, NOT in this Kotlin tree. It lives in this same repo under **`agent/`** (Go module). Build: `cd agent && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o dist/netguard-agent-amd64 ./cmd/netguard-agent` (+ arm64). The two arch binaries are bundled into the APK at `app/src/main/assets/agent/netguard-agent-{amd64,arm64}` (gitignored; SshBootstrap streams the right one + sha256 verifies).
- Agent endpoints (`agent/internal/api/router.go`): `/v1/auth/{pair,rotate,revoke}`, `/v1/status`, `/v1/xray/{deploy,profile,uninstall,inbounds,refresh-geo-dat}`, `/v1/telemost/{deploy,scale,rooms,cookies,uninstall,health}`, `/v1/bypass/{rules,outbounds}`, `/v1/agent/{warmup,update,update-upload,swap-setup,provision,purge}`, `/v1/tasks/{id}`.
  - `/v1/agent/provision` — fire-and-forget after pairing: installs xray (empty+running) + stages Telemost so the first profile create is instant (`deploy/provision.go`).
  - `/v1/agent/purge` — full self-destruct (xray, sing-box, Telemost, wlb user, the agent itself); script runs inline via `sh -c` because the unit's `PrivateTmp=true` hides a /tmp file from the systemd-run cleanup (`deploy/purge.go`).
  - **sing-box** installer (`deploy/singbox.go`) is written but **deferred** — not wired into provision (no consumer yet; profiles go through xray). Revisit for Hysteria2/TUIC profiles.
- Long ops are async **tasks** (`agent/internal/tasks`): POST returns `task_id`, poll `GET /v1/tasks/{id}` (status pending/running/done/failed/rolled_back).
- Deploy logic with the 6 idempotency rules: `agent/internal/deploy/` (`xray.go`, `telemost.go`, `swap.go`, `common.go`). Port preflight → `E_PORT_BUSY`; healthcheck checks `systemctl is-active` (not just port-listening).

## Build / run

```bash
# APK (release, signed from keystore.properties → ~/.android/netguard-release.keystore)
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk        # device f2cf8c1d

# Agent (Go, linux only)
cd agent && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build ./...     # + arm64
go test ./internal/deploy/                                           # unit tests

# Lint (full report; lintRelease aborts on Error-severity, e.g. MissingTranslation)
./gradlew :app:lintRelease   # report: app/build/reports/lint-results-release.xml
```

## Gotchas (learned the hard way)

- **stage_\*** strings are dynamic (`getIdentifier`) — lint calls them unused; they're not.
- **DatabaseKeyManager** uses `commit()` deliberately (sync write of the DB key; async could lose it on crash → unreadable DB).
- **GeoLookup.appCtx** holds the *application* context (not a leak; lint false positive, suppressed).
- Release build strips `Log.d/v` (ProGuard); `Log.w/e` survive — grep logcat for `AddServerVM`, `TunnelVpn`, `XrayCore`.
- Reality wizard deploys on **:443** by default; the agent now auto-picks a free port if 443 is taken (e.g. a co-located sing-box) instead of crash-looping.
- minSdk 26 — no `SDK_INT < O` guards needed.
