package deploy

import (
	"bytes"
	"context"
	"crypto/sha256"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

const wbDir = "/etc/netguard-wbstream"
const wbBinary = "/usr/local/bin/netguard-wbstream-creator"

var wbLock sync.Mutex
var wbRoomID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

type WBDeployRequest struct {
	Room         string         `json:"room"`
	Update       bool           `json:"update"`
	OwnerSession *WBHostSession `json:"owner_session,omitempty"`
}

// IDs never reach a shell. Reject encoded separators, authorities and query data.
func normalizeWBRoom(input string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(input))
	if err != nil || u.Scheme != "https" || !strings.EqualFold(u.Hostname(), "stream.wb.ru") || u.User != nil || (u.Port() != "" && u.Port() != "443") {
		return "", fmt.Errorf("expected an HTTPS stream.wb.ru room link")
	}
	path := strings.TrimSuffix(u.EscapedPath(), "/")
	if !strings.HasPrefix(path, "/room/") {
		return "", fmt.Errorf("missing room ID")
	}
	id := strings.TrimPrefix(path, "/room/")
	if !wbRoomID.MatchString(id) {
		return "", fmt.Errorf("invalid room ID")
	}
	return "https://stream.wb.ru/room/" + id, nil
}

func wbKey(room string) string  { return fmt.Sprintf("%x", sha256.Sum256([]byte(room)))[:24] }
func wbUnit(key string) string  { return "netguard-wbstream-" + key + ".service" }
func wbReady(key string) string { return "/run/netguard-wbstream-" + key + "/ready" }

func renderWBUnit(room string) (string, error) {
	normalized, err := normalizeWBRoom(room)
	if err != nil {
		return "", err
	}
	key := wbKey(normalized)
	id := strings.TrimPrefix(normalized, "https://stream.wb.ru/room/")
	return fmt.Sprintf(`[Unit]
Description=NetGuard WB Stream room
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0
[Service]
Type=simple
User=wlb
Group=wlb
RuntimeDirectory=netguard-wbstream-%s
RuntimeDirectoryMode=0700
Environment=NETGUARD_CARRIER=wbstream
Environment=WLB_VALID_VP8_TUNNEL=1
Environment=WLB_CARRIER_PCARQ=1
Environment=WLB_CARRIER_KBPS=10000
Environment=WLB_PC_RATE_MODE=probe
Environment=WLB_PC_FPS=96
Environment=WLB_PC_CHUNK=4096
UnsetEnvironment=WLB_CARRIER_ARQ
ExecStart=%s -guest -room %s -name NetGuard -resources moderate -ready-file %s
Restart=on-failure
RestartSec=10
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
`, key, wbBinary, id, wbReady(key)), nil
}

// A process merely being alive is insufficient: the creator writes the marker
// only after publisher ICE connects to WB. systemd removes it on restart/stop.
func wbPublisherReady(ctx context.Context, key string) bool {
	return fileExists(wbReady(key)) && SystemctlIsActive(ctx, wbUnit(key))
}

