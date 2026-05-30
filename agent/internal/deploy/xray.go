package deploy

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

// ErrPortInUse is returned when an explicitly-requested inbound port is
// already held by another listener. The API layer maps it to E_PORT_BUSY
// so the app can tell the user to pick another port instead of silently
// deploying an xray that crash-loops on `bind: address already in use`.
var ErrPortInUse = errors.New("port in use")

// Pinned Xray-core release. Update by bumping version tag + matching
// sha256 per arch (from XTLS/Xray-core release page). Always pinned —
// "latest" lets a compromised release sneak in via MITM on the download.
const (
	xrayVersion     = "v26.5.9"
	xrayURLTemplate = "https://github.com/XTLS/Xray-core/releases/download/%s/Xray-linux-%s.zip"
)

// SHA256s captured from the XTLS/Xray-core v26.5.9 release page on
// 2026-05-28 via `curl -L | sha256sum`. Bump alongside xrayVersion.
var xrayZipSha256ByArch = map[string]string{
	"64":        "f56c106b7c0159ad386bccd340faa5bbf55fd5c15821ec9e63e6a6ba11d3d1c7",
	"arm64-v8a": "7bc1da606e26e4ac2d7831181745bb3bcf4dca0fd7825f41388ae032e1247d15",
}

// XrayInstallPath is where the agent puts the xray binary regardless of
// how it was originally installed. Lets us own the version we ship.
const (
	XrayInstallPath = "/usr/local/bin/xray"
	XrayConfigPath  = "/etc/xray/config.json"
	XrayUnitPath    = "/etc/systemd/system/xray.service"
	XrayDataDir     = "/var/lib/xray"
	// XrayAssetDir holds geoip.dat + geosite.dat. xray reads files
	// from XRAY_LOCATION_ASSET, which we point at this dir via the
	// systemd unit's Environment= directive. Without these files,
	// any rule using kind=geosite / kind=geoip prevents xray from
	// loading config.
	XrayAssetDir = "/usr/local/share/xray"
)

// Pinned v2fly geo-data releases. Bump alongside testing; matching
// sha256 captured 2026-05-28 from the official release pages.
const (
	geoipURL     = "https://github.com/v2fly/geoip/releases/download/202605120112/geoip.dat"
	geoipSha256  = "e9002979e0df72bce1c8751ff70725386594c551db684b7a232935b8b2bb8aa2"
	geositeURL   = "https://github.com/v2fly/domain-list-community/releases/download/20260527110433/dlc.dat"
	geositeSha256 = "50a1f17d12f1d44495ddea7d32a8c5d852ccefe848cf375f5adff22346b68cef"
)

// DeployXrayRequest is the body of POST /v1/xray/deploy.
type DeployXrayRequest struct {
	// FirstInbound: convenience — if set, after install we add this
	// inbound and return its vless:// URI in the task result.
	// If nil, deploy stops after `systemctl is-active xray == active`
	// and the user adds profiles via /v1/xray/profile later.
	FirstInbound *InboundSpec `json:"first_inbound,omitempty"`
}

// InboundSpec is the user-facing description of one xray inbound. Phase
// 1 only handles VLESS (no Reality, no TLS). Reality + Trojan land in
// phase 2 when the API + Android UI know enough to ask for them.
type InboundSpec struct {
	Protocol   string `json:"protocol"`           // "vless" only for phase 1
	Port       int    `json:"port"`               // 0 → pick a free one in 10000–65000
	UUID       string `json:"uuid"`               // empty → generate
	ServerName string `json:"server_name,omitempty"` // sni — phase 2 only
	Label      string `json:"label,omitempty"`    // shown to user in profile list
}

// XrayDeployResult is the JSON returned via /v1/tasks/{id}.result on
// success. Mirrored to xray_inbounds.profile_uri so /v1/xray/profile
// can list it without re-deriving.
type XrayDeployResult struct {
	XrayVersion string `json:"xray_version"`
	Inbound     *InboundResult `json:"first_inbound,omitempty"`
}

type InboundResult struct {
	InboundID  string `json:"inbound_id"`
	Protocol   string `json:"protocol"`
	Port       int    `json:"port"`
	ProfileURI string `json:"profile_uri"`
}

