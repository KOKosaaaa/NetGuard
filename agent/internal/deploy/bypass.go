// bypass.go — server-side routing rules editor.
//
// The user-facing model is three actions:
//   - proxy  (default, no rule needed)  — traffic exits this server normally
//   - direct (route via "out-direct" freedom outbound)
//   - block  (route via "out-block" blackhole outbound — drops the packet)
//
// We persist rules in the bypass_rules table and re-render xray's
// routing.rules / outbounds on every change, then restart xray. xray
// has no SIGHUP-style hot reload — the restart costs ~1s of downtime
// which is fine for an admin-driven change.
//
// Rule "kind" maps onto xray's RoutingRule field:
//   - domain  → "domain":  ["example.com", ...]
//   - cidr    → "ip":      ["192.0.2.0/24", ...]
//   - geosite → "domain":  ["geosite:cn", ...]
//   - geoip   → "ip":      ["geoip:cn", ...]

package deploy

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os/exec"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
)

const (
	OutboundFreedomTag   = "out-default" // catch-all proxy (= straight to internet)
	OutboundDirectTag    = "out-direct"  // explicit bypass for "direct" rules
	OutboundBlackholeTag = "out-block"   // for "block" rules
)

// AddBypassRuleRequest is the body for POST /v1/bypass/rules.
type AddBypassRuleRequest struct {
	Kind   string `json:"kind"`
	Value  string `json:"value"`
	Action string `json:"action"`
	Order  int    `json:"order"`
}

// PutBypassRulesRequest replaces the whole rule set atomically.
type PutBypassRulesRequest struct {
	Rules []*AddBypassRuleRequest `json:"rules"`
}

func validateRule(r *AddBypassRuleRequest) error {
	switch r.Kind {
	case "domain", "cidr", "geosite", "geoip":
	default:
		return fmt.Errorf("kind must be one of domain|cidr|geosite|geoip (got %q)", r.Kind)
	}
	switch r.Action {
	case "direct", "block":
	default:
		return fmt.Errorf("action must be direct|block (got %q)", r.Action)
	}
	if r.Value == "" {
		return fmt.Errorf("value required")
	}
	return nil
}

// AddBypassRule inserts one rule and re-applies the routing config to a
// running xray (if any). Returns the created row.
func AddBypassRule(db *storage.DB, req *AddBypassRuleRequest) (*storage.BypassRule, error) {
	if err := validateRule(req); err != nil {
		return nil, err
	}
	r := &storage.BypassRule{
		ID:        newBypassID(),
		Kind:      req.Kind,
		Value:     req.Value,
		Action:    req.Action,
		Order:     req.Order,
		CreatedAt: time.Now(),
	}
	if err := db.InsertBypassRule(r); err != nil {
		return nil, err
	}
	if err := ApplyBypassToXray(db); err != nil {
		// Don't fail the API — the rule is persisted, just couldn't be
		// applied to a live xray (probably not deployed yet). The user
		// will see the live state on the next /v1/xray/inbounds.
		return r, fmt.Errorf("rule saved but apply to xray failed: %w", err)
	}
	return r, nil
}

// DeleteBypassRule removes the rule by id and re-applies.
func DeleteBypassRule(db *storage.DB, id string) error {
	if err := db.DeleteBypassRule(id); err != nil {
		return err
	}
	return ApplyBypassToXray(db)
}

// ReplaceBypassRules swaps in a whole new set in one transaction, then
// re-applies. Useful for the Android editor's "Save" button after the
// user reorders / edits a batch.
func ReplaceBypassRules(db *storage.DB, reqs []*AddBypassRuleRequest) ([]*storage.BypassRule, error) {
	for _, r := range reqs {
		if err := validateRule(r); err != nil {
			return nil, err
		}
	}
	now := time.Now()
	rules := make([]*storage.BypassRule, 0, len(reqs))
	for _, r := range reqs {
		rules = append(rules, &storage.BypassRule{
			ID:        newBypassID(),
			Kind:      r.Kind,
			Value:     r.Value,
			Action:    r.Action,
			Order:     r.Order,
			CreatedAt: now,
		})
	}
	if err := db.ReplaceBypassRules(rules); err != nil {
		return nil, err
	}
	if err := ApplyBypassToXray(db); err != nil {
		return rules, fmt.Errorf("rules saved but apply to xray failed: %w", err)
	}
	return rules, nil
}