func DeployWBStream(req *WBDeployRequest) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		wbLock.Lock()
		defer wbLock.Unlock()
		room, err := normalizeWBRoom(req.Room)
		if err != nil {
			return h.Fail("E_BAD_REQUEST", err.Error(), false)
		}
		key := wbKey(room)
		if wbPublisherReady(ctx, key) && !req.Update && req.OwnerSession == nil {
			return h.Ok(map[string]any{"rooms": []string{room}, "publisher_ready": true})
		}
		ctx, cancel := context.WithTimeout(ctx, 12*time.Minute)
		defer cancel()
		var ownerState []byte
		if req.OwnerSession != nil {
			ownerState, err = wbHostState(req.OwnerSession)
			req.OwnerSession = nil
			if err != nil {
				return h.Fail("E_WB_LOGIN", err.Error(), false)
			}
		}
		h.SetStep("wb_install", 10)
		if err = ensureWlbUser(ctx); err != nil {
			return h.Fail("E_WB_INSTALL", "Cannot prepare WB service user", true)
		}
		if err = os.MkdirAll(wbDir, 0700); err != nil {
			return h.Fail("E_WB_INSTALL", err.Error(), true)
		}
		entries, _ := filepath.Glob(filepath.Join(wbDir, "*.room"))
		config := filepath.Join(wbDir, key+".room")
		existed := fileExists(config)
		if !existed && len(entries) >= 12 {
			return h.Fail("E_WB_LIMIT", "Maximum 12 WB rooms per server", false)
		}
		if len(ownerState) > 0 {
			h.SetStep("wb_owner_install", 15)
			// Keep this room registered if joining later times out. The owner
			// continues recovery and can be managed/deleted from the app.
			if err = AtomicWrite(config, []byte(room+"\n"), 0600); err != nil {
				return h.Fail("E_WB_INSTALL", err.Error(), true)
			}
			existed = true
			if err = enableWBHost(ctx, room, ownerState); err != nil {
				return h.Fail("E_WB_OWNER", err.Error(), true)
			}
		}
		ctx, joinCancel := context.WithTimeout(ctx, 100*time.Second)
		defer joinCancel()
		if req.Update && len(ownerState) == 0 {
			// Updating only the publisher leaves a running legacy owner able to
			// evict active peers. Upgrade it without replacing the saved login.
			if err = upgradeRunningWBHost(ctx, room); err != nil {
				return h.Fail("E_WB_OWNER_UPDATE", err.Error(), true)
			}
		}
		previousBinary, _ := os.ReadFile(wbBinary)
		if err = AtomicWrite(wbBinary, headlessTelemostCreator, 0755); err != nil {
			return h.Fail("E_WB_INSTALL", err.Error(), true)
		}
		unitText, _ := renderWBUnit(room)
		unitPath := "/etc/systemd/system/" + wbUnit(key)
		if err = AtomicWrite(unitPath, []byte(unitText), 0644); err != nil {
			return h.Fail("E_WB_INSTALL", err.Error(), true)
		}
		if err = AtomicWrite(config, []byte(room+"\n"), 0600); err != nil {
			return h.Fail("E_WB_INSTALL", err.Error(), true)
		}
		// Failed first deploys must not leave restart loops or a misleading saved room.
		committed := false
		defer func() {
			if !committed && !existed {
				cleanup, stop := context.WithTimeout(context.Background(), 15*time.Second)
				defer stop()
				_ = exec.CommandContext(cleanup, "systemctl", "disable", "--now", wbUnit(key)).Run()
				_ = os.Remove(unitPath)
				_ = os.Remove(config)
				_ = exec.CommandContext(cleanup, "systemctl", "daemon-reload").Run()
			}
		}()
		if _, err = Run(ctx, "systemctl", "daemon-reload"); err != nil {
			return h.Fail("E_WB_INSTALL", err.Error(), true)
		}
		// Re-authenticating the owner must not restart a healthy, unchanged publisher.
		// A dead publisher needs no keeper: it must start even while owner reconnects.
		upgradingPublisher := req.Update && existed && wbPublisherReady(ctx, key) && !bytes.Equal(previousBinary, headlessTelemostCreator)
		if upgradingPublisher {
			h.SetStep("wb_update", 35)
			// An extra guest keeps the meeting alive while the creator restarts.
			// Do not stop the working creator unless that guest has connected.
			cleanup, keepErr := startWBKeeper(ctx, room)
			if keepErr != nil {
				return h.Fail("E_WB_UPDATE", "Could not keep the meeting open; the existing WB process was not restarted", true)
			}
			defer cleanup()
			// Restore the previous executable on a failed update while the extra
			// guest still holds the room. Already-running rooms are unaffected.
			defer func() {
				if !committed && len(previousBinary) > 0 {
					rollback, cancel := context.WithTimeout(context.Background(), 30*time.Second)
					defer cancel()
					if AtomicWrite(wbBinary, previousBinary, 0755) == nil {
						_, _ = Run(rollback, "systemctl", "restart", wbUnit(key))
						_ = waitWBReady(rollback, key)
					}
				}
			}()
			if _, err = Run(ctx, "systemctl", "restart", wbUnit(key)); err != nil {
				return h.Fail("E_WB_UPDATE", err.Error(), true)
			}
		}
		h.SetStep("wb_join", 45)
		if _, err = Run(ctx, "systemctl", "enable", "--now", wbUnit(key)); err != nil {
			return h.Fail("E_WB_START", err.Error(), true)
		}
		hasOwner := len(ownerState) > 0 || fileExists(filepath.Join(wbHostDir(key), "state.json"))
		if hasOwner && !upgradingPublisher {
			// Both persistent services are installed. Their recovery is independent
			// of this finite HTTP task and the phone/WebView lifetime.
			committed = true
			return h.Ok(map[string]any{"rooms": []string{room}, "publisher_ready": wbPublisherReady(ctx, key), "recovery_enabled": true, "owner_state": wbHostStatus(ctx, key)})
		}
		h.SetStep("wb_wait", 65)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			if wbPublisherReady(ctx, key) {
				committed = true
				return h.Ok(map[string]any{"rooms": []string{room}, "publisher_ready": true, "recovery_enabled": hasOwner})
			}
			select {
			case <-ctx.Done():
				return h.Fail("E_WB_CONNECT", "WB did not connect. Keep the meeting open; check that guests can join, then retry or create a new room.", true)
			case <-ticker.C:
			}
		}
	}
}

