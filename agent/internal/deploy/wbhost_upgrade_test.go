package deploy

import (
	"crypto/sha256"
	"errors"
	"reflect"
	"strings"
	"testing"
)

func TestWBHostUpgradeUsesRunningExecutableAndPreservesState(t *testing.T) {
	room := "https://stream.wb.ru/room/qa-upgrade"
	unit := wbHostUnit(wbKey(room))
	want, old := sha256.Sum256([]byte("new")), sha256.Sum256([]byte("old"))
	var events []string
	pid := 42
	ops := wbHostUpgradeOps{
		managed: func(got string) bool {
			if got != unit {
				t.Fatal("wrong unit", got)
			}
			events = append(events, "managed")
			return true
		},
		pid: func(got string) (int, error) {
			if got != unit {
				t.Fatal("wrong unit", got)
			}
			events = append(events, "pid")
			return pid, nil
		},
		exe: func(got int) (string, error) {
			if got != pid {
				t.Fatal("wrong PID", got)
			}
			events = append(events, "exe")
			return wbHostRoot + "/owner (deleted)", nil
		},
		digest: func(got int) ([32]byte, error) {
			events = append(events, "digest")
			if got == 42 {
				return old, nil
			}
			return want, nil
		},
		write: func() error { events = append(events, "atomic-write"); return nil },
		tryRestart: func(got string) error {
			if got != unit {
				t.Fatal("wrong restart unit", got)
			}
			events = append(events, "try-restart")
			pid = 43
			return nil
		},
	}
	if err := upgradeRunningWBHostWithOps(room, want, ops); err != nil {
		t.Fatal(err)
	}
	expected := []string{"managed", "pid", "exe", "digest", "pid", "atomic-write", "try-restart", "pid", "exe", "digest"}
	if !reflect.DeepEqual(events, expected) {
		t.Fatalf("unsafe update order: %v", events)
	}
	// Same running bytes need neither another write nor reconnect. No operation
	// exposes a state.json path or reads any owner credential file.
	events = nil
	if err := upgradeRunningWBHostWithOps(room, want, ops); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(events, []string{"managed", "pid", "exe", "digest"}) {
		t.Fatal("unchanged owner restarted", events)
	}
}

func TestWBHostUpgradeSkipsStoppedAndUnmanagedRooms(t *testing.T) {
	for _, managed := range []bool{false, true} {
		calls := 0
		ops := wbHostUpgradeOps{
			managed:    func(string) bool { return managed },
			pid:        func(string) (int, error) { calls++; return 0, nil },
			exe:        func(int) (string, error) { t.Fatal("stopped owner inspected"); return "", nil },
			write:      func() error { t.Fatal("stopped owner binary replaced"); return nil },
			tryRestart: func(string) error { t.Fatal("stopped owner enabled"); return nil },
		}
		if err := upgradeRunningWBHostWithOps("https://stream.wb.ru/room/qa", [32]byte{}, ops); err != nil {
			t.Fatal(err)
		}
		if !managed && calls != 0 {
			t.Fatal("unmanaged service inspected")
		}
	}
}

func TestWBHostUpgradeFailuresNeverProceedPastUnsafeStep(t *testing.T) {
	for _, scenario := range []string{"bad-room", "pid-error", "foreign-exe", "hash-error", "stopped-during-check", "pid-replaced", "write-error", "restart-error", "post-restart-old", "stopped-during-restart"} {
		t.Run(scenario, func(t *testing.T) {
			pidCalls, writes, restarts := 0, 0, 0
			want := [32]byte{1}
			ops := wbHostUpgradeOps{
				managed: func(string) bool { return true },
				pid: func(string) (int, error) {
					pidCalls++
					if scenario == "pid-error" {
						return 0, errors.New("unavailable")
					}
					if scenario == "stopped-during-check" && pidCalls == 2 {
						return 0, nil
					}
					if scenario == "pid-replaced" && pidCalls == 2 {
						return 43, nil
					}
					if scenario == "stopped-during-restart" && pidCalls == 3 {
						return 0, nil
					}
					return 42, nil
				},
				exe: func(int) (string, error) {
					if scenario == "foreign-exe" {
						return "/usr/bin/unrelated", nil
					}
					return wbHostRoot + "/owner", nil
				},
				digest: func(int) ([32]byte, error) {
					if scenario == "hash-error" {
						return [32]byte{}, errors.New("denied")
					}
					return [32]byte{}, nil
				},
				write: func() error {
					writes++
					if scenario == "write-error" {
						return errors.New("disk full")
					}
					return nil
				},
				tryRestart: func(string) error {
					restarts++
					if scenario == "restart-error" {
						return errors.New("denied")
					}
					return nil
				},
			}
			room := "https://stream.wb.ru/room/qa"
			if scenario == "bad-room" {
				room = "https://evil.test/room/qa"
			}
			err := upgradeRunningWBHostWithOps(room, want, ops)
			expectSkip := strings.HasPrefix(scenario, "stopped-during-")
			if (err == nil) != expectSkip {
				t.Fatalf("error=%v expectSkip=%v", err, expectSkip)
			}
			if scenario == "bad-room" && pidCalls != 0 {
				t.Fatal("invalid room caused a system operation")
			}
			if scenario == "write-error" {
				if writes != 1 || restarts != 0 {
					t.Fatal("restarted after failed write")
				}
				return
			}
			if scenario == "restart-error" || scenario == "post-restart-old" || scenario == "stopped-during-restart" {
				if writes != 1 || restarts != 1 {
					t.Fatal("unexpected write/restart count")
				}
				return
			}
			if writes != 0 || restarts != 0 {
				t.Fatal("mutated service after failed safety check")
			}
		})
	}
}
