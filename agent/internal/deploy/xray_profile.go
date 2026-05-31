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
	Protocol   string `json:"protocol"`              // phase 1 only "vless"
	Port       int    `json:"port"`                  // 0 → pick a free one (443 for Reality)
	UUID       string `json:"uuid"`                  // empty → generate
	ServerName string `json:"server_name,omitempty"` // SNI; non-empty → VLESS+REALITY
	Label      string `json:"label,omitempty"`
	// ChainTo: when non-nil, this inbound forwards into a freshly added
	// VLESS outbound pointing at the next hop instead of falling through
	// to the freedom outbound. Caller (the chain orchestrator on the
	// Android side) walks the chain from exit→entry, so by the time we
	// see ChainTo populated, the next hop's inbound already exists.
	ChainTo *ChainTarget `json:"chain_to,omitempty"`
}

// ChainTarget describes the next hop in a multi-hop VPN chain — i.e.
// where the VLESS outbound on THIS server should connect to. Mirrors
// the public vless-URI parameters so the Android-side orchestrator can
// just pull them out of the previous hop's profile_uri response.
//
// Reality fields (ServerName, PublicKey, ShortID) are required when
// the next-hop inbound is VLESS+REALITY; for plain VLESS-TCP they're
// left empty.
type ChainTarget struct {
	Host       string `json:"host"`
	Port       int    `json:"port"`
	UUID       string `json:"uuid"`
	ServerName string `json:"server_name,omitempty"`
	PublicKey  string `json:"public_key,omitempty"`
	ShortID    string `json:"short_id,omitempty"`
	Flow       string `json:"flow,omitempty"` // "xtls-rprx-vision" for Reality, "" for plain VLESS
}

