package deploy

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

//go:embed wbhost/host.py
var wbHostScript []byte

const wbHostRoot = "/opt/netguard-wbhost"
const wbHostData = "/var/lib/netguard-wbstream"
const wbHostUser = "netguard-wbhost"

// Credentials are accepted only over the authenticated agent API. They are
// never task results, arguments, environment variables, or diagnostic logs.
type WBHostSession struct {
	Cookies   map[string]string `json:"cookies"`
	DeviceID  string            `json:"device_id"`
	AuthSlice string            `json:"auth_slice"`
}

func wbHostState(s *WBHostSession) ([]byte, error) {
	bad := fmt.Errorf("WB owner login is missing or invalid; sign in again")
	if s == nil || len(s.DeviceID) == 0 || len(s.DeviceID) > 256 || len(s.AuthSlice) > 16384 {
		return nil, bad
	}
	var slice map[string]any
	if json.Unmarshal([]byte(s.AuthSlice), &slice) != nil {
		return nil, bad
	}
	if token, ok := slice["accessToken"].(string); !ok || len(token) == 0 {
		return nil, bad
	}
	if s.Cookies["wbx-refresh"] == "" {
		return nil, bad
	}
	cookies := []map[string]any{}
	total := 0
	for _, name := range []string{"wbx-refresh", "_wbauid", "wbx-validation-key"} {
		value := s.Cookies[name]
		if value == "" {
			continue
		}
		total += len(value)
		if total > 16384 || strings.ContainsAny(value, "\r\n\x00;") {
			return nil, bad
		}
		cookies = append(cookies, map[string]any{"name": name, "value": value, "domain": ".wb.ru", "path": "/", "expires": -1, "httpOnly": true, "secure": true, "sameSite": "None"})
	}
	// Do not copy x_wbaas_token: it is tied to the phone's IP. Let WB mint one
	// for this server. Only WB's own origin receives the supplied localStorage.
	return json.Marshal(map[string]any{"cookies": cookies, "origins": []any{map[string]any{
		"origin": "https://stream.wb.ru", "localStorage": []any{
			map[string]string{"name": "wb_auth_api_device_id", "value": s.DeviceID},
			map[string]string{"name": "wb_auth_auth_slice", "value": s.AuthSlice},
		},
	}}})
}

func wbHostDir(key string) string  { return filepath.Join(wbHostData, key) }
func wbHostUnit(key string) string { return wbUnit(key + "-host") }

func renderWBHostUnit(room string) (string, error) {
	room, err := normalizeWBRoom(room)
	if err != nil {
		return "", err
	}
	key := wbKey(room)
	return fmt.Sprintf(`[Unit]
Description=NetGuard WB Stream automatic room owner
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0
[Service]
Type=simple
User=netguard-wbhost
Group=netguard-wbhost
StateDirectory=netguard-wbstream/%s
StateDirectoryMode=0700
Environment=NETGUARD_CARRIER=wbstream
Environment=WLB_VALID_VP8_TUNNEL=1
Environment=WLB_CARRIER_PCARQ=1
UnsetEnvironment=WLB_CARRIER_ARQ
ExecStart=/opt/netguard-wbhost/owner -room %s -owner-state %s/state.json -owner-status %s/status.json
Restart=always
RestartSec=20
TimeoutStopSec=20
KillMode=control-group
UMask=0077
MemoryMax=256M
TasksMax=256
CPUQuota=100%%
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectHome=true
ProtectSystem=strict
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
[Install]
WantedBy=multi-user.target
`, key, room, wbHostDir(key), wbHostDir(key)), nil
}

