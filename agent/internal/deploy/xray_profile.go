package deploy

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"

	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

// XrayAddProfileRequest is the body of POST /v1/xray/profile.
//
// Sync handler — no task FSM needed: editing the live xray config + a
// `systemctl reload` runs in <1s. Returns the new inbound row directly.
type XrayAddProfileRequest struct {
	Protocol string `json:"protocol"` // phase 1 only "vless"
	Port     int    `json:"port"`     // 0 → pick a free one
	UUID     string `json:"uuid"`     // empty → generate
	Label    string `json:"label,omitempty"`
}

// XrayAddProfile inserts a new inbound into the running xray, reloads,
// and persists the row. Returns the same InboundResult shape the deploy
// task uses so the Android client can treat both flows uniformly.
//
// If xray isn't installed at all yet, returns an error — caller should
// hit /v1/xray/deploy first.
func XrayAddProfile(db *storage.DB, req *XrayAddProfileRequest) (*InboundResult, error) {
	if !fileExists(XrayInstallPath) {
		return nil, fmt.Errorf("xray is not installed; call /v1/xray/deploy first")
	}
	if !fileExists(XrayConfigPath) {
		return nil, fmt.Errorf("xray config missing at %s", XrayConfigPath)
	}
	// Build the new inbound using the same helper as the initial deploy.
	spec := &InboundSpec{
		Protocol: req.Protocol,
		Port:     req.Port,
		UUID:     req.UUID,
		Label:    req.Label,
	}
	_, newInbound, err := buildInitialConfig(spec)
	if err != nil {
		return nil, fmt.Errorf("build inbound: %w", err)
	}

	// Read existing config, append the new inbound, atomic-write back.
	cfgBytes, err := os.ReadFile(XrayConfigPath)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	var cfg map[string]any
	if err := json.Unmarshal(cfgBytes, &cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	inbounds, _ := cfg["inbounds"].([]any)
	var newInboundCfg map[string]any
	_ = json.Unmarshal([]byte(newInbound.ConfigJSON), &newInboundCfg)
	cfg["inbounds"] = append(inbounds, newInboundCfg)

	newCfgBytes, _ := json.MarshalIndent(cfg, "", "  ")
	if err := AtomicWrite(XrayConfigPath, newCfgBytes, 0o600); err != nil {
		return nil, fmt.Errorf("write config: %w", err)
	}

	// Open firewall for the new port (idempotent — common.go skips dups).
	ctx := context.Background()
	_ = openFirewallPort(ctx, newInbound.Port)

	// xray doesn't have a SIGHUP reload; restart is fastest.
	// Brief downtime (~1s) — acceptable for an add operation. Profile
	// removal goes through the same path.
	if _, err := exec.CommandContext(ctx, "systemctl", "restart", "xray").CombinedOutput(); err != nil {
		return nil, fmt.Errorf("systemctl restart xray: %w", err)
	}
	if err := WaitPortListening("127.0.0.1", newInbound.Port, 10); err != nil {
		return nil, fmt.Errorf("new inbound port did not open: %w", err)
	}

	if err := db.InsertXrayInbound(newInbound); err != nil {
		return nil, fmt.Errorf("persist inbound: %w", err)
	}

	return &InboundResult{
		InboundID:  newInbound.ID,
		Protocol:   newInbound.Protocol,
		Port:       newInbound.Port,
		ProfileURI: newInbound.ProfileURI,
	}, nil
}

// XrayDeleteProfile removes the inbound with the given inbound_id from
// xray config, restarts xray, drops the DB row.
func XrayDeleteProfile(db *storage.DB, inboundID string) error {
	cfgBytes, err := os.ReadFile(XrayConfigPath)
	if err != nil {
		return fmt.Errorf("read config: %w", err)
	}
	var cfg map[string]any
	if err := json.Unmarshal(cfgBytes, &cfg); err != nil {
		return fmt.Errorf("parse config: %w", err)
	}
	inbounds, _ := cfg["inbounds"].([]any)
	kept := inbounds[:0]
	found := false
	for _, raw := range inbounds {
		m, ok := raw.(map[string]any)
		if ok && m["tag"] == inboundID {
			found = true
			continue
		}
		kept = append(kept, raw)
	}
	if !found {
		return fmt.Errorf("inbound %q not found in xray config", inboundID)
	}
	cfg["inbounds"] = kept

	newCfgBytes, _ := json.MarshalIndent(cfg, "", "  ")
	if err := AtomicWrite(XrayConfigPath, newCfgBytes, 0o600); err != nil {
		return fmt.Errorf("write config: %w", err)
	}
	ctx := context.Background()
	if _, err := exec.CommandContext(ctx, "systemctl", "restart", "xray").CombinedOutput(); err != nil {
		return fmt.Errorf("systemctl restart xray: %w", err)
	}
	return db.DeleteXrayInbound(inboundID)
}

// XrayUninstall is a Runner — stops + disables + removes xray entirely
// so a fresh /v1/xray/deploy can proceed. Used when /v1/xray/deploy
// returned E_XRAY_PREEXISTING and the user confirmed wipe.
//
// Backs up config + unit before removing in case the user changes their
// mind; backup lives in /var/lib/netguard-agent/backups/<task-id>/.
func XrayUninstall(db *storage.DB) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		h.SetStep("backup", 10)
		bk, err := EnsureBackupDir(h.TaskID())
		if err != nil {
			return h.Fail("E_BACKUP_DIR", err.Error(), false)
		}
		_ = BackupFile(XrayConfigPath, bk)
		_ = BackupFile(XrayUnitPath, bk)
		_ = BackupFile(XrayInstallPath, bk)
		h.LogF("backed up to %s", bk)

		h.SetStep("stop", 30)
		_, _ = exec.CommandContext(ctx, "systemctl", "disable", "--now", "xray").CombinedOutput()

		h.SetStep("remove", 60)
		paths := []string{
			XrayConfigPath,
			XrayUnitPath,
			XrayInstallPath,
			"/etc/xray", // dir, will fail if non-empty — that's fine
		}
		for _, p := range paths {
			if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
				h.LogF("WARN remove %s: %v", p, err)
			}
		}

		h.SetStep("daemon-reload", 80)
		_, _ = exec.CommandContext(ctx, "systemctl", "daemon-reload").CombinedOutput()

		// Wipe inbound rows so a subsequent deploy starts clean.
		rows, _ := db.ListXrayInbounds()
		for _, r := range rows {
			_ = db.DeleteXrayInbound(r.ID)
		}
		h.LogF("removed %d inbound rows from agent DB", len(rows))

		return h.Ok(map[string]any{"removed_inbounds": len(rows)})
	}
}
