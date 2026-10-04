package deploy

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWBStateUploadNeverFollowsExistingTemporarySymlink(t *testing.T) {
	dir := t.TempDir()
	outside := filepath.Join(t.TempDir(), "untouched")
	if err := os.WriteFile(outside, []byte("keep"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(dir, ".session-upload")); err != nil {
		t.Fatal(err)
	}
	if err := writeWBState(dir, []byte("private state"), os.Getuid(), os.Getgid()); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(outside)
	if string(data) != "keep" {
		t.Fatal("followed temporary symlink")
	}
	info, err := os.Stat(filepath.Join(dir, "state.json"))
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("state is not private")
	}
}

func TestWBOwnerStateRejectsGuestAndDropsForeignCookies(t *testing.T) {
	session := &WBHostSession{DeviceID: "test-device", AuthSlice: `{"accessToken":"test-bearer"}`, Cookies: map[string]string{"wbx-refresh": "test-refresh", "unrelated": "must-not-export", "x_wbaas_token": "old-ip"}}
	raw, err := wbHostState(session)
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{"must-not-export", "old-ip", "http://"} {
		if strings.Contains(string(raw), forbidden) {
			t.Fatal("exported unrelated login state")
		}
	}
	session.Cookies["wbx-refresh"] = ""
	if _, err = wbHostState(session); err == nil {
		t.Fatal("guest accepted")
	}
	session.Cookies["wbx-refresh"] = "secret\ninjection"
	if _, err = wbHostState(session); err == nil || strings.Contains(err.Error(), "secret") {
		t.Fatal("invalid cookie accepted or logged")
	}
}

func TestWBOwnerUnitUsesSameRoomAndPrivatePersistentState(t *testing.T) {
	room := "https://stream.wb.ru/room/existing-room"
	unit, err := renderWBHostUnit(room)
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{room, "Restart=always", "StartLimitIntervalSec=0", "User=netguard-wbhost", "StateDirectoryMode=0700", "ProtectSystem=strict", "KillMode=control-group", "WantedBy=multi-user.target"} {
		if !strings.Contains(unit, required) {
			t.Fatal("missing", required)
		}
	}
	if strings.Contains(unit, "wbx-refresh") {
		t.Fatal("credentials in unit")
	}
	if strings.Contains(unit, "python") || strings.Contains(unit, "PLAYWRIGHT") || !strings.Contains(unit, "-owner-state ") {
		t.Fatal("owner still depends on browser runtime")
	}
	if _, err := renderWBHostUnit("https://evil.test/room/x"); err == nil {
		t.Fatal("foreign room accepted")
	}
}
