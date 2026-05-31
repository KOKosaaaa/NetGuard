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
	"io"
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

// ApplyUploadedAgent installs a new agent binary the app streamed over
// HTTPS (no external download / hosting needed). It STREAMS the body to a
// temp file while hashing (so a 64 MB upload never sits whole in RAM on a
// low-memory VPS), REQUIRES a matching sha256 (an empty hash is rejected —
// installing an unverified binary as root is never acceptable, even for an
// authenticated caller), smoke-tests that the binary runs on this host
// (`--version`) so a wrong-arch / corrupt upload can't brick the live
// agent, backs up the current binary, then atomically swaps it in. Caller
// restarts the service afterwards via [ScheduleAgentRestart].
func ApplyUploadedAgent(body io.Reader, wantSha string) error {
	if strings.TrimSpace(wantSha) == "" {
		return fmt.Errorf("sha256 is required (refusing to install an unverified binary)")
	}
	tmp := AgentBinaryPath + ".new"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o755)
	if err != nil {
		return fmt.Errorf("open temp: %w", err)
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), body)
	closeErr := f.Close()
	if err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("stream upload: %w", err)
	}
	if closeErr != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("flush temp: %w", closeErr)
	}
	if n < 1_000_000 {
		_ = os.Remove(tmp)
		return fmt.Errorf("uploaded binary suspiciously small (%d bytes)", n)
	}
	got := hex.EncodeToString(h.Sum(nil))
	if !strings.EqualFold(got, wantSha) {
		_ = os.Remove(tmp)
		return fmt.Errorf("sha256 mismatch: got %s want %s", got, wantSha)
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
		h.LogF("scheduling graceful restart in ~1s")

		// Restart via `systemctl restart` (graceful) instead of os.Exit(0):
		// a bare os.Exit skips the HTTP server's Shutdown and abandons any
		// other in-flight task mid-operation, and exit code 0 wouldn't even
		// trip Restart=on-failure. ScheduleAgentRestart lets systemd stop us
		// cleanly and bring the new binary up. Fires AFTER this runner
		// returns + the framework persists the final status row.
		ScheduleAgentRestart()

		return h.Ok(map[string]any{
			"installed_path": AgentBinaryPath,
			"arch":           runtime.GOARCH,
			"restart_in_s":   1,
			"note":           "agent will be unreachable for ~2-5s as systemd restarts it; poll /v1/health to confirm the new version",
		})
	}
}
