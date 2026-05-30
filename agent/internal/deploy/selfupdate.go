// selfupdate.go — pulls a new agent binary from a known URL, verifies
// the supplied sha256, atomically replaces /usr/local/bin/netguard-agent,
// then schedules its own os.Exit so systemd restarts the new code.
//
// The task itself reports "done" BEFORE the exit fires (~2s delay) so
// the API call that triggered it gets a clean response. The Android
// client polls /v1/health afterward to confirm the new version came up.

package deploy

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

// AgentBinaryPath is where systemd's ExecStart points; updating any
// other copy would be a no-op.
const AgentBinaryPath = "/usr/local/bin/netguard-agent"

// UpdateAgentRequest is the body of POST /v1/agent/update.
//
// URL must be HTTPS and reachable from the VPS. SHA256 is required —
// any release-pinning regime where we'd skip the hash is too dangerous
// to be worth supporting.
type UpdateAgentRequest struct {
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
}

// ApplyUploadedAgent installs a new agent binary the app uploaded over
// HTTPS (no external download / hosting needed). It verifies the sha256,
// smoke-tests that the binary actually runs on this host (`--version`) so a
// wrong-arch or corrupt upload can't brick the live agent, backs up the
// current binary, then atomically swaps it in. Caller restarts the service
// afterwards via [ScheduleAgentRestart].
func ApplyUploadedAgent(data []byte, wantSha string) error {
	sum := sha256.Sum256(data)
	got := hex.EncodeToString(sum[:])
	if wantSha != "" && !strings.EqualFold(got, wantSha) {
		return fmt.Errorf("sha256 mismatch: got %s want %s", got, wantSha)
	}
	if len(data) < 1_000_000 {
		return fmt.Errorf("uploaded binary suspiciously small (%d bytes)", len(data))
	}
	tmp := AgentBinaryPath + ".new"
	if err := os.WriteFile(tmp, data, 0o755); err != nil {
		return fmt.Errorf("write temp: %w", err)
	}
	// Smoke-test: the new binary must execute here. `--version` prints and
	// exits 0; a wrong-arch / corrupt binary fails, so we bail before
	// touching the live one.
	if out, err := exec.Command(tmp, "--version").CombinedOutput(); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("uploaded binary won't run here (wrong arch / corrupt): %v (%s)",
			err, strings.TrimSpace(string(out)))
	}
	_ = os.Rename(AgentBinaryPath, AgentBinaryPath+".bak")
	if err := os.Rename(tmp, AgentBinaryPath); err != nil {
		_ = os.Rename(AgentBinaryPath+".bak", AgentBinaryPath) // best-effort restore
		_ = os.Remove(tmp)
		return fmt.Errorf("swap binary: %w", err)
	}
	return nil
}

// ScheduleAgentRestart restarts the service ~1s later, so the caller's HTTP
// response is flushed first. `systemctl restart` picks up the swapped binary
// regardless of the unit's Restart= policy.
func ScheduleAgentRestart() {
	go func() {
		time.Sleep(1 * time.Second)
		log.Print("self-update: restarting via systemctl")
		_ = exec.Command("systemctl", "restart", "netguard-agent").Run()
	}()
}

// AgentUpdate is a tasks.Runner. Downloads → verifies → installs →
// schedules self-exit; framework reports task done before the exit.
func AgentUpdate(db *storage.DB, req *UpdateAgentRequest) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		if req.URL == "" || req.SHA256 == "" {
			return h.Fail("E_BAD_REQUEST",
				"url and sha256 are both required", false)
		}
		h.SetStep("download", 20)
		tmp := fmt.Sprintf("/tmp/netguard-agent.update-%s", h.TaskID())
		h.LogF("downloading %s", req.URL)
		if err := DownloadAndVerify(ctx, req.URL, tmp, req.SHA256); err != nil {
			return h.Fail("E_DOWNLOAD", err.Error(), true)
		}
		defer os.Remove(tmp)

		h.SetStep("backup", 60)
		bk, err := EnsureBackupDir(h.TaskID())
		if err != nil {
			return h.Fail("E_BACKUP_DIR", err.Error(), false)
		}
		if err := BackupFile(AgentBinaryPath, bk); err != nil {
			return h.Fail("E_BACKUP", err.Error(), false)
		}
		h.LogF("backed up current binary to %s", bk)

		h.SetStep("install", 80)
		data, err := os.ReadFile(tmp)
		if err != nil {
			return h.Fail("E_READ_TMP", err.Error(), false)
		}
		if err := AtomicWrite(AgentBinaryPath, data, 0o755); err != nil {
			return h.Fail("E_INSTALL", err.Error(), false)
		}
		h.LogF("installed %d-byte binary at %s", len(data), AgentBinaryPath)

		h.SetStep("restart-scheduled", 95)
		h.LogF("scheduling self-exit in 2s for systemd restart")

		// Fire the exit AFTER this runner returns and the framework has
		// persisted the final status row. systemd restarts us per
		// netguard-agent.service Restart=on-failure. The next time the
		// agent boots, FailRunningTasksOnStartup will NOT fire for this
		// task because we marked it done first.
		go func() {
			time.Sleep(2 * time.Second)
			log.Print("self-update: exiting now (systemd will restart)")
			os.Exit(0)
		}()

		return h.Ok(map[string]any{
			"installed_path": AgentBinaryPath,
			"arch":           runtime.GOARCH,
			"restart_in_s":   2,
			"note":           "agent will be unreachable for ~2-5s as systemd restarts it; poll /v1/health to confirm the new version",
		})
	}
}
