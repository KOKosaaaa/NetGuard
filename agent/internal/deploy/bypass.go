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
	Kind           string `json:"kind"`
	Value          string `json:"value"`
	Action         string `json:"action"`             // direct | block | via
	ViaOutboundTag string `json:"via_outbound_tag,omitempty"` // required when Action="via"
	Order          int    `json:"order"`
}

// AddBypassOutboundRequest — POST /v1/bypass/outbounds.
type AddBypassOutboundRequest struct {
	Tag      string `json:"tag"`
	Type     string `json:"type"` // socks | http
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Username string `json:"username,omitempty"`
	Password string `json:"password,omitempty"`
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
	case "via":
		if r.ViaOutboundTag == "" {
			return fmt.Errorf("action=via requires via_outbound_tag")
		}
	default:
		return fmt.Errorf("action must be direct|block|via (got %q)", r.Action)
	}
	if r.Value == "" {
		return fmt.Errorf("value required")
	}
	return nil
}

func validateOutbound(o *AddBypassOutboundRequest) error {
	if o.Tag == "" {
		return fmt.Errorf("tag required")
	}
	// Reserved outbound tags clash with our managed ones below.
	switch o.Tag {
	case OutboundFreedomTag, OutboundDirectTag, OutboundBlackholeTag:
		return fmt.Errorf("tag %q is reserved", o.Tag)
	}
	switch o.Type {
	case "socks", "http":
	default:
		return fmt.Errorf("type must be socks|http (got %q)", o.Type)
	}
	if o.Host == "" {
		return fmt.Errorf("host required")
	}
	if o.Port <= 0 || o.Port > 65535 {
		return fmt.Errorf("port out of range")
	}
	return nil
}

// AddBypassRule inserts one rule and re-applies the routing config to a
// running xray (if any). Returns the created row.
func AddBypassRule(db *storage.DB, req *AddBypassRuleRequest) (*storage.BypassRule, error) {
	if err := validateRule(req); err != nil {
		return nil, err
	}
	// When action=via, sanity-check the referenced upstream exists so
	// the user gets a clear error instead of a silently-broken xray.
	if req.Action == "via" {
		outs, _ := db.ListBypassOutbounds()
		ok := false
		for _, o := range outs {
			if o.Tag == req.ViaOutboundTag {
				ok = true
				break
			}
		}
		if !ok {
			return nil, fmt.Errorf("upstream %q not found", req.ViaOutboundTag)
		}
	}
	r := &storage.BypassRule{
		ID:             newBypassID(),
		Kind:           req.Kind,
		Value:          req.Value,
		Action:         req.Action,
		ViaOutboundTag: req.ViaOutboundTag,
		Order:          req.Order,
		CreatedAt:      time.Now(),
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
			ID:             newBypassID(),
			Kind:           r.Kind,
			Value:          r.Value,
			Action:         r.Action,
			ViaOutboundTag: r.ViaOutboundTag,
			Order:          r.Order,
			CreatedAt:      now,
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
	// Same lock as XrayAddProfile/XrayDeleteProfile — serialize config
	// rewrites. Callers (AddBypassRule, XrayDeploy's final step, etc.) do
	// NOT hold this lock, so taking it here is not reentrant.
	xrayConfigMu.Lock()
	defer xrayConfigMu.Unlock()
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
	// Append user-defined upstream proxies (typical use: an RF VPS
	// SOCKS5 so a few RF-only domains exit via a Russian IP). xray
	// outbound shape for socks/http is identical save the protocol.
	userOuts, err := db.ListBypassOutbounds()
	if err != nil {
		return nil, err
	}
	for _, o := range userOuts {
		server := map[string]any{"address": o.Host, "port": o.Port}
		if o.Username != "" {
			if o.Type == "socks" {
				server["users"] = []any{
					map[string]any{"user": o.Username, "pass": o.Password},
				}
			} else { // http
				server["users"] = []any{
					map[string]any{"user": o.Username, "pass": o.Password},
				}
			}
		}
		outbounds = append(outbounds, map[string]any{
			"tag":      o.Tag,
			"protocol": o.Type,
			"settings": map[string]any{
				"servers": []any{server},
			},
		})
	}

	// Build routing.rules from the bypass table. xray takes the first
	// matching rule, so DB ord is preserved on serialization.
	xrRules := make([]any, 0, len(rules))
	for _, r := range rules {
		outboundTag := OutboundDirectTag
		switch r.Action {
		case "block":
			outboundTag = OutboundBlackholeTag
		case "via":
			outboundTag = r.ViaOutboundTag
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

func newOutboundID() string {
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	return "bo-" + hex.EncodeToString(b)
}

// AddBypassOutbound inserts a user-defined upstream proxy and re-applies
// the xray config (so the new outbound shows up under "outbounds" and
// is reachable by tag from any rule).
func AddBypassOutbound(db *storage.DB, req *AddBypassOutboundRequest) (*storage.BypassOutbound, error) {
	if err := validateOutbound(req); err != nil {
		return nil, err
	}
	o := &storage.BypassOutbound{
		ID:        newOutboundID(),
		Tag:       req.Tag,
		Type:      req.Type,
		Host:      req.Host,
		Port:      req.Port,
		Username:  req.Username,
		Password:  req.Password,
		CreatedAt: time.Now(),
	}
	if err := db.InsertBypassOutbound(o); err != nil {
		return nil, err
	}
	if err := ApplyBypassToXray(db); err != nil {
		return o, fmt.Errorf("outbound saved but apply to xray failed: %w", err)
	}
	return o, nil
}

// DeleteBypassOutbound removes the upstream by id and re-applies.
// Caller should warn the user when rules still reference this tag —
// xray fails to load if a rule references an unknown outbound.
func DeleteBypassOutbound(db *storage.DB, id string) error {
	if err := db.DeleteBypassOutbound(id); err != nil {
		return err
	}
	return ApplyBypassToXray(db)
}