// XrayDeploy is the Runner passed to tasks.Manager.Spawn. Implements all
// six idempotency rules from common.go.
func XrayDeploy(db *storage.DB, req *DeployXrayRequest) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		h.SetStep("detect", 5)

		// --- 1. detect existing install ---
		alreadyHaveBinary := WhichExists("xray") || fileExists(XrayInstallPath)
		alreadyRunning := SystemctlIsActive(ctx, "xray")
		hasConfig := fileExists(XrayConfigPath)
		ourInbounds, _ := db.ListXrayInbounds()
		isOurs := len(ourInbounds) > 0
		h.LogF("detect: xray binary=%v unit_active=%v config=%v our=%v",
			alreadyHaveBinary, alreadyRunning, hasConfig, isOurs)

		// Refuse to touch an xray we didn't put there. The user may have
		// configured this server by hand; silently overwriting their
		// inbounds would lose data even with a backup (they wouldn't
		// know to look in /var/lib/netguard-agent/backups/). Android
		// surfaces this E_ code as a dialog offering "Cancel" or
		// "Wipe and reinstall" — the latter hits /v1/xray/uninstall
		// and then re-spawns this task.
		if hasConfig && !isOurs {
			return h.Fail("E_XRAY_PREEXISTING",
				"xray is already configured on this server but was not "+
					"installed by the agent. Refusing to overwrite. Use "+
					"POST /v1/xray/uninstall to wipe the existing setup "+
					"before retrying, or remove xray manually.",
				false /* non-retryable until the user resolves it */)
		}

		// --- 1b. resolve + preflight the inbound port (improvements #1/#2) ---
		// Done before we download/install anything so a port conflict fails
		// fast with a clear E_PORT_BUSY instead of crash-looping xray after
		// a full install.
		if req.FirstInbound != nil {
			p, perr := resolveInboundPort(ctx, req.FirstInbound.Port, req.FirstInbound.ServerName != "")
			if perr != nil {
				if errors.Is(perr, ErrPortInUse) {
					return h.Fail("E_PORT_BUSY", perr.Error(), false)
				}
				return h.Fail("E_PORT_PICK", perr.Error(), true)
			}
			if p != req.FirstInbound.Port {
				h.LogF("inbound port resolved to %d (requested %d)", p, req.FirstInbound.Port)
			}
			req.FirstInbound.Port = p
		}

		// --- 2. install prerequisites (curl + unzip) ---
		h.SetStep("prereqs", 10)
		if err := EnsureBinaryInstalled(ctx, "curl", "curl"); err != nil {
			return h.Fail("E_APT_CURL", err.Error(), true)
		}
		if err := EnsureBinaryInstalled(ctx, "unzip", "unzip"); err != nil {
			return h.Fail("E_APT_UNZIP", err.Error(), true)
		}

		// --- 3. acquire backup dir for any system mutation ---
		backupDir, err := EnsureBackupDir(h.TaskID())
		if err != nil {
			return h.Fail("E_BACKUP_DIR", err.Error(), false)
		}
		h.LogF("backup dir: %s", backupDir)

		// --- 4. download + extract xray binary if missing ---
		if !alreadyHaveBinary {
			h.SetStep("download_xray", 30)
			arch := xrayArchSuffix()
			if arch == "" {
				return h.Fail("E_UNSUPPORTED_ARCH",
					"only amd64 / arm64 are supported", false)
			}
			zipURL := fmt.Sprintf(xrayURLTemplate, xrayVersion, arch)
			// Cache the zip under /var/cache so a re-install (or a
			// concurrent chain hop on the same server) skips the GitHub
			// round-trip. The file is small (~5 MB) and disk is cheap
			// on the kind of VPS users actually buy.
			zipPath := filepath.Join(CacheDir, fmt.Sprintf("Xray-linux-%s-%s.zip", arch, xrayVersion))
			h.LogF("downloading %s (or reusing cache at %s)", zipURL, zipPath)
			if err := EnsureCachedDownload(ctx, zipURL, zipPath,
				xrayZipSha256ByArch[arch]); err != nil {
				return h.Fail("E_DOWNLOAD", err.Error(), true)
			}
			// Don't delete on exit — leave it cached for next time.

			h.SetStep("extract_xray", 45)
			extractDir := filepath.Join(os.TempDir(), "xray-extract-"+h.TaskID())
			defer os.RemoveAll(extractDir)
			if err := os.MkdirAll(extractDir, 0o755); err != nil {
				return h.Fail("E_EXTRACT_MKDIR", err.Error(), false)
			}
			if _, err := Run(ctx, "unzip", "-qo", zipPath, "-d", extractDir); err != nil {
				return h.Fail("E_EXTRACT", err.Error(), true)
			}
			binIn := filepath.Join(extractDir, "xray")
			binData, err := os.ReadFile(binIn)
			if err != nil {
				return h.Fail("E_EXTRACT_NO_BIN",
					"xray binary missing from release archive: "+err.Error(),
					false)
			}
			if err := AtomicWrite(XrayInstallPath, binData, 0o755); err != nil {
				return h.Fail("E_INSTALL_BIN", err.Error(), false)
			}
			h.LogF("installed xray binary at %s (%d bytes)",
				XrayInstallPath, len(binData))
		}

		// --- 5. data dir ---
		if err := os.MkdirAll(XrayDataDir, 0o755); err != nil {
			return h.Fail("E_DATA_DIR", err.Error(), false)
		}

		// --- 5b. geo-dat files (skip if both already present) ---
		h.SetStep("geo_dat", 55)
		if err := ensureGeoDat(ctx, h); err != nil {
			// Geo-dat download failures are non-fatal — xray still
			// runs without geosite/geoip rules. We surface a warning
			// so the user knows kind=geosite/geoip will not work.
			h.LogF("WARN geo-dat: %v (kind=geosite/geoip rules will fail until next deploy)", err)
		}

		// --- 6. config.json — minimal if first install, otherwise leave ---
		h.SetStep("write_config", 60)
		var firstInbound *storage.XrayInbound
		if !alreadyHaveBinary || !fileExists(XrayConfigPath) {
			cfg, inbound, err := buildInitialConfig(req.FirstInbound)
			if err != nil {
				return h.Fail("E_BUILD_CONFIG", err.Error(), false)
			}
			if err := BackupFile(XrayConfigPath, backupDir); err != nil {
				return h.Fail("E_BACKUP_CONFIG", err.Error(), false)
			}
			cfgBytes, _ := json.MarshalIndent(cfg, "", "  ")
			if err := AtomicWrite(XrayConfigPath, cfgBytes, 0o600); err != nil {
				return h.Fail("E_WRITE_CONFIG", err.Error(), false)
			}
			firstInbound = inbound
		}

		// --- 7. systemd unit ---
		h.SetStep("systemd_unit", 70)
		if err := BackupFile(XrayUnitPath, backupDir); err != nil {
			return h.Fail("E_BACKUP_UNIT", err.Error(), false)
		}
		if err := AtomicWrite(XrayUnitPath, []byte(xraySystemdUnit), 0o644); err != nil {
			return h.Fail("E_WRITE_UNIT", err.Error(), false)
		}
		if _, err := Run(ctx, "systemctl", "daemon-reload"); err != nil {
			return h.Fail("E_DAEMON_RELOAD", err.Error(), true)
		}

		// --- 8. enable VPN-relevant sysctls (just ip_forward for now) ---
		h.SetStep("sysctl", 75)
		if err := SysctlSet(ctx, backupDir, "net.ipv4.ip_forward", "1"); err != nil {
			h.LogF("WARN sysctl ip_forward: %v (continuing — relays may be IPv6-only)", err)
		}

		// --- 9. open firewall port if there is one ---
		if firstInbound != nil {
			h.SetStep("firewall", 80)
			if err := openFirewallPort(ctx, firstInbound.Port); err != nil {
				h.LogF("WARN firewall: %v (continuing — host may be open by default)", err)
			}
		}

		// --- 10. start unit ---
		h.SetStep("systemd_start", 85)
		if _, err := Run(ctx, "systemctl", "enable", "--now", "xray"); err != nil {
			h.LogF("rolling back: systemctl start failed: %v", err)
			h.SetStep("rollback", 90)
			rollbackConfig(ctx, backupDir, alreadyRunning)
			return h.FailRolledBack("E_SYSTEMCTL_START", err.Error(), true)
		}

		// --- 11. healthcheck: xray must be ACTIVE (not crash-looping) and,
		//         if we made an inbound, actually listening on its port.
		//         Checking is-active (improvement #3) avoids a false pass
		//         when another service holds the port and xray is dying. ---
		h.SetStep("healthcheck", 95)
		hcPort := 0
		if firstInbound != nil {
			hcPort = firstInbound.Port
		}
		if err := waitXrayHealthy(ctx, hcPort); err != nil {
			h.LogF("rolling back: healthcheck failed: %v", err)
			rollbackConfig(ctx, backupDir, alreadyRunning)
			return h.FailRolledBack("E_HEALTHCHECK", err.Error(), true)
		}

		// --- 12. persist inbound row if we made one ---
		var inboundRes *InboundResult
		if firstInbound != nil {
			if err := db.InsertXrayInbound(firstInbound); err != nil {
				h.LogF("WARN persist inbound: %v (xray still running, just no DB row)", err)
			}
			inboundRes = &InboundResult{
				InboundID:  firstInbound.ID,
				Protocol:   firstInbound.Protocol,
				Port:       firstInbound.Port,
				ProfileURI: firstInbound.ProfileURI,
			}
		}

		// --- 13. re-apply config if there are pre-existing bypass rules ---
		// Lets the user define routing rules before deploying xray for
		// the first time — phase-1 deploy would otherwise drop them.
		rules, _ := db.ListBypassRules()
		if len(rules) > 0 {
			h.LogF("re-applying config with %d bypass rule(s)", len(rules))
			if err := ApplyBypassToXray(db); err != nil {
				h.LogF("WARN apply bypass after deploy: %v", err)
			}
		}

		return h.Ok(&XrayDeployResult{
			XrayVersion: xrayVersion,
			Inbound:     inboundRes,
		})
	}
}

