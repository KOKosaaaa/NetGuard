package deploy

// Swap setup — POST /v1/agent/swap-setup.
//
// Telemost's creator binary peaks at ~95 MB RSS during the WebRTC
// handshake. On a <1 GB VPS even a single stream can trip the OOM-killer
// (kernel + sshd + agent ~70 MB + creator peak 95 -> ~165 MB). A modest
// swapfile absorbs the transient peak so deploy succeeds. The app offers
// this when /v1/status reports RAM < ~1 GB.

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"

	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

const swapFilePath = "/swapfile"

// SwapSetupRequest is the POST /v1/agent/swap-setup body. SizeMB is
// optional; 0 means the 512 MB default.
type SwapSetupRequest struct {
	SizeMB int `json:"size_mb"`
}

// SwapSetup creates and enables /swapfile, persists it in /etc/fstab, and
// sets a gentle vm.swappiness so swap is a safety net under pressure
// rather than eagerly used. Idempotent: a no-op if /swapfile is already an
// active swap.
func SwapSetup(req *SwapSetupRequest) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		size := req.SizeMB
		if size == 0 {
			size = 512
		}
		if size < 128 || size > 4096 {
			return h.Fail("E_BAD_REQUEST",
				fmt.Sprintf("size_mb must be 128..4096 (got %d)", size), false)
		}

		h.SetStep("detect", 10)
		if swapfileActive(ctx) {
			h.LogF("%s is already an active swap; nothing to do", swapFilePath)
			return h.Ok(map[string]any{"swap_mb": size, "already": true})
		}

		// Disk headroom check (best-effort — skip silently if df is odd).
		h.SetStep("check_disk", 20)
		if freeMB, err := rootFreeMB(ctx); err == nil && freeMB < size+64 {
			return h.Fail("E_NO_DISK",
				fmt.Sprintf("only %d MB free on /, need ~%d MB for a %d MB swapfile",
					freeMB, size+64, size), false)
		}

		// Clean up a leftover from a previous partial run.
		if fileExists(swapFilePath) {
			_ = exec.CommandContext(ctx, "swapoff", swapFilePath).Run()
			_ = os.Remove(swapFilePath)
		}

		h.SetStep("allocate", 45)
		if err := fallocateSwap(ctx, swapFilePath, size); err != nil {
			// Some hosts lack fallocate; fall straight to dd.
			h.LogF("fallocate failed (%v); using dd", err)
			if err := ddSwap(ctx, swapFilePath, size); err != nil {
				return h.Fail("E_ALLOCATE", err.Error(), true)
			}
		}
		if err := os.Chmod(swapFilePath, 0o600); err != nil {
			return h.Fail("E_CHMOD", err.Error(), false)
		}

		h.SetStep("mkswap", 65)
		if out, err := Run(ctx, "mkswap", swapFilePath); err != nil {
			_ = os.Remove(swapFilePath)
			return h.Fail("E_MKSWAP", fmt.Sprintf("%s: %v", strings.TrimSpace(string(out)), err), false)
		}

		h.SetStep("swapon", 80)
		if out, err := Run(ctx, "swapon", swapFilePath); err != nil {
			// Classic failure on btrfs / holey fallocate files:
			// "swapon failed: Invalid argument". Recreate with dd (real
			// blocks) and retry once.
			h.LogF("swapon failed (%s); recreating with dd and retrying",
				strings.TrimSpace(string(out)))
			_ = os.Remove(swapFilePath)
			if err := ddSwap(ctx, swapFilePath, size); err != nil {
				return h.Fail("E_ALLOCATE", err.Error(), true)
			}
			_ = os.Chmod(swapFilePath, 0o600)
			if out2, err2 := Run(ctx, "mkswap", swapFilePath); err2 != nil {
				_ = os.Remove(swapFilePath)
				return h.Fail("E_MKSWAP", fmt.Sprintf("%s: %v", strings.TrimSpace(string(out2)), err2), false)
			}
			if out2, err2 := Run(ctx, "swapon", swapFilePath); err2 != nil {
				_ = os.Remove(swapFilePath)
				return h.Fail("E_SWAPON", fmt.Sprintf("%s: %v", strings.TrimSpace(string(out2)), err2), false)
			}
		}

		// Persist across reboots.
		h.SetStep("fstab", 90)
		backupDir, _ := EnsureBackupDir(h.TaskID())
		if backupDir != "" {
			_ = BackupFile("/etc/fstab", backupDir)
		}
		if err := ensureFstabSwap(); err != nil {
			h.LogF("warning: could not persist to /etc/fstab: %v (swap is active now but won't survive reboot)", err)
		}

		// Gentle swappiness: prefer RAM, fall back to swap only under pressure.
		_ = SysctlSet(ctx, backupDir, "vm.swappiness", "10")

		return h.Ok(map[string]any{"swap_mb": size, "swappiness": 10})
	}
}

// swapfileActive reports whether /swapfile is currently an active swap.
func swapfileActive(ctx context.Context) bool {
	out, err := exec.CommandContext(ctx, "swapon", "--show=NAME", "--noheadings").Output()
	if err != nil {
		return false
	}
	for _, l := range strings.Split(string(out), "\n") {
		if strings.TrimSpace(l) == swapFilePath {
			return true
		}
	}
	return false
}

// rootFreeMB returns the available space on / in MB via `df -Pm /`.
func rootFreeMB(ctx context.Context) (int, error) {
	out, err := exec.CommandContext(ctx, "df", "-Pm", "/").Output()
	if err != nil {
		return 0, err
	}
	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	if len(lines) < 2 {
		return 0, fmt.Errorf("unexpected df output")
	}
	// Filesystem 1M-blocks Used Available Capacity Mounted-on
	fields := strings.Fields(lines[len(lines)-1])
	if len(fields) < 4 {
		return 0, fmt.Errorf("unexpected df columns: %v", fields)
	}
	return strconv.Atoi(fields[3])
}

func fallocateSwap(ctx context.Context, path string, sizeMB int) error {
	out, err := exec.CommandContext(ctx, "fallocate", "-l",
		fmt.Sprintf("%dM", sizeMB), path).CombinedOutput()
	if err != nil {
		return fmt.Errorf("fallocate: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func ddSwap(ctx context.Context, path string, sizeMB int) error {
	out, err := exec.CommandContext(ctx, "dd",
		"if=/dev/zero", "of="+path, "bs=1M",
		fmt.Sprintf("count=%d", sizeMB), "status=none").CombinedOutput()
	if err != nil {
		return fmt.Errorf("dd: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// ensureFstabSwap appends the swap entry to /etc/fstab if absent. Idempotent.
func ensureFstabSwap() error {
	const entry = swapFilePath + " none swap sw 0 0"
	data, err := os.ReadFile("/etc/fstab")
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if strings.Contains(string(data), swapFilePath) {
		return nil // already present
	}
	body := string(data)
	if body != "" && !strings.HasSuffix(body, "\n") {
		body += "\n"
	}
	body += entry + "\n"
	return AtomicWrite("/etc/fstab", []byte(body), 0o644)
}
