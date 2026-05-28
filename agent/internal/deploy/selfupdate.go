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
	"fmt"
	"log"
	"os"
	"runtime"
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