// rollbackConfig restores the pre-task config + unit and stops the
// service if it wasn't already running. Best-effort — we want to leave
// the box in a usable state even if rollback partly fails.
func rollbackConfig(ctx context.Context, backupDir string, wasRunning bool) {
	_, _ = exec.CommandContext(ctx, "systemctl", "stop", "xray").CombinedOutput()
	_ = RestoreFile(XrayConfigPath, backupDir)
	_ = RestoreFile(XrayUnitPath, backupDir)
	_, _ = exec.CommandContext(ctx, "systemctl", "daemon-reload").CombinedOutput()
	if wasRunning {
		_, _ = exec.CommandContext(ctx, "systemctl", "start", "xray").CombinedOutput()
	}
}

// buildInitialConfig produces a minimal xray config with one VLESS
// inbound + freedom outbound. Adds the inbound to the result so the
// runner can persist + announce it.
//
// If req.ServerName is non-empty, the inbound is wrapped in VLESS+REALITY
// using that SNI as both the public "serverNames" and the upstream "dest"
// target. Reality plain ports are 443 by default (so the masquerade is
// believable); the caller can override via req.Port.
//
// If req.ServerName is empty we fall back to plain VLESS-TCP — left in
// for tests + the (rare) "I want pure inside-LAN tunnel" case.
func buildInitialConfig(req *InboundSpec) (any, *storage.XrayInbound, error) {
	if req == nil {
		// No inbound — fall back to an empty config that still passes xray
		// validation (one freedom outbound, no listeners). Lets us reach
		// the "xray active" state without committing to a profile yet.
		return map[string]any{
			"log":       map[string]any{"loglevel": "warning"},
			"inbounds":  []any{},
			"outbounds": []any{map[string]any{"protocol": "freedom"}},
		}, nil, nil
	}
	if req.Protocol != "" && req.Protocol != "vless" {
		return nil, nil, fmt.Errorf("phase 1 only supports vless inbounds (got %q)", req.Protocol)
	}
	port := req.Port
	if port == 0 {
		if req.ServerName != "" {
			port = 443 // Reality masquerade is believable on 443
		} else {
			var err error
			port, err = pickFreePort()
			if err != nil {
				return nil, nil, err
			}
		}
	}
	uuid := req.UUID
	if uuid == "" {
		uuid = newUUID()
	}
	inboundID := "vless-" + randomHex(4)
	label := req.Label
	if label == "" {
		label = "NetGuard"
	}

	var inboundCfg map[string]any
	var uri string
	if req.ServerName != "" {
		// VLESS + Reality — the only protocol pair worth deploying inside
		// RF in 2025+: handshake is indistinguishable from a real TLS
		// session to the SNI host, the DPI rigs that block plain VLESS
		// can't tell it apart from a TLS-1.3 session to vk.com.
		priv, pub, err := generateRealityKeypair()
		if err != nil {
			return nil, nil, fmt.Errorf("reality keypair: %w", err)
		}
		shortID := randomHex(4) // 8 hex chars — xray accepts 0-16
		dest := req.ServerName + ":443"
		inboundCfg = map[string]any{
			"tag":      inboundID,
			"port":     port,
			"protocol": "vless",
			"settings": map[string]any{
				"clients": []any{
					map[string]any{"id": uuid, "flow": "xtls-rprx-vision"},
				},
				"decryption": "none",
			},
			"streamSettings": map[string]any{
				"network":  "tcp",
				"security": "reality",
				"realitySettings": map[string]any{
					"show":        false,
					"dest":        dest,
					"xver":        0,
					"serverNames": []string{req.ServerName},
					"privateKey":  priv,
					"shortIds":    []string{shortID},
				},
			},
		}
		hostForURI, _ := os.Hostname()
		if hostForURI == "" {
			hostForURI = "agent-host"
		}
		uri = buildVlessRealityURI(uuid, hostForURI, port, label, req.ServerName, pub, shortID)
	} else {
		inboundCfg = map[string]any{
			"tag":      inboundID,
			"port":     port,
			"protocol": "vless",
			"settings": map[string]any{
				"clients": []any{
					map[string]any{"id": uuid, "flow": ""},
				},
				"decryption": "none",
			},
			"streamSettings": map[string]any{
				"network":  "tcp",
				"security": "none",
			},
		}
		hostForURI, _ := os.Hostname()
		if hostForURI == "" {
			hostForURI = "agent-host"
		}
		uri = buildVlessURI(uuid, hostForURI, port, label)
	}
	cfg := map[string]any{
		"log":       map[string]any{"loglevel": "warning"},
		"inbounds":  []any{inboundCfg},
		"outbounds": []any{map[string]any{"protocol": "freedom"}},
	}

	cfgJSON, _ := json.Marshal(inboundCfg)
	inbound := &storage.XrayInbound{
		ID:         inboundID,
		Protocol:   "vless",
		Port:       port,
		ConfigJSON: string(cfgJSON),
		ProfileURI: uri,
		CreatedAt:  time.Now(),
	}
	return cfg, inbound, nil
}

