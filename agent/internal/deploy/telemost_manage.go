package deploy

// Telemost management endpoints — task #33 follow-up to DeployTelemost.
//
//	GET  /v1/telemost/rooms   -> TelemostRooms      (sync, read state)
//	POST /v1/telemost/scale   -> ScaleTelemost      (async task)
//	POST /v1/telemost/cookies -> UpdateTelemostCookies (async task)
//
// All three assume DeployTelemost has already run (binary + unit + links
// file present). They reuse the package-level helpers from telemost.go
// (createOneRoom, countNonBlankLines, readNonBlankLines, the path consts).

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

// telemostUnitPrefix is the systemd template stem; instance i is
// "wlb-telemost@<i>.service".
const telemostUnitPrefix = "wlb-telemost@"

func telemostUnit(i int) string {
	return fmt.Sprintf("%s%d.service", telemostUnitPrefix, i)
}

// TelemostInstanceStatus is one provisioned room + its live systemd state.
type TelemostInstanceStatus struct {
	Index  int    `json:"index"`
	Room   string `json:"room"`
	Active bool   `json:"active"`
}

// TelemostRoomsResult is the GET /v1/telemost/rooms body.
//
// Installed is the number of provisioned rooms (lines in the links file) —
// this is the capacity; ActiveCount is how many instances are currently
// running. Scaling down stops instances without dropping their room URLs,
// so Installed >= ActiveCount is normal.
type TelemostRoomsResult struct {
	Deployed    bool                     `json:"deployed"`
	Installed   int                      `json:"installed"`
	ActiveCount int                      `json:"active_count"`
	Instances   []TelemostInstanceStatus `json:"instances"`
}

// TelemostRooms reports provisioned rooms + per-instance systemd state.
// Sync (fast) — no task needed. Returns Deployed=false (everything else
// zero) when Telemost was never deployed on this host.
func TelemostRooms(ctx context.Context) (TelemostRoomsResult, error) {
	res := TelemostRoomsResult{Instances: []TelemostInstanceStatus{}}
	if !fileExists(telemostInstallPath) || !fileExists(telemostLinksFile) {
		return res, nil
	}
	res.Deployed = true
	rooms, err := readNonBlankLines(telemostLinksFile)
	if err != nil {
		return res, err
	}
	res.Installed = len(rooms)
	for i, url := range rooms {
		idx := i + 1
		active := SystemctlIsActive(ctx, telemostUnit(idx))
		if active {
			res.ActiveCount++
		}
		res.Instances = append(res.Instances, TelemostInstanceStatus{
			Index: idx, Room: url, Active: active,
		})
	}
	return res, nil
}

// ScaleTelemostRequest is the POST /v1/telemost/scale body.
type ScaleTelemostRequest struct {
	TargetCount int `json:"target_count"`
}

// ScaleTelemost changes the number of running instances WITHOUT a full
// re-deploy. The model:
//
//   - provisioned rooms = lines in conference-links.txt (capacity).
//   - target <= provisioned: just enable 1..target, disable the rest.
//     Rooms persist in Yandex, so scaling back up later re-uses them with
//     no new room creation (avoids tripping Yandex's abuse heuristics).
//   - target  > provisioned: create (target-provisioned) fresh rooms first,
//     then enable 1..target.
//
// target == 0 stops every instance but keeps the install (a "pause").
func ScaleTelemost(req *ScaleTelemostRequest) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		target := req.TargetCount
		if target < 0 || target > 12 {
			return h.Fail("E_BAD_REQUEST",
				fmt.Sprintf("target_count must be 0..12 (got %d)", target), false)
		}
		if !fileExists(telemostInstallPath) || !fileExists(telemostUnitPath) {
			return h.Fail("E_TELEMOST_NOT_DEPLOYED",
				"Telemost is not deployed yet; POST /v1/telemost/deploy first", false)
		}

		h.SetStep("detect", 5)
		provisioned, _ := countNonBlankLines(telemostLinksFile)
		h.LogF("provisioned rooms=%d target=%d", provisioned, target)

		// --- scale up beyond provisioned: create the extra rooms ---
		if target > provisioned {
			if !fileExists(telemostCookiesFile) {
				return h.Fail("E_TELEMOST_NO_COOKIES",
					"need Yandex cookies to create more rooms; PUT /v1/telemost/cookies first", false)
			}
			h.SetStep("create_rooms", 30)
			for i := provisioned + 1; i <= target; i++ {
				h.LogF("creating room %d/%d", i, target)
				if err := createOneRoom(ctx); err != nil {
					if strings.Contains(err.Error(), "OOM_KILLED") {
						return h.Fail("E_OOM", err.Error(), false)
					}
					return h.Fail("E_CREATE_ROOM",
						fmt.Sprintf("room %d: %v", i, err), true)
				}
			}
		}
		provisionedNow, _ := countNonBlankLines(telemostLinksFile)

		// --- enable 1..target, disable target+1..provisionedNow ---
		h.SetStep("apply", 70)
		for i := 1; i <= target; i++ {
			unit := telemostUnit(i)
			if out, err := exec.CommandContext(ctx, "systemctl", "enable", "--now", unit).CombinedOutput(); err != nil {
				return h.Fail("E_SYSTEMCTL_START",
					fmt.Sprintf("%s: %v (%s)", unit, err, strings.TrimSpace(string(out))), true)
			}
		}
		for i := target + 1; i <= provisionedNow; i++ {
			// Best-effort: a not-loaded instance returns non-zero, which is fine.
			_, _ = exec.CommandContext(ctx, "systemctl", "disable", "--now", telemostUnit(i)).CombinedOutput()
		}

		// --- healthcheck the ones we just brought up ---
		h.SetStep("healthcheck", 90)
		if target > 0 {
			if down := waitTelemostActive(ctx, target, 30*time.Second); down != "" {
				return h.Fail("E_HEALTHCHECK",
					fmt.Sprintf("%s did not become active within 30s; check journalctl -u %s", down, down), true)
			}
		}

		rooms, _ := readNonBlankLines(telemostLinksFile)
		activeRooms := rooms
		if target < len(rooms) {
			activeRooms = rooms[:target]
		}
		return h.Ok(map[string]any{
			"target_count": target,
			"provisioned":  len(rooms),
			"active_rooms": activeRooms,
		})
	}
}