// The embedded native owner uses WB's authenticated API and needs no browser,
// package manager, runtime downloads or interactive server login.
func installWBHost(ctx context.Context) error {
	if _, err := user.Lookup(wbHostUser); err != nil {
		if exec.CommandContext(ctx, "useradd", "--system", "--no-create-home", "--shell", "/usr/sbin/nologin", wbHostUser).Run() != nil {
			return fmt.Errorf("cannot prepare WB owner user")
		}
	}
	if err := os.MkdirAll(wbHostRoot, 0755); err != nil {
		return err
	}
	return AtomicWrite(wbHostRoot+"/owner", headlessTelemostCreator, 0755)
}

func enableWBHost(ctx context.Context, room string, state []byte) error {
	if err := installWBHost(ctx); err != nil {
		return err
	}
	key := wbKey(room)
	dir := wbHostDir(key)
	u, err := user.Lookup(wbHostUser)
	if err != nil {
		return fmt.Errorf("WB owner user missing")
	}
	uid, _ := strconv.Atoi(u.Uid)
	gid, _ := strconv.Atoi(u.Gid)
	if err = os.MkdirAll(wbHostData, 0755); err != nil {
		return err
	}
	if info, statErr := os.Lstat(wbHostData); statErr != nil || !info.IsDir() {
		return fmt.Errorf("invalid WB state root")
	}
	if err = os.Chown(wbHostData, 0, 0); err != nil {
		return err
	}
	if err = os.Chmod(wbHostData, 0755); err != nil {
		return err
	}
	if err = os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	if info, statErr := os.Lstat(dir); statErr != nil || !info.IsDir() {
		return fmt.Errorf("invalid WB room state directory")
	}
	if err = os.Chown(dir, uid, gid); err != nil {
		return err
	}
	// Stop before replacing state: an old browser must not overwrite a fresh login.
	if fileExists("/etc/systemd/system/" + wbHostUnit(key)) {
		if exec.CommandContext(ctx, "systemctl", "stop", wbHostUnit(key)).Run() != nil {
			return fmt.Errorf("cannot stop previous WB owner")
		}
	}
	if err = writeWBState(dir, state, uid, gid); err != nil {
		return err
	}
	_ = os.Remove(filepath.Join(dir, "status.json"))
	unit, _ := renderWBHostUnit(room)
	if err = AtomicWrite("/etc/systemd/system/"+wbHostUnit(key), []byte(unit), 0644); err != nil {
		return err
	}
	if exec.CommandContext(ctx, "systemctl", "daemon-reload").Run() != nil {
		return fmt.Errorf("cannot reload WB owner service")
	}
	if exec.CommandContext(ctx, "systemctl", "enable", "--now", wbHostUnit(key)).Run() != nil {
		return fmt.Errorf("cannot start WB owner service")
	}
	return nil
}

// Unlike generic root-owned configuration, this directory is writable by the
// browser user. Exclusive creation and descriptor-based chown prevent following
// a pre-existing temporary-file symlink with the agent's root privileges.
func writeWBState(dir string, state []byte, uid, gid int) error {
	tmp := filepath.Join(dir, ".session-upload")
	if err := os.Remove(tmp); err != nil && !os.IsNotExist(err) {
		return err
	}
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer os.Remove(tmp)
	defer f.Close()
	if _, err = f.Write(state); err != nil {
		return err
	}
	if err = f.Sync(); err != nil {
		return err
	}
	if err = f.Chown(uid, gid); err != nil {
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(dir, "state.json"))
}

func wbHostStatus(ctx context.Context, key string) string {
	if !fileExists(filepath.Join(wbHostDir(key), "state.json")) {
		return "not_configured"
	}
	if !SystemctlIsActive(ctx, wbHostUnit(key)) {
		return "stopped"
	}
	var status struct {
		State   string `json:"state"`
		Updated int64  `json:"updated"`
	}
	data, _ := os.ReadFile(filepath.Join(wbHostDir(key), "status.json"))
	if json.Unmarshal(data, &status) != nil || time.Now().Unix()-status.Updated > 360 {
		return "starting"
	}
	switch status.State {
	case "hosting", "checking", "reconnecting", "needs_login", "wb_blocked":
		return status.State
	}
	return "starting"
}
