// services.go — generic restart/start/stop/logs for the systemd units
// the agent deploys.
//
// We DO NOT accept an arbitrary unit name from the API — it would be a
// straight-up RCE pipe (`systemctl start <attacker-string>` runs unit
// dropins owned by other things). Whitelist what the agent has business
// touching; anything else is rejected with E_SERVICE_UNKNOWN.

package deploy

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// AllowedServices is the closed set of unit names the API will act on.
// Bump when phase 2 (telemost) and phase 3+ (sing-box per-feature)
// land additional deployable services.
var AllowedServices = map[string]bool{
	"xray":                      true,
	"sing-box":                  true,
	"headless-telemost-creator": true,
}

var (
	ErrServiceUnknown = errors.New("service is not in the allow-list")
	ErrServiceFailed  = errors.New("systemctl returned non-zero exit")
)

// ServiceAction is one of: restart, start, stop.
// `enable --now` / `disable --now` go through deploy/uninstall paths,
// not this generic control plane.
func ServiceAction(ctx context.Context, name, action string) ([]byte, error) {
	if !AllowedServices[name] {
		return nil, fmt.Errorf("%w: %q", ErrServiceUnknown, name)
	}
	if action != "restart" && action != "start" && action != "stop" {
		return nil, fmt.Errorf("action must be restart|start|stop (got %q)", action)
	}
	cmd := exec.CommandContext(ctx, "systemctl", action, name)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return out, fmt.Errorf("%w: %v (%s)", ErrServiceFailed, err, strings.TrimSpace(string(out)))
	}
	return out, nil
}

// ServiceLogs returns the last `lines` of journalctl for a whitelisted
// unit. Bounded to avoid a malicious caller pulling gigabytes — the
// Android UI doesn't need more than a few hundred lines.
func ServiceLogs(ctx context.Context, name string, lines int) ([]byte, error) {
	if !AllowedServices[name] {
		return nil, fmt.Errorf("%w: %q", ErrServiceUnknown, name)
	}
	if lines <= 0 {
		lines = 200
	}
	if lines > 2000 {
		lines = 2000
	}
	// --no-pager keeps the output single-shot (no `less` invocation),
	// -o cat strips the journal metadata (we only want what the unit
	// itself wrote), -n bounds the size.
	cmd := exec.CommandContext(ctx, "journalctl",
		"-u", name, "--no-pager", "-o", "cat", "-n", strconv.Itoa(lines))
	out, err := cmd.CombinedOutput()
	if err != nil {
		return out, fmt.Errorf("journalctl: %w (%s)", err, strings.TrimSpace(string(out)))
	}
	return out, nil
}

// ServiceStatus is a thin wrapper around `systemctl is-active` +
// `systemctl show` for one unit. Mirrors sysinfo.probeOne but exposes
// it directly for the per-service detail screen.
func ServiceStatus(ctx context.Context, name string) (map[string]any, error) {
	if !AllowedServices[name] {
		return nil, fmt.Errorf("%w: %q", ErrServiceUnknown, name)
	}
	active := false
	if out, err := exec.CommandContext(ctx, "systemctl", "is-active", name).Output(); err == nil {
		active = strings.TrimSpace(string(out)) == "active"
	}
	res := map[string]any{
		"name":   name,
		"active": active,
		"checked_at": time.Now().Format(time.RFC3339),
	}
	if !active {
		return res, nil
	}
	if show, err := exec.CommandContext(ctx, "systemctl", "show", name,
		"--property=MainPID", "--property=ActiveEnterTimestamp",
		"--property=MemoryCurrent",
	).Output(); err == nil {
		for _, line := range strings.Split(string(show), "\n") {
			kv := strings.SplitN(line, "=", 2)
			if len(kv) != 2 {
				continue
			}
			switch kv[0] {
			case "MainPID":
				if pid, err := strconv.Atoi(kv[1]); err == nil && pid > 0 {
					res["pid"] = pid
				}
			case "ActiveEnterTimestamp":
				if kv[1] != "" {
					res["since"] = kv[1]
				}
			case "MemoryCurrent":
				if mem, err := strconv.ParseUint(kv[1], 10, 64); err == nil && mem > 0 {
					res["memory_mb"] = mem / 1024 / 1024
				}
			}
		}
	}
	return res, nil
}