// generateRealityKeypair returns a base64url-no-pad encoded X25519
// (privateKey, publicKey) — the format xray's realitySettings.privateKey
// and the client URI's `pbk` parameter expect. Standard-library only;
// matches what `xray x25519` emits byte-for-byte.
func generateRealityKeypair() (privB64, pubB64 string, err error) {
	curve := ecdh.X25519()
	priv, err := curve.GenerateKey(rand.Reader)
	if err != nil {
		return "", "", err
	}
	privB64 = base64.RawURLEncoding.EncodeToString(priv.Bytes())
	pubB64 = base64.RawURLEncoding.EncodeToString(priv.PublicKey().Bytes())
	return privB64, pubB64, nil
}

// buildVlessURI assembles a vless://uuid@host:port?... URI. The hash
// portion is the human-readable label that NetGuard shows in the server
// list.
//
// The Android client will REPLACE the host with the public IP it used
// to reach the agent — we don't have a reliable view of that from
// inside the box (NAT, multiple interfaces). The placeholder we put in
// here is just so the URI is structurally complete.
func buildVlessURI(uuid, host string, port int, label string) string {
	q := url.Values{}
	q.Set("encryption", "none")
	q.Set("type", "tcp")
	q.Set("security", "none")
	return fmt.Sprintf("vless://%s@%s:%d?%s#%s",
		uuid, host, port, q.Encode(), url.QueryEscape(label))
}