func waitWBReady(ctx context.Context, key string) error {
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		if wbPublisherReady(ctx, key) {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-tick.C:
		}
	}
}

func renderWBKeeper(room string) (string, error) {
	unit, err := renderWBUnit(room)
	if err != nil {
		return "", err
	}
	key := wbKey(room)
	unit = strings.ReplaceAll(unit, "netguard-wbstream-"+key, "netguard-wbstream-"+key+"-keeper")
	unit = strings.Replace(unit, " -guest -room ", " -hold-room -guest -room ", 1)
	unit = strings.Replace(unit, "Restart=on-failure", "RuntimeMaxSec=180\nRestart=no", 1)
	return unit, nil
}

func startWBKeeper(ctx context.Context, room string) (func(), error) {
	key := wbKey(room) + "-keeper"
	path := "/etc/systemd/system/" + wbUnit(key)
	cleanup := func() {
		c, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		_, _ = Run(c, "systemctl", "stop", wbUnit(key))
		_ = os.Remove(path)
		_, _ = Run(c, "systemctl", "daemon-reload")
	}
	unit, err := renderWBKeeper(room)
	if err != nil {
		return nil, err
	}
	if err = AtomicWrite(path, []byte(unit), 0644); err != nil {
		return nil, err
	}
	if _, err = Run(ctx, "systemctl", "daemon-reload"); err == nil {
		_, err = Run(ctx, "systemctl", "start", wbUnit(key))
	}
	if err == nil {
		c, cancel := context.WithTimeout(ctx, 40*time.Second)
		err = waitWBReady(c, key)
		cancel()
	}
	if err != nil {
		cleanup()
		return nil, err
	}
	return cleanup, nil
}

func WBStreamRooms(ctx context.Context) TelemostRoomsResult {
	result := TelemostRoomsResult{Instances: []TelemostInstanceStatus{}}
	files, _ := filepath.Glob(filepath.Join(wbDir, "*.room"))
	for _, f := range files {
		data, err := os.ReadFile(f)
		if err != nil {
			continue
		}
		room, err := normalizeWBRoom(string(data))
		if err != nil {
			continue
		}
		ready := wbPublisherReady(ctx, wbKey(room))
		result.Installed++
		if ready {
			result.ActiveCount++
		}
		result.Instances = append(result.Instances, TelemostInstanceStatus{Index: result.Installed, Room: room, Active: ready, OwnerState: wbHostStatus(ctx, wbKey(room))})
	}
	result.Deployed = result.Installed > 0
	return result
}
