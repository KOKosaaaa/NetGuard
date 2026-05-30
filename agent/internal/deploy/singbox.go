// singbox.go — sing-box installer for the managed server.
//
// ┌─────────────────────────────────────────────────────────────────────┐
// │ DEFERRED — NOT WIRED IN (2026-05-31).                                 │
// │                                                                       │
// │ This installer is complete and works, but it is intentionally NOT    │
// │ called from ProvisionAll (provision.go) right now. Nothing in the    │
// │ app consumes sing-box yet — VPN profiles are created via xray        │
// │ (VLESS+Reality) and Telemost is its own path. Auto-installing it     │
// │ would just pull ~24 MB and leave an idle service on every server.    │
// │                                                                       │
// │ Kept for a future sing-box-backed feature — most likely Hysteria2 /  │
// │ TUIC profiles (QUIC/UDP), which hold up where DPI throttles TCP and  │
// │ xray's VLESS+Reality struggles. When that ships, re-add the          │
// │ `SingboxDeploy()` call in ProvisionAll and add a profile path that   │
// │ writes a real sing-box inbound + emits a client URI.                 │
// └─────────────────────────────────────────────────────────────────────┘
//
// Mirrors the xray install skeleton (pinned version + sha256 per arch,
// atomic writes, healthcheck) but uses a tar.gz release and Go-style arch
// names (amd64 / arm64). Installs a verified binary + a minimal valid
// config + a systemd unit.

package deploy

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

// firstLine returns the first non-empty line of s, trimmed (for logging
// `sing-box version` output without the trailing build metadata noise).
func firstLine(s string) string {
	for _, l := range strings.Split(s, "\n") {
		if t := strings.TrimSpace(l); t != "" {
			return t
		}
	}
	return ""
}

const (
	SingboxInstallPath = "/usr/local/bin/sing-box"
	SingboxConfigPath  = "/etc/sing-box/config.json"
	SingboxUnitPath    = "/etc/systemd/system/sing-box.service"

	// Pinned sing-box release. Bump version + both sha256 together.
	singboxVersion     = "1.13.12"
	singboxURLTemplate = "https://github.com/SagerNet/sing-box/releases/download/v%s/sing-box-%s-linux-%s.tar.gz"
)

// SHA256 of the linux tarballs, captured 2026-05-31 from the SagerNet
// release page via `curl -L | sha256sum`. Keyed by runtime.GOARCH.
var singboxSha256ByArch = map[string]string{
	"amd64": "1540533adb3df24f5ad5f14b5c7ca3dbc2401b10a1c1eb278fcadcada47ec6c4",
	"arm64": "1ffa3b48ad6fa98f9fd810482e39bdd5b6157782ef11ce37d67bdcfd9338547a",
}

// singboxMinimalConfig is a valid config with no listeners — sing-box
// stays up as an idle daemon until a real feature rewrites it.
const singboxMinimalConfig = `{
  "log": { "level": "warn" },
  "inbounds": [],
  "outbounds": [ { "type": "direct", "tag": "direct" } ]
}
`

const singboxSystemdUnit = `[Unit]
Description=sing-box service (NetGuard-managed)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
ExecStart=/usr/local/bin/sing-box run -c /etc/sing-box/config.json
Restart=on-failure
RestartSec=5
LimitNOFILE=infinity
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
`