// buildVlessRealityURI assembles a vless://uuid@host:port?security=reality...
// URI consumable by any modern xray client (v2rayNG, Hiddify, Streisand,
// NekoBox). Query keys: security, encryption, type, sni, fp, pbk, sid,
// spx, flow — names match what xray-core's URI parser expects.
func buildVlessRealityURI(uuid, host string, port int, label, sni, pbk, sid string) string {
	q := url.Values{}
	q.Set("security", "reality")
	q.Set("encryption", "none")
	q.Set("type", "tcp")
	q.Set("sni", sni)
	q.Set("fp", "chrome")
	q.Set("pbk", pbk)
	q.Set("sid", sid)
	q.Set("spx", "/")
	q.Set("flow", "xtls-rprx-vision")
	return fmt.Sprintf("vless://%s@%s:%d?%s#%s",
		uuid, host, port, q.Encode(), url.QueryEscape(label))
}

// xraySystemdUnit is what we write to /etc/systemd/system/xray.service.
// One-shot ExecStart, restart-on-failure, no hardening flags (xray needs
// CAP_NET_ADMIN if Reality is ever enabled; keep it simple for phase 1).
//
// XRAY_LOCATION_ASSET points at the directory holding geoip.dat +
// geosite.dat (downloaded during deploy). Without this var xray
// defaults to looking next to the binary, which only works if the dat
// files happen to live there.
const xraySystemdUnit = `[Unit]
Description=Xray Service (managed by netguard-agent)
Documentation=https://xtls.github.io/
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
Environment=XRAY_LOCATION_ASSET=/usr/local/share/xray
ExecStart=/usr/local/bin/xray -config /etc/xray/config.json
Restart=on-failure
RestartSec=3
User=root
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`

