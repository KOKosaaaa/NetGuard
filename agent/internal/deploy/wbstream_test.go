package deploy

import (
	"strings"
	"testing"
)

func TestWBRoomValidation(t *testing.T) {
	for _, bad := range []string{"", "http://stream.wb.ru/room/a", "https://stream.wb.ru.evil/room/a", "https://x@stream.wb.ru/room/a", "https://stream.wb.ru:55/room/a", "https://stream.wb.ru/room/../x", "https://stream.wb.ru/room/%2Fa", "https://stream.wb.ru/room/a\nExecStart=evil", "https://stream.wb.ru/room/a;id", "https://stream.wb.ru/room/"} {
		if _, err := normalizeWBRoom(bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
	got, err := normalizeWBRoom("https://STREAM.WB.RU:443/room/test-id/?tracking=yes#name")
	if err != nil || got != "https://stream.wb.ru/room/test-id" {
		t.Fatal(got, err)
	}
}
func TestWBServiceIsIndependentAndUsesGuestPCARQ(t *testing.T) {
	room := "https://stream.wb.ru/room/test-id"
	unit, err := renderWBUnit(room)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(unit, "Environment=WLB_CARRIER_KBPS=10000") {
		t.Fatal("WB publisher cannot negotiate the tested higher pacing ceiling")
	}
	for _, expected := range []string{"User=wlb", "Environment=NETGUARD_CARRIER=wbstream", "Environment=WLB_CARRIER_PCARQ=1", "Environment=WLB_PC_RATE_MODE=probe", "Environment=WLB_PC_FPS=96", "Environment=WLB_PC_CHUNK=4096", "UnsetEnvironment=WLB_CARRIER_ARQ", " -guest -room test-id ", "-ready-file " + wbReady(wbKey(room)), "RuntimeDirectoryMode=0700"} {
		if !strings.Contains(unit, expected) {
			t.Fatal("missing", expected)
		}
	}
	for _, bad := range []string{"/bin/sh", "-cookies", "wlb-telemost@", "Environment=WLB_CARRIER_ARQ=1"} {
		if strings.Contains(unit, bad) {
			t.Fatal("unexpected", bad)
		}
	}
	if wbKey(room) == wbKey(room+"2") {
		t.Fatal("colliding room service")
	}
	if _, err := renderWBUnit("x; rm -rf /"); err == nil {
		t.Fatal("unit accepted unsafe room")
	}
}

func TestWBUpdateKeeperCannotReplaceMainService(t *testing.T) {
	room := "https://stream.wb.ru/room/test-id"
	unit, err := renderWBKeeper(room)
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range []string{" -hold-room -guest -room test-id ", "RuntimeMaxSec=180", "Restart=no", "RuntimeDirectory=netguard-wbstream-" + wbKey(room) + "-keeper", "-ready-file " + wbReady(wbKey(room)+"-keeper")} {
		if !strings.Contains(unit, s) {
			t.Fatal("missing", s)
		}
	}
	if strings.Contains(unit, "Restart=on-failure") {
		t.Fatal("keeper must expire, not restart forever")
	}
}