// ApplyBypassToXray rewrites the routing + outbounds sections of
// /etc/xray/config.json based on the current bypass_rules + xray_inbounds
// tables, then restarts xray. No-ops if xray isn't deployed yet (the
// rules apply on the next deploy via buildXrayConfigFromDB).
func ApplyBypassToXray(db *storage.DB) error {
	if !fileExists(XrayConfigPath) {
		return nil // not deployed yet
	}
	cfg, err := buildXrayConfigFromDB(db)
	if err != nil {
		return fmt.Errorf("build config: %w", err)
	}
	bytes, _ := json.MarshalIndent(cfg, "", "  ")
	if err := AtomicWrite(XrayConfigPath, bytes, 0o600); err != nil {
		return fmt.Errorf("write config: %w", err)
	}
	ctx := context.Background()
	if _, err := exec.CommandContext(ctx, "systemctl", "restart", "xray").CombinedOutput(); err != nil {
		return fmt.Errorf("restart xray: %w", err)
	}
	return nil
}

// buildXrayConfigFromDB reconstructs the entire xray config from the
// current state in SQLite. Used by ApplyBypassToXray and by the deploy
// runner after the bootstrap path so they agree on shape.
func buildXrayConfigFromDB(db *storage.DB) (map[string]any, error) {
	inbounds, err := db.ListXrayInbounds()
	if err != nil {
		return nil, err
	}
	rules, err := db.ListBypassRules()
	if err != nil {
		return nil, err
	}

	inboundsArr := make([]any, 0, len(inbounds))
	for _, in := range inbounds {
		var m map[string]any
		_ = json.Unmarshal([]byte(in.ConfigJSON), &m)
		// Force every inbound to share the same default outbound so
		// rules apply uniformly. xray uses sniffing for domain rules.
		m["sniffing"] = map[string]any{
			"enabled":      true,
			"destOverride": []string{"http", "tls"},
		}
		inboundsArr = append(inboundsArr, m)
	}

	// Outbounds: always include three tagged outlets so rules can reference them.
	outbounds := []any{
		map[string]any{"tag": OutboundFreedomTag, "protocol": "freedom"},
		map[string]any{"tag": OutboundDirectTag, "protocol": "freedom"},
		map[string]any{"tag": OutboundBlackholeTag, "protocol": "blackhole"},
	}

	// Build routing.rules from the bypass table. xray takes the first
	// matching rule, so DB ord is preserved on serialization.
	xrRules := make([]any, 0, len(rules))
	for _, r := range rules {
		outboundTag := OutboundDirectTag
		if r.Action == "block" {
			outboundTag = OutboundBlackholeTag
		}
		entry := map[string]any{
			"type":        "field",
			"outboundTag": outboundTag,
		}
		switch r.Kind {
		case "domain":
			entry["domain"] = []string{r.Value}
		case "geosite":
			entry["domain"] = []string{"geosite:" + r.Value}
		case "cidr":
			entry["ip"] = []string{r.Value}
		case "geoip":
			entry["ip"] = []string{"geoip:" + r.Value}
		}
		xrRules = append(xrRules, entry)
	}

	cfg := map[string]any{
		"log":       map[string]any{"loglevel": "warning"},
		"inbounds":  inboundsArr,
		"outbounds": outbounds,
	}
	if len(xrRules) > 0 {
		cfg["routing"] = map[string]any{
			"domainStrategy": "IPIfNonMatch",
			"rules":          xrRules,
		}
	}
	return cfg, nil
}

func newBypassID() string {
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	return "br-" + hex.EncodeToString(b)
}
