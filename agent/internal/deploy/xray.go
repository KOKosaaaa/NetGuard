package deploy

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
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
			zipPath := filepath.Join(os.TempDir(), "xray.zip")
			h.LogF("downloading %s", zipURL)
			if err := DownloadAndVerify(ctx, zipURL, zipPath,
				xrayZipSha256ByArch[arch]); err != nil {
				return h.Fail("E_DOWNLOAD", err.Error(), true)
			}
			defer os.Remove(zipPath)

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

		// --- 11. healthcheck: wait until xray binds the port (if we have one) ---
		h.SetStep("healthcheck", 95)
		if firstInbound != nil {
			if err := WaitPortListening("127.0.0.1", firstInbound.Port, 15); err != nil {
				h.LogF("rolling back: healthcheck failed: %v", err)
				rollbackConfig(ctx, backupDir, alreadyRunning)
				return h.FailRolledBack("E_HEALTHCHECK", err.Error(), true)
			}
		} else {
			// No inbound to check — just confirm the unit thinks it's alive.
			time.Sleep(2 * time.Second)
			if !SystemctlIsActive(ctx, "xray") {
				rollbackConfig(ctx, backupDir, alreadyRunning)
				return h.FailRolledBack("E_NOT_ACTIVE",
					"xray.service is not active after start", true)
			}
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
		var err error
		port, err = pickFreePort()
		if err != nil {
			return nil, nil, err
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

	inboundCfg := map[string]any{
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
	cfg := map[string]any{
		"log":       map[string]any{"loglevel": "warning"},
		"inbounds":  []any{inboundCfg},
		"outbounds": []any{map[string]any{"protocol": "freedom"}},
	}

	cfgJSON, _ := json.Marshal(inboundCfg)
	hostForURI, _ := os.Hostname() // user will override in URI; placeholder
	if hostForURI == "" {
		hostForURI = "agent-host"
	}
	uri := buildVlessURI(uuid, hostForURI, port, label)
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

// xraySystemdUnit is what we write to /etc/systemd/system/xray.service.
// One-shot ExecStart, restart-on-failure, no hardening flags (xray needs
// CAP_NET_ADMIN if Reality is ever enabled; keep it simple for phase 1).
const xraySystemdUnit = `[Unit]
Description=Xray Service (managed by netguard-agent)
Documentation=https://xtls.github.io/
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/xray -config /etc/xray/config.json
Restart=on-failure
RestartSec=3
User=root
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`

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
