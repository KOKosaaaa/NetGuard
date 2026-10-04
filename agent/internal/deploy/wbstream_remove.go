package deploy

import (
	"context"
	"fmt"
	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
	"os"
	"path/filepath"
	"time"
)

type wbRemovalOps struct {
	stop   func(string) error
	remove func(string) error
	reload func() error
}

// Stop both the publisher and a possible upgrade keeper BEFORE removing their
// units/configuration. Never orphan a running service on a failed stop.
func removeWBRoom(room string, ops wbRemovalOps) error {
	canonical, err := normalizeWBRoom(room)
	if err != nil {
		return err
	}
	key := wbKey(canonical)
	for _, k := range []string{key, key + "-keeper", key + "-host"} {
		if err = ops.stop(wbUnit(k)); err != nil {
			return err
		}
	}
	for _, k := range []string{key, key + "-keeper", key + "-host"} {
		if err = ops.remove("/etc/systemd/system/" + wbUnit(k)); err != nil {
			return err
		}
	}
	if err = ops.reload(); err != nil {
		return err
	}
	for _, name := range []string{"state.json", "state.tmp", "status.json", "status.tmp", ".session-upload"} {
		if err = ops.remove(filepath.Join(wbHostDir(key), name)); err != nil {
			return err
		}
	}
	if err = ops.remove(wbHostDir(key)); err != nil {
		return err
	}
	return ops.remove(filepath.Join(wbDir, key+".room"))
}

func DeleteWBStream(req *WBDeployRequest) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		wbLock.Lock()
		defer wbLock.Unlock()
		if _, err := normalizeWBRoom(req.Room); err != nil {
			return h.Fail("E_BAD_REQUEST", err.Error(), false)
		}
		ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
		defer cancel()
		h.SetStep("wb_remove", 20)
		err := removeWBRoom(req.Room, wbRemovalOps{
			stop: func(unit string) error {
				// An absent, inactive unit is already removed (safe retry).
				if !fileExists("/etc/systemd/system/"+unit) && !SystemctlIsActive(ctx, unit) {
					return nil
				}
				if _, err := Run(ctx, "systemctl", "disable", "--now", unit); err != nil {
					return err
				}
				if SystemctlIsActive(ctx, unit) {
					return fmt.Errorf("WB service is still running")
				}
				return nil
			},
			remove: func(path string) error {
				err := os.Remove(path)
				if os.IsNotExist(err) {
					return nil
				}
				return err
			},
			reload: func() error { _, err := Run(ctx, "systemctl", "daemon-reload"); return err },
		})
		if err != nil {
			return h.Fail("E_WB_REMOVE", err.Error(), true)
		}
		return h.Ok(map[string]any{"removed": true})
	}
}
