package deploy

import (
	"context"
	"fmt"
	"os/exec"
	"runtime"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

// StripePort is the loopback port the stripe-server listens on and the
// Telemost creator dials for each room pipe. The Kotlin StripeMux MUST use
// the same value (see TelemostRelayManager). Keep them in lockstep.
const StripePort = 38500

const (
	stripeInstallPath = "/usr/local/bin/netguard-stripe-server"
	stripeUnitName    = "netguard-stripe-server.service"
	stripeUnitPath    = "/etc/systemd/system/netguard-stripe-server.service"
)

// stripeUnitTemplate is the systemd unit. Single instance (not templated):
// one stripe-server multiplexes every session by sessionId internally. It
// only listens on loopback (the creator reaches it via 127.0.0.1) and dials
// flow destinations outbound, so it needs no privileged ports and no
// writable paths.
//
// Hardening mirrors the Telemost unit. AF_NETLINK is required: Go's resolver
// walks netlink for DNS, and without it Dial fails with "address family not
// supported".
const stripeUnitTemplate = `[Unit]
Description=NetGuard Telemost stripe-server (exit-side mux)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=wlb
Group=wlb
ExecStart=/usr/local/bin/netguard-stripe-server -port %d
Restart=on-failure
RestartSec=5

NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectHome=true
ProtectSystem=strict
RestrictAddressFamilies=AF_INET AF_INET6 AF_NETLINK AF_UNIX
RestrictNamespaces=true
RestrictRealtime=true
LockPersonality=true

[Install]
WantedBy=multi-user.target
`

// PrepareStripe installs the stripe-server binary + unit and starts it.
// Idempotent and best-effort-friendly: safe to re-run (AtomicWrite replaces
// the binary, enable --now is a no-op if already up). Mirrors PrepareTelemost.
func PrepareStripe() tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		h.SetStep("install_bin", 20)
		blob, err := stripeServerForArch()
		if err != nil {
			return h.Fail("E_UNSUPPORTED_ARCH", err.Error(), false)
		}
		if err := AtomicWrite(stripeInstallPath, blob, 0o755); err != nil {
			return h.Fail("E_INSTALL_BIN", err.Error(), false)
		}

		// Reuse the unprivileged wlb user already used by Telemost.
		h.SetStep("user", 45)
		if err := ensureWlbUser(ctx); err != nil {
			return h.Fail("E_INSTALL", err.Error(), false)
		}

		h.SetStep("systemd_unit", 70)
		unit := fmt.Sprintf(stripeUnitTemplate, StripePort)
		if err := AtomicWrite(stripeUnitPath, []byte(unit), 0o644); err != nil {
			return h.Fail("E_WRITE_UNIT", err.Error(), false)
		}
		if _, err := exec.CommandContext(ctx, "systemctl", "daemon-reload").CombinedOutput(); err != nil {
			return h.Fail("E_DAEMON_RELOAD", err.Error(), true)
		}

		h.SetStep("systemd_start", 85)
		if _, err := exec.CommandContext(ctx, "systemctl", "enable", "--now", stripeUnitName).CombinedOutput(); err != nil {
			return h.Fail("E_SYSTEMCTL_START", err.Error(), true)
		}

		// Healthcheck: should be active within a few seconds (no network
		// negotiation, just a loopback listen).
		h.SetStep("healthcheck", 95)
		deadline := time.Now().Add(15 * time.Second)
		for {
			if SystemctlIsActive(ctx, stripeUnitName) {
				break
			}
			if time.Now().After(deadline) {
				_, _ = exec.CommandContext(ctx, "systemctl", "stop", stripeUnitName).CombinedOutput()
				return h.FailRolledBack("E_HEALTHCHECK",
					"stripe-server did not become active within 15s; check journalctl -u "+stripeUnitName, true)
			}
			time.Sleep(1 * time.Second)
		}

		return h.Ok(map[string]any{
			"prepared": true,
			"port":     StripePort,
		})
	}
}

// stripeServerForArch returns the embedded binary for the host arch.
func stripeServerForArch() ([]byte, error) {
	if runtime.GOARCH != "amd64" && runtime.GOARCH != "arm64" {
		return nil, fmt.Errorf("stripe-server only ships for linux/amd64 + linux/arm64; got %s", runtime.GOARCH)
	}
	return stripeServerBin, nil
}