// UpdateCookiesRequest is the POST /v1/telemost/cookies body.
type UpdateCookiesRequest struct {
	CookiesJSON string `json:"cookies_json"`
}

// UpdateTelemostCookies rewrites the shared cookies file (used when the
// Yandex session expires, ~yearly) and restarts every loaded instance so
// they re-authenticate. Does NOT create or destroy rooms.
func UpdateTelemostCookies(req *UpdateCookiesRequest) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		if strings.TrimSpace(req.CookiesJSON) == "" {
			return h.Fail("E_BAD_REQUEST", "cookies_json is required", false)
		}
		if !fileExists(telemostInstallPath) {
			return h.Fail("E_TELEMOST_NOT_DEPLOYED",
				"Telemost is not deployed yet; POST /v1/telemost/deploy first", false)
		}

		h.SetStep("write_cookies", 30)
		if err := os.MkdirAll(telemostConfDir, 0o750); err != nil {
			return h.Fail("E_DATA_DIR", err.Error(), false)
		}
		if err := AtomicWrite(telemostCookiesFile, []byte(req.CookiesJSON), 0o640); err != nil {
			return h.Fail("E_WRITE_CONFIG", err.Error(), false)
		}
		_ = exec.CommandContext(ctx, "chown", "root:wlb", telemostCookiesFile).Run()

		// Restart whatever instances are currently loaded so they pick up
		// the new cookies. Same list-units pattern as UninstallTelemost.
		h.SetStep("restart", 70)
		_, _ = exec.CommandContext(ctx, "sh", "-c",
			"systemctl list-units --type=service 'wlb-telemost@*' --no-legend --plain | awk '{print $1}' | xargs -r systemctl restart").CombinedOutput()

		// Report active count after a short settle. We don't fail on
		// inactive units here — bad cookies are user error, and the unit's
		// Restart=on-failure keeps retrying; the app surfaces the count.
		h.SetStep("healthcheck", 90)
		select {
		case <-ctx.Done():
		case <-time.After(4 * time.Second):
		}
		rooms, _ := readNonBlankLines(telemostLinksFile)
		active := 0
		for i := 1; i <= len(rooms); i++ {
			if SystemctlIsActive(ctx, telemostUnit(i)) {
				active++
			}
		}
		return h.Ok(map[string]any{
			"updated":      true,
			"provisioned":  len(rooms),
			"active_count": active,
		})
	}
}

// waitTelemostActive polls instances 1..n until all are active or timeout.
// Returns "" on success, or the unit name of the first one still down.
func waitTelemostActive(ctx context.Context, n int, timeout time.Duration) string {
	deadline := time.Now().Add(timeout)
	for {
		firstDown := ""
		for i := 1; i <= n; i++ {
			unit := telemostUnit(i)
			if !SystemctlIsActive(ctx, unit) {
				firstDown = unit
				break
			}
		}
		if firstDown == "" {
			return ""
		}
		if time.Now().After(deadline) {
			return firstDown
		}
		select {
		case <-ctx.Done():
			return firstDown
		case <-time.After(3 * time.Second):
		}
	}
}
