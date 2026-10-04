package deploy

import (
	"context"
	"crypto/sha256"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"
)

type wbHostUpgradeOps struct {
	managed    func(string) bool
	pid        func(string) (int, error)
	exe        func(int) (string, error)
	digest     func(int) ([sha256.Size]byte, error)
	write      func() error
	tryRestart func(string) error
}

// Only upgrades this running managed room. The shared file also supplies future
// starts of other rooms, but their existing processes are deliberately untouched.
// Login state is neither read nor replaced. Caller serializes through wbLock.
func upgradeRunningWBHost(ctx context.Context, room string) error {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	ops := wbHostUpgradeOps{
		managed: func(unit string) bool {
			info, err := os.Lstat("/etc/systemd/system/" + unit)
			return err == nil && info.Mode().IsRegular()
		},
		pid: func(unit string) (int, error) {
			out, err := Run(ctx, "systemctl", "show", "--property=MainPID", "--value", unit)
			if err != nil {
				return 0, fmt.Errorf("cannot inspect running WB owner")
			}
			pid, err := strconv.Atoi(strings.TrimSpace(string(out)))
			if err != nil || pid < 0 {
				return 0, fmt.Errorf("invalid WB owner process ID")
			}
			return pid, nil
		},
		exe: func(pid int) (string, error) { return os.Readlink(fmt.Sprintf("/proc/%d/exe", pid)) },
		digest: func(pid int) ([sha256.Size]byte, error) {
			var result [sha256.Size]byte
			f, err := os.Open(fmt.Sprintf("/proc/%d/exe", pid))
			if err != nil {
				return result, err
			}
			defer f.Close()
			h := sha256.New()
			if _, err = io.Copy(h, f); err != nil {
				return result, err
			}
			copy(result[:], h.Sum(nil))
			return result, nil
		},
		write:      func() error { return AtomicWrite(wbHostRoot+"/owner", headlessTelemostCreator, 0755) },
		tryRestart: func(unit string) error { _, err := Run(ctx, "systemctl", "try-restart", unit); return err },
	}
	return upgradeRunningWBHostWithOps(room, sha256.Sum256(headlessTelemostCreator), ops)
}

func upgradeRunningWBHostWithOps(room string, want [sha256.Size]byte, ops wbHostUpgradeOps) error {
	canonical, err := normalizeWBRoom(room)
	if err != nil {
		return err
	}
	unit := wbHostUnit(wbKey(canonical))
	if !ops.managed(unit) {
		return nil
	}
	pid, err := ops.pid(unit)
	if err != nil {
		return err
	}
	if pid == 0 {
		return nil
	}
	check := func(pid int) ([sha256.Size]byte, error) {
		var empty [sha256.Size]byte
		path, err := ops.exe(pid)
		if err != nil || strings.TrimSuffix(path, " (deleted)") != wbHostRoot+"/owner" {
			return empty, fmt.Errorf("running WB owner executable cannot be verified")
		}
		digest, err := ops.digest(pid)
		if err != nil {
			return empty, fmt.Errorf("cannot inspect running WB owner executable")
		}
		return digest, nil
	}
	digest, err := check(pid)
	if err != nil {
		return err
	}
	if digest == want {
		return nil
	}
	// Do not act on a PID inspected before an external stop/restart.
	current, err := ops.pid(unit)
	if err != nil {
		return err
	}
	if current == 0 {
		return nil
	}
	if current != pid {
		return fmt.Errorf("WB owner changed during update; retry")
	}
	if err = ops.write(); err != nil {
		return fmt.Errorf("cannot install updated WB owner")
	}
	// try-restart never starts a service stopped concurrently by the user.
	if err = ops.tryRestart(unit); err != nil {
		return fmt.Errorf("cannot restart updated WB owner")
	}
	current, err = ops.pid(unit)
	if err != nil {
		return err
	}
	if current == 0 {
		return nil
	}
	digest, err = check(current)
	if err != nil {
		return err
	}
	if digest != want {
		return fmt.Errorf("WB owner is still running the previous executable")
	}
	return nil
}