// XrayRefreshGeoDat is a Runner that re-downloads pinned geo-data
// regardless of whether files already exist. Useful when the user
// bumps the agent and wants the latest dat blobs without redeploying
// the whole xray stack.
func XrayRefreshGeoDat() tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		// Force re-download by wiping the existing files first.
		_ = os.Remove(filepath.Join(XrayAssetDir, "geoip.dat"))
		_ = os.Remove(filepath.Join(XrayAssetDir, "geosite.dat"))
		h.SetStep("download", 30)
		if err := ensureGeoDat(ctx, h); err != nil {
			return h.Fail("E_GEO_DAT", err.Error(), true)
		}
		// xray reads geoip/geosite at config load — restart to pick up
		// the fresh files.
		h.SetStep("reload", 80)
		if SystemctlIsActive(ctx, "xray") {
			if _, err := Run(ctx, "systemctl", "restart", "xray"); err != nil {
				return h.Fail("E_RESTART_XRAY", err.Error(), true)
			}
		}
		return h.Ok(map[string]any{"ok": true})
	}
}

// ensureGeoDat downloads pinned geoip.dat + geosite.dat into
// XrayAssetDir if either is missing. Idempotent — re-runs are no-ops
// when both files exist. We don't verify sha256 of existing files
// here on purpose: the user might have a newer set placed manually,
// and overwriting would be surprising. Bumping the constants above
// forces a fresh download on the next deploy by changing the URLs.
func ensureGeoDat(ctx context.Context, h *tasks.Handle) error {
	if err := os.MkdirAll(XrayAssetDir, 0o755); err != nil {
		return fmt.Errorf("mkdir %s: %w", XrayAssetDir, err)
	}
	jobs := []struct {
		path, url, sha, label string
	}{
		{filepath.Join(XrayAssetDir, "geoip.dat"), geoipURL, geoipSha256, "geoip"},
		{filepath.Join(XrayAssetDir, "geosite.dat"), geositeURL, geositeSha256, "geosite"},
	}
	for _, j := range jobs {
		if fileExists(j.path) {
			h.LogF("geo-dat: %s already present", j.label)
			continue
		}
		h.LogF("geo-dat: downloading %s", j.label)
		if err := DownloadAndVerify(ctx, j.url, j.path, j.sha); err != nil {
			return fmt.Errorf("%s: %w", j.label, err)
		}
		h.LogF("geo-dat: installed %s", j.path)
	}
	return nil
}

// --- small helpers --------------------------------------------------------

