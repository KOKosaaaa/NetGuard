package deploy

import (
	"errors"
	"strings"
	"testing"
)

func TestWBRemovalStopsBothServicesBeforeRemovingFiles(t *testing.T) {
	room := "https://stream.wb.ru/room/remove-test"
	var events []string
	ops := wbRemovalOps{stop: func(s string) error { events = append(events, "stop "+s); return nil }, remove: func(s string) error { events = append(events, "remove "+s); return nil }, reload: func() error { events = append(events, "reload"); return nil }}
	for i := 0; i < 2; i++ {
		if err := removeWBRoom(room, ops); err != nil {
			t.Fatal(err)
		}
	}
	if len(events) != 28 {
		t.Fatalf("unexpected cleanup: %v", events)
	}
	if !strings.HasPrefix(events[0], "stop ") || !strings.Contains(events[1], "-keeper.service") || !strings.Contains(events[2], "-host.service") || events[6] != "reload" || !strings.HasSuffix(events[13], ".room") {
		t.Fatal(events)
	}
	for _, e := range events {
		if strings.Contains(e, "telemost") || strings.Contains(e, wbBinary) {
			t.Fatalf("removed shared/unrelated service: %s", e)
		}
	}
}

func TestWBRemovalStopFailureKeepsConfigurationForRetry(t *testing.T) {
	removed := false
	ops := wbRemovalOps{stop: func(string) error { return errors.New("stop failed") }, remove: func(string) error { removed = true; return nil }, reload: func() error { return nil }}
	if err := removeWBRoom("https://stream.wb.ru/room/test", ops); err == nil || removed {
		t.Fatal("lost configuration of a running room")
	}
	if err := removeWBRoom("https://evil.test/room/test", ops); err == nil {
		t.Fatal("accepted foreign room")
	}
}