// SingboxDeploy installs sing-box (binary + minimal config + unit) and
// tries to bring it up. Install success is decided by the binary running
// (`sing-box version`) + a valid config (`sing-box check`); the service
// staying active is best-effort (an empty-inbound daemon may behave
// differently across versions, so we never leave a crash-loop behind).
func SingboxDeploy() tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		h.SetStep("detect", 5)
		arch := runtime.GOARCH
		if arch != "amd64" && arch != "arm64" {
			return h.Fail("E_UNSUPPORTED_ARCH",
				"sing-box: only amd64 / arm64 are supported", false)
		}
		alreadyBinary := WhichExists("sing-box") || fileExists(SingboxInstallPath)
		hasConfig := fileExists(SingboxConfigPath)

		h.SetStep("prereqs", 10)
		if err := EnsureBinaryInstalled(ctx, "curl", "curl"); err != nil {
			return h.Fail("E_APT_CURL", err.Error(), true)
		}
		if err := EnsureBinaryInstalled(ctx, "tar", "tar"); err != nil {
			return h.Fail("E_APT_TAR", err.Error(), true)
		}

		if !alreadyBinary {
			h.SetStep("download", 30)
			url := fmt.Sprintf(singboxURLTemplate, singboxVersion, singboxVersion, arch)
			tgz := filepath.Join(CacheDir,
				fmt.Sprintf("sing-box-%s-linux-%s.tar.gz", singboxVersion, arch))
			h.LogF("downloading %s (or reusing cache)", url)
			if err := EnsureCachedDownload(ctx, url, tgz, singboxSha256ByArch[arch]); err != nil {
				return h.Fail("E_DOWNLOAD", err.Error(), true)
			}

			h.SetStep("extract", 45)
			extractDir := filepath.Join(os.TempDir(), "singbox-extract-"+h.TaskID())
			defer os.RemoveAll(extractDir)
			if err := os.MkdirAll(extractDir, 0o755); err != nil {
				return h.Fail("E_EXTRACT_MKDIR", err.Error(), false)
			}
			if _, err := Run(ctx, "tar", "xzf", tgz, "-C", extractDir); err != nil {
				return h.Fail("E_EXTRACT", err.Error(), true)
			}
			// tarball layout: sing-box-<ver>-linux-<arch>/sing-box
			binIn := filepath.Join(extractDir,
				fmt.Sprintf("sing-box-%s-linux-%s", singboxVersion, arch), "sing-box")
			data, err := os.ReadFile(binIn)
			if err != nil {
				return h.Fail("E_EXTRACT_NO_BIN",
					"sing-box binary missing from release archive: "+err.Error(), false)
			}
			if err := AtomicWrite(SingboxInstallPath, data, 0o755); err != nil {
				return h.Fail("E_INSTALL_BIN", err.Error(), false)
			}
			h.LogF("installed sing-box %s at %s (%d bytes)", singboxVersion, SingboxInstallPath, len(data))
		}

		// Binary must actually run on this host (catches a wrong-arch /
		// corrupt download before we wire a service to it).
		if out, err := Run(ctx, SingboxInstallPath, "version"); err != nil {
			return h.Fail("E_SINGBOX_RUN",
				"sing-box binary won't run here: "+err.Error(), false)
		} else {
			h.LogF("sing-box version: %s", firstLine(string(out)))
		}

		h.SetStep("write_config", 60)
		if !hasConfig {
			if err := AtomicWrite(SingboxConfigPath, []byte(singboxMinimalConfig), 0o600); err != nil {
				return h.Fail("E_WRITE_CONFIG", err.Error(), false)
			}
		}
		// Validate whatever config is in place.
		if _, err := Run(ctx, SingboxInstallPath, "check", "-c", SingboxConfigPath); err != nil {
			return h.Fail("E_SINGBOX_CONFIG", "sing-box config invalid: "+err.Error(), false)
		}

		h.SetStep("systemd_unit", 75)
		if err := AtomicWrite(SingboxUnitPath, []byte(singboxSystemdUnit), 0o644); err != nil {
			return h.Fail("E_WRITE_UNIT", err.Error(), false)
		}
		if _, err := Run(ctx, "systemctl", "daemon-reload"); err != nil {
			return h.Fail("E_DAEMON_RELOAD", err.Error(), true)
		}

		// Try to start it. Best-effort: if an empty-inbound daemon doesn't
		// stay up on this version, stop+disable so we don't leave a
		// crash-loop, and still report a successful install.
		h.SetStep("systemd_start", 85)
		running := false
		if _, err := Run(ctx, "systemctl", "enable", "--now", "sing-box"); err == nil {
			deadline := time.Now().Add(12 * time.Second)
			for time.Now().Before(deadline) {
				if SystemctlIsActive(ctx, "sing-box") {
					running = true
					break
				}
				time.Sleep(2 * time.Second)
			}
		}
		if !running {
			h.LogF("sing-box didn't stay active with an empty config; stopping to avoid a restart loop (install still OK)")
			_, _ = Run(ctx, "systemctl", "disable", "--now", "sing-box")
		}

		return h.Ok(map[string]any{
			"singbox_version": singboxVersion,
			"installed":       true,
			"running":         running,
		})
	}
}