func xrayArchSuffix() string {
	switch runtime.GOARCH {
	case "amd64":
		return "64"
	case "arm64":
		return "arm64-v8a"
	default:
		return ""
	}
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func pickFreePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

// portInUse reports whether something already listens on 0.0.0.0:port.
// Used as a preflight before we deploy an xray inbound there — binding a
// port a co-located service (e.g. sing-box on :443) already owns makes
// xray crash-loop on "address already in use" forever, which the old
// port-listening healthcheck couldn't even detect (the other service was
// answering on the port).
func portInUse(port int) bool {
	l, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", port))
	if err != nil {
		return true
	}
	_ = l.Close()
	return false
}

// portHolder is a best-effort lookup of the process name listening on
// port (via `ss -ltnp`), purely to make the E_PORT_BUSY message
// actionable ("...in use by sing-box"). Returns "" when unknown.
func portHolder(ctx context.Context, port int) string {
	out, err := exec.CommandContext(ctx, "ss", "-ltnp").Output()
	if err != nil {
		return ""
	}
	needle := fmt.Sprintf(":%d ", port)
	for _, line := range strings.Split(string(out), "\n") {
		if !strings.Contains(line, needle) {
			continue
		}
		// users:(("sing-box",pid=123,fd=7))
		if i := strings.Index(line, `("`); i >= 0 {
			rest := line[i+2:]
			if j := strings.Index(rest, `"`); j >= 0 {
				return rest[:j]
			}
		}
	}
	return ""
}

// resolveInboundPort decides the final listen port for a new inbound and
// guarantees it is free at decision time. This is improvements #1 + #2:
//
//   - explicit port: returned as-is, or ErrPortInUse if occupied (so the
//     app shows "pick another port" instead of a silent crash-loop).
//   - port==0 (auto): Reality prefers 443 when free (believable
//     masquerade); if 443 is taken (co-located sing-box etc.) we auto-pick
//     a free high port instead of blindly using 443. Plain VLESS always
//     auto-picks. The app therefore never has to know a free port up front.
func resolveInboundPort(ctx context.Context, reqPort int, reality bool) (int, error) {
	if reqPort != 0 {
		if portInUse(reqPort) {
			msg := fmt.Sprintf("port %d is already in use", reqPort)
			if h := portHolder(ctx, reqPort); h != "" {
				msg += " by " + h
			}
			return 0, fmt.Errorf("%w: %s - pick another port or stop that service", ErrPortInUse, msg)
		}
		return reqPort, nil
	}
	if reality && !portInUse(443) {
		return 443, nil
	}
	return pickFreePort()
}

// waitXrayHealthy blocks until xray.service is active AND (when port>0) the
// port is accepting connections, or until ~15s elapse. This is improvement
// #3: checking is-active (not merely "something listens on the port")
// catches the case where a DIFFERENT service holds the port and xray is
// actually crash-looping behind it - the old WaitPortListening check would
// false-pass because the other service answered.
func waitXrayHealthy(ctx context.Context, port int) error {
	deadline := time.Now().Add(15 * time.Second)
	for {
		active := SystemctlIsActive(ctx, "xray")
		listening := true
		if port > 0 {
			c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
			if err != nil {
				listening = false
			} else {
				_ = c.Close()
			}
		}
		if active && listening {
			return nil
		}
		if time.Now().After(deadline) {
			if !active {
				return fmt.Errorf("xray.service is not active (crash-looping?); check `journalctl -u xray -n 30`")
			}
			return fmt.Errorf("xray is active but port %d is not accepting connections", port)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(1 * time.Second):
		}
	}
}

func newUUID() string {
	// RFC 4122 v4 — 16 bytes of randomness with version + variant nibbles.
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// openFirewallPort tries the simplest path that's likely available on a
// fresh Debian/Ubuntu — direct iptables INPUT ACCEPT for the TCP port.
// We don't touch ufw because most VPS images have it inactive; users
// who installed ufw can override with their own rule afterwards.
func openFirewallPort(ctx context.Context, port int) error {
	if !WhichExists("iptables") {
		// On a really stripped-down image iptables itself is missing;
		// silently skip — host firewall is probably permissive anyway.
		return nil
	}
	args := []string{"-I", "INPUT", "-p", "tcp", "--dport",
		fmt.Sprintf("%d", port), "-m", "comment",
		"--comment", "netguard-agent xray inbound", "-j", "ACCEPT"}
	// Idempotency: check if a rule with that comment already exists.
	existing, err := exec.CommandContext(ctx, "iptables", "-S", "INPUT").Output()
	if err == nil && strings.Contains(string(existing), "netguard-agent xray inbound") &&
		strings.Contains(string(existing), fmt.Sprintf("--dport %d ", port)) {
		return nil
	}
	_, err = Run(ctx, "iptables", args...)
	return err
}