// XrayAddProfile inserts a new inbound into the running xray, reloads,
// and persists the row. Returns the same InboundResult shape the deploy
// task uses so the Android client can treat both flows uniformly.
//
// If xray isn't installed at all yet, returns an error — caller should
// hit /v1/xray/deploy first.
//
// When req.ChainTo is non-nil this is an intermediate / entry hop of a
// multi-hop chain: we additionally add a VLESS outbound pointing at the
// next hop AND a routing rule binding this inbound's tag to that
// outbound's tag. Everything is rolled back atomically on failure so a
// partial write can't desync xray config vs the agent DB.
func XrayAddProfile(db *storage.DB, req *XrayAddProfileRequest) (*InboundResult, error) {
	// Serialize against any other config mutation (another add, a delete,
	// or a bypass-rule apply) so concurrent edits can't drop each other.
	xrayConfigMu.Lock()
	defer xrayConfigMu.Unlock()
	if !fileExists(XrayInstallPath) {
		return nil, fmt.Errorf("xray is not installed; call /v1/xray/deploy first")
	}
	if !fileExists(XrayConfigPath) {
		return nil, fmt.Errorf("xray config missing at %s", XrayConfigPath)
	}
	// Resolve + preflight the port first (improvements #1/#2): explicit
	// busy port → ErrPortInUse (router maps to E_PORT_BUSY); auto port
	// prefers a free 443 for Reality, else picks a free high port. This
	// also stops a second profile from silently colliding with the first.
	ctx := context.Background()
	resolvedPort, err := resolveInboundPort(ctx, req.Port, req.ServerName != "")
	if err != nil {
		return nil, err
	}

	// Build the new inbound using the same helper as the initial deploy.
	spec := &InboundSpec{
		Protocol:   req.Protocol,
		Port:       resolvedPort,
		UUID:       req.UUID,
		ServerName: req.ServerName,
		Label:      req.Label,
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

	// Multi-hop chain wiring: add a VLESS outbound + a routing rule so
	// traffic landing on this new inbound is forwarded straight into the
	// next hop instead of falling through to the freedom outbound.
	if req.ChainTo != nil {
		outboundTag := "chain-" + newInbound.ID
		outboundCfg, err := buildVlessOutbound(req.ChainTo, outboundTag)
		if err != nil {
			return nil, fmt.Errorf("build chain outbound: %w", err)
		}
		outbounds, _ := cfg["outbounds"].([]any)
		// APPEND, never prepend: xray treats the FIRST outbound as the
		// default for any traffic that matches no routing rule. The exit
		// hop's inbound has no rule, so a prepended chain outbound becomes
		// the default and the exit hop forwards back into the chain instead
		// of exiting to the internet — an A→B→A→B loop (which also OOM-kills
		// xray). Rules select the chain outbound by tag, so it does not need
		// to be first; keeping freedom first preserves a real exit.
		cfg["outbounds"] = append(outbounds, outboundCfg)

		// Routing section may be absent (the initial deploy doesn't
		// create one); initialize it on-demand.
		routing, _ := cfg["routing"].(map[string]any)
		if routing == nil {
			routing = map[string]any{"domainStrategy": "AsIs"}
		}
		rules, _ := routing["rules"].([]any)
		rule := map[string]any{
			"type":        "field",
			"inboundTag":  []string{newInbound.ID},
			"outboundTag": outboundTag,
		}
		routing["rules"] = append(rules, rule)
		cfg["routing"] = routing

		newInbound.ChainToTag = outboundTag
		// Build a vless URI string for the next hop so UI/debug paths
		// have something readable; the orchestrator can verify the URI
		// matches what it intended.
		newInbound.ChainToURI = nextHopURI(req.ChainTo)
	}

	newCfgBytes, _ := json.MarshalIndent(cfg, "", "  ")
	if err := AtomicWrite(XrayConfigPath, newCfgBytes, 0o600); err != nil {
		return nil, fmt.Errorf("write config: %w", err)
	}

	// Open firewall for the new port (idempotent — common.go skips dups).
	_ = openFirewallPort(ctx, newInbound.Port)

	// revert restores the pre-append config and restarts xray, so a failed
	// add leaves the server in its previous working state instead of a
	// crash-loop (improvement #3).
	revert := func() {
		_ = AtomicWrite(XrayConfigPath, cfgBytes, 0o600)
		_, _ = exec.CommandContext(ctx, "systemctl", "restart", "xray").CombinedOutput()
	}

	// xray doesn't have a SIGHUP reload; restart is fastest.
	// Brief downtime (~1s) — acceptable for an add operation. Profile
	// removal goes through the same path.
	if _, err := exec.CommandContext(ctx, "systemctl", "restart", "xray").CombinedOutput(); err != nil {
		revert()
		return nil, fmt.Errorf("systemctl restart xray: %w", err)
	}
	// Healthcheck the new inbound — xray must be active AND listening on
	// the new port. On failure, revert so we don't leave xray broken.
	if err := waitXrayHealthy(ctx, newInbound.Port); err != nil {
		revert()
		return nil, fmt.Errorf("new inbound failed healthcheck, reverted to previous config: %w", err)
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

// buildVlessOutbound assembles an xray outbound config that connects to
// the given chain target. Reality fields are honored when ServerName +
// PublicKey are both set; otherwise we emit a plain VLESS-TCP outbound.
func buildVlessOutbound(t *ChainTarget, tag string) (map[string]any, error) {
	if t.Host == "" || t.Port == 0 || t.UUID == "" {
		return nil, fmt.Errorf("chain_to.host/port/uuid are required")
	}
	user := map[string]any{
		"id":         t.UUID,
		"encryption": "none",
	}
	if t.Flow != "" {
		user["flow"] = t.Flow
	}
	stream := map[string]any{
		"network": "tcp",
	}
	if t.ServerName != "" && t.PublicKey != "" {
		stream["security"] = "reality"
		stream["realitySettings"] = map[string]any{
			"serverName": t.ServerName,
			"publicKey":  t.PublicKey,
			"shortId":    t.ShortID,
			"fingerprint": "chrome",
		}
	} else {
		stream["security"] = "none"
	}
	return map[string]any{
		"tag":      tag,
		"protocol": "vless",
		"settings": map[string]any{
			"vnext": []any{
				map[string]any{
					"address": t.Host,
					"port":    t.Port,
					"users":   []any{user},
				},
			},
		},
		"streamSettings": stream,
	}, nil
}

// nextHopURI produces a debugging-grade vless URI for a chain target.
// Mirror of buildVlessRealityURI / buildVlessURI but without label and
// without the placeholder-host rewrite — the orchestrator already knows
// the canonical URI; we just want something readable in the DB.
func nextHopURI(t *ChainTarget) string {
	if t.ServerName != "" && t.PublicKey != "" {
		return fmt.Sprintf(
			"vless://%s@%s:%d?security=reality&sni=%s&pbk=%s&sid=%s&fp=chrome&flow=%s&type=tcp",
			t.UUID, t.Host, t.Port, t.ServerName, t.PublicKey, t.ShortID, t.Flow,
		)
	}
	return fmt.Sprintf("vless://%s@%s:%d?type=tcp", t.UUID, t.Host, t.Port)
}

// XrayDeleteProfile removes the inbound with the given inbound_id from
// xray config, restarts xray, drops the DB row.
//
// If this inbound was a chain hop, we also remove its dedicated VLESS
// outbound (tag = "chain-<inbound_id>") and the routing rule that
// glued them together. Done together so a half-deleted chain doesn't
// leave orphan outbounds tightening xray's startup time.
func XrayDeleteProfile(db *storage.DB, inboundID string) error {
	xrayConfigMu.Lock()
	defer xrayConfigMu.Unlock()
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

	// Chain teardown: drop the matching outbound (if any) and any
	// routing rules that referenced this inbound. The "chain-" prefix
	// is a convention we control (set in XrayAddProfile) so we can
	// derive the outbound tag without a DB lookup.
	chainTag := "chain-" + inboundID
	outbounds, _ := cfg["outbounds"].([]any)
	if len(outbounds) > 0 {
		filtered := outbounds[:0]
		for _, raw := range outbounds {
			m, ok := raw.(map[string]any)
			if ok && m["tag"] == chainTag {
				continue
			}
			filtered = append(filtered, raw)
		}
		cfg["outbounds"] = filtered
	}
	if routing, ok := cfg["routing"].(map[string]any); ok {
		if rules, ok := routing["rules"].([]any); ok {
			keptRules := rules[:0]
			for _, raw := range rules {
				m, ok := raw.(map[string]any)
				if !ok {
					keptRules = append(keptRules, raw)
					continue
				}
				// Drop the rule iff it targets either this inbound's
				// tag or this inbound's chain outbound tag.
				if m["outboundTag"] == chainTag {
					continue
				}
				if tags, ok := m["inboundTag"].([]any); ok && len(tags) == 1 && tags[0] == inboundID {
					continue
				}
				keptRules = append(keptRules, raw)
			}
			routing["rules"] = keptRules
			cfg["routing"] = routing
		}
	}

	newCfgBytes, _ := json.MarshalIndent(cfg, "", "  ")
	if err := AtomicWrite(XrayConfigPath, newCfgBytes, 0o600); err != nil {
		return fmt.Errorf("write config: %w", err)
	}
	ctx := context.Background()
	// revert restores the pre-delete config + restarts xray, so a delete
	// that produces an invalid config (e.g. a dangling rule) doesn't leave
	// xray crash-looping and drop the DB row out of sync.
	revert := func() {
		_ = AtomicWrite(XrayConfigPath, cfgBytes, 0o600)
		_, _ = exec.CommandContext(ctx, "systemctl", "restart", "xray").CombinedOutput()
	}
	if _, err := exec.CommandContext(ctx, "systemctl", "restart", "xray").CombinedOutput(); err != nil {
		revert()
		return fmt.Errorf("systemctl restart xray: %w", err)
	}
	// xray must still be active after the delete (port 0 = is-active only).
	if err := waitXrayHealthy(ctx, 0); err != nil {
		revert()
		return fmt.Errorf("xray unhealthy after delete, reverted: %w", err)
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
