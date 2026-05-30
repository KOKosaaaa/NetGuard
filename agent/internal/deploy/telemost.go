package deploy

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

// headlessTelemostCreator is the pre-built Linux binary of
// `kulikov0/whitelist-bypass/headless/telemost` for THIS build's
// architecture. The variable is declared in telemost_amd64.go /
// telemost_arm64.go behind build tags so each agent binary only
// embeds the bytes it can actually run — drops ~10 MB per arch.

const (
	telemostInstallPath = "/usr/local/bin/headless-telemost-creator"
	telemostConfDir     = "/etc/whitelist-bypass"
	telemostLogDir      = "/var/log/whitelist-bypass"
	telemostRunDir      = "/run/whitelist-bypass"
	telemostUnitName    = "wlb-telemost@.service"
	telemostUnitPath    = "/etc/systemd/system/wlb-telemost@.service"
	telemostLinksFile   = "/etc/whitelist-bypass/conference-links.txt"
	telemostCookiesFile = "/etc/whitelist-bypass/cookies-yandex.json"
)

// telemostUnitTemplate is the systemd unit installed at TelemostUnitPath.
// `%i` is the instance id passed by `wlb-telemost@1`, `wlb-telemost@2`, …
//
// Each instance reads ONE line from /etc/whitelist-bypass/conference-links.txt
// and joins that room with the shared cookies file. We start instances 1..N
// where N == number of lines in conference-links.txt.
//
// Hardening notes:
//   - User=wlb (no shell, no login) — least privilege.
//   - RestrictAddressFamilies MUST include AF_NETLINK; pion/webrtc walks
//     /proc/net via netlink during ICE candidate gathering, and without
//     AF_NETLINK the gather phase fails with "address family not supported".
//   - We let it bind UDP for STUN/TURN; ProtectSystem=strict + ProtectHome
//     give a sealed fs view.
const telemostUnitTemplate = `[Unit]
Description=Whitelist-bypass Telemost instance %i
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=wlb
Group=wlb
WorkingDirectory=/run/whitelist-bypass
StandardOutput=append:/var/log/whitelist-bypass/telemost-%i.log
StandardError=inherit
Restart=on-failure
RestartSec=10

# Per-instance: read the %i-th line of conference-links.txt as -tm-link.
# sed -n "%iP" prints just that line; the wrapper script keeps things
# composable in case we ever swap join modes (anon, alt accounts, ...).
ExecStartPre=/bin/sh -c 'test -s /etc/whitelist-bypass/conference-links.txt'
ExecStart=/bin/sh -c '/usr/local/bin/headless-telemost-creator \
    -cookies /etc/whitelist-bypass/cookies-yandex.json \
    -resources moderate \
    -tm-link "$(sed -n "%iP" /etc/whitelist-bypass/conference-links.txt)"'

NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/log/whitelist-bypass /run/whitelist-bypass
RestrictAddressFamilies=AF_INET AF_INET6 AF_NETLINK AF_UNIX
RestrictNamespaces=true
RestrictRealtime=true
LockPersonality=true

[Install]
WantedBy=multi-user.target
`

// DeployTelemostRequest is the body of POST /v1/telemost/deploy.
//
// count: number of instances (1..12). Each will join its own
//
//	persistent Yandex Telemost room.
//
// cookiesJSON: full content of cookies-yandex.json (Session_id / sessar
//
//	/ L / sessionid2 …). Required because the creator binary
//	needs an authenticated session to both create rooms and
//	join them with the user's identity.
type DeployTelemostRequest struct {
	Count       int    `json:"count"`
	CookiesJSON string `json:"cookies_json"`
}

// DeployTelemostResult is the JSON returned via /v1/tasks/{id}.result
// on success. URIs is one entry per instance — the user-facing app
// imports the composite multi-x{N} URI built on top of these.
type DeployTelemostResult struct {
	Count int      `json:"count"`
	Rooms []string `json:"rooms"`
}

// DeployTelemost is the Runner spawned by POST /v1/telemost/deploy.
//
// Steps mirror XrayDeploy's idempotency contract: detect existing
// install + don't re-clobber a hand-tuned setup, write atomically,
// healthcheck before declaring success.
func DeployTelemost(req *DeployTelemostRequest) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		if req.Count < 1 || req.Count > 12 {
			return h.Fail("E_BAD_REQUEST",
				fmt.Sprintf("count must be 1..12 (got %d)", req.Count), false)
		}
		if strings.TrimSpace(req.CookiesJSON) == "" {
			return h.Fail("E_BAD_REQUEST",
				"cookies_json is required", false)
		}

		h.SetStep("detect", 5)
		alreadyHaveBinary := fileExists(telemostInstallPath)
		hasUnit := fileExists(telemostUnitPath)
		hasLinks := fileExists(telemostLinksFile)
		h.LogF("detect: binary=%v unit=%v links=%v",
			alreadyHaveBinary, hasUnit, hasLinks)

		// --- 1. install binary ---
		h.SetStep("install_bin", 15)
		blob, err := telemostCreatorForArch()
		if err != nil {
			return h.Fail("E_UNSUPPORTED_ARCH", err.Error(), false)
		}
		// Write to tmp + rename for atomicity.
		if err := AtomicWrite(telemostInstallPath, blob, 0o755); err != nil {
			return h.Fail("E_INSTALL_BIN", err.Error(), false)
		}

		// --- 2. wlb user + dirs ---
		h.SetStep("user_dirs", 25)
		if err := ensureWlbUser(ctx); err != nil {
			return h.Fail("E_INSTALL", err.Error(), false)
		}
		for _, d := range []string{telemostConfDir, telemostLogDir, telemostRunDir} {
			if err := os.MkdirAll(d, 0o750); err != nil {
				return h.Fail("E_DATA_DIR", err.Error(), false)
			}
		}
		_ = exec.CommandContext(ctx, "chown", "-R", "wlb:wlb", telemostLogDir, telemostRunDir).Run()
		_ = exec.CommandContext(ctx, "chgrp", "wlb", telemostConfDir).Run()
		_ = os.Chmod(telemostConfDir, 0o750)

		// --- 3. write cookies (root:wlb 640) ---
		h.SetStep("cookies", 35)
		if err := AtomicWrite(telemostCookiesFile, []byte(req.CookiesJSON), 0o640); err != nil {
			return h.Fail("E_WRITE_CONFIG", err.Error(), false)
		}
		_ = exec.CommandContext(ctx, "chown", "root:wlb", telemostCookiesFile).Run()

		// --- 4. create N rooms one-by-one ---
		// We run the creator binary in "no -tm-link" mode N times — each
		// run creates a fresh permanent room and appends its URL to the
		// links file. The creator process exits after the first
		// successful join, which is enough for our purpose; we read the
		// just-appended URL from the file and move on.
		h.SetStep("create_rooms", 50)
		if err := os.Truncate(telemostLinksFile, 0); err != nil && !os.IsNotExist(err) {
			_ = err // best-effort wipe; ensures fresh file on re-deploy
		}
		if f, err := os.OpenFile(telemostLinksFile, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o640); err == nil {
			f.Close()
		}
		_ = exec.CommandContext(ctx, "chown", "root:wlb", telemostLinksFile).Run()

		// Each creator invocation can take 5-15s to negotiate. Use a
		// per-call timeout to avoid hanging the whole task on one
		// unhappy run.
		for i := 1; i <= req.Count; i++ {
			h.LogF("creating room %d/%d", i, req.Count)
			if err := createOneRoom(ctx); err != nil {
				return h.Fail("E_TELEMOST_PENDING",
					fmt.Sprintf("room %d/%d: %v", i, req.Count, err), true)
			}
		}

		// --- 5. systemd unit + start instances ---
		h.SetStep("systemd_unit", 75)
		if err := AtomicWrite(telemostUnitPath, []byte(telemostUnitTemplate), 0o644); err != nil {
			return h.Fail("E_WRITE_UNIT", err.Error(), false)
		}
		if _, err := exec.CommandContext(ctx, "systemctl", "daemon-reload").CombinedOutput(); err != nil {
			return h.Fail("E_DAEMON_RELOAD", err.Error(), true)
		}

		h.SetStep("systemd_start", 85)
		for i := 1; i <= req.Count; i++ {
			unit := fmt.Sprintf("wlb-telemost@%d.service", i)
			if _, err := exec.CommandContext(ctx, "systemctl", "enable", "--now", unit).CombinedOutput(); err != nil {
				return h.FailRolledBack("E_SYSTEMCTL_START",
					fmt.Sprintf("%s: %v", unit, err), true)
			}
		}

		// --- 6. healthcheck: every instance should be active after the
		//        first 30 seconds. Some processes fail their first start
		//        on cookies-token negotiation and we rely on the systemd
		//        unit's Restart=on-failure (RestartSec=10) to bring them
		//        back up. The old single-pass 5-sec check failed deploys
		//        that ultimately succeeded — instead poll for up to
		//        30 sec with 3-sec ticks, failing only if a unit stays
		//        inactive across the whole window.
		h.SetStep("healthcheck", 95)
		deadline := time.Now().Add(30 * time.Second)
		for {
			allUp := true
			var firstDown string
			for i := 1; i <= req.Count; i++ {
				unit := fmt.Sprintf("wlb-telemost@%d.service", i)
				if !SystemctlIsActive(ctx, unit) {
					allUp = false
					firstDown = unit
					break
				}
			}
			if allUp {
				break
			}
			if time.Now().After(deadline) {
				return h.FailRolledBack("E_HEALTHCHECK",
					fmt.Sprintf("%s did not become active within 30s; check journalctl", firstDown), true)
			}
			time.Sleep(3 * time.Second)
		}

		// Read the links we just produced so we can echo them back to
		// the caller (Android client uses them to build the composite
		// multi-channel URI for the user's subscription).
		links, _ := readNonBlankLines(telemostLinksFile)
		return h.Ok(DeployTelemostResult{
			Count: req.Count,
			Rooms: links,
		})
	}
}

// PrepareTelemost stages the Telemost worker WITHOUT creating any rooms:
// it installs the embedded creator binary, the wlb user + dirs, and the
// systemd unit template. Rooms still need Yandex cookies (the user logs in
// from the app later) — this just makes that later step fast and means
// "Telemost is installed" the moment the server is added.
//
// It intentionally does NOT write cookies, create rooms, or start any
// instance. TelemostRooms() still reports Deployed=false until a links
// file exists, so the app's Telemost card stays hidden until real rooms
// are provisioned.
func PrepareTelemost() tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		h.SetStep("install_bin", 20)
		blob, err := telemostCreatorForArch()
		if err != nil {
			return h.Fail("E_UNSUPPORTED_ARCH", err.Error(), false)
		}
		if err := AtomicWrite(telemostInstallPath, blob, 0o755); err != nil {
			return h.Fail("E_INSTALL_BIN", err.Error(), false)
		}

		h.SetStep("user_dirs", 55)
		if err := ensureWlbUser(ctx); err != nil {
			return h.Fail("E_INSTALL", err.Error(), false)
		}
		for _, d := range []string{telemostConfDir, telemostLogDir, telemostRunDir} {
			if err := os.MkdirAll(d, 0o750); err != nil {
				return h.Fail("E_DATA_DIR", err.Error(), false)
			}
		}
		_ = exec.CommandContext(ctx, "chown", "-R", "wlb:wlb", telemostLogDir, telemostRunDir).Run()
		_ = exec.CommandContext(ctx, "chgrp", "wlb", telemostConfDir).Run()
		_ = os.Chmod(telemostConfDir, 0o750)

		h.SetStep("systemd_unit", 85)
		if err := AtomicWrite(telemostUnitPath, []byte(telemostUnitTemplate), 0o644); err != nil {
			return h.Fail("E_WRITE_UNIT", err.Error(), false)
		}
		_, _ = exec.CommandContext(ctx, "systemctl", "daemon-reload").CombinedOutput()

		return h.Ok(map[string]any{
			"prepared": true,
			"note":     "binary + unit staged; rooms need Yandex cookies (log in from the app)",
		})
	}
}

// telemostCreatorForArch returns the embedded binary matching the host
// architecture, or an error on unsupported hosts. The binary itself is
// selected at build time via per-arch files (telemost_{amd64,arm64}.go);
// this function just sanity-checks that the agent is running on the
// arch it was compiled for and surfaces a friendlier error otherwise.
func telemostCreatorForArch() ([]byte, error) {
	if runtime.GOARCH != "amd64" && runtime.GOARCH != "arm64" {
		return nil, fmt.Errorf("Telemost creator only ships for linux/amd64 + linux/arm64; got %s", runtime.GOARCH)
	}
	return headlessTelemostCreator, nil
}

func ensureWlbUser(ctx context.Context) error {
	// Idempotent: getent returns 2 if missing.
	if exec.CommandContext(ctx, "getent", "passwd", "wlb").Run() == nil {
		return nil
	}
	out, err := exec.CommandContext(ctx, "useradd",
		"--system", "--no-create-home",
		"--shell", "/usr/sbin/nologin", "wlb").CombinedOutput()
	if err != nil {
		return fmt.Errorf("useradd wlb: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// createOneRoom runs the creator binary without -tm-link so it builds a
// new permanent room, joins it, and writes its URL to the links file —
// then we kill the process. The creator doesn't exit on its own (it
// stays connected to the Yandex SFU running the event loop), so the
// classic wait-for-exit pattern would block forever; instead we poll
// the links file for a new line and tear the process down as soon as
// the URL is written. Moderate resources cap (64 MiB) keeps RAM
// pressure modest for low-end VPSes.
func createOneRoom(ctx context.Context) error {
	beforeLines, _ := countNonBlankLines(telemostLinksFile)

	cctx, cancel := context.WithTimeout(ctx, 90*time.Second)
	defer cancel()
	cmd := exec.CommandContext(cctx,
		telemostInstallPath,
		"-cookies", telemostCookiesFile,
		"-resources", "moderate",
		"-write-file", telemostLinksFile,
	)
	// Capture stdout/stderr in a buffer for diagnostics if we need to
	// fail with details. We discard it on the happy path.
	var buf strings.Builder
	cmd.Stdout = &buf
	cmd.Stderr = &buf
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("creator start: %w", err)
	}

	// Poll the links file every 500ms — the creator writes the URL
	// early (right after createAndJoinCall returns, before the WebSocket
	// event loop starts), so this usually fires within 5-10 seconds.
	deadline := time.Now().Add(85 * time.Second)
	got := false
	for time.Now().Before(deadline) {
		cur, _ := countNonBlankLines(telemostLinksFile)
		if cur > beforeLines {
			got = true
			break
		}
		// Sanity-check: did the process die early (e.g. cookies expired,
		// real OOM)? If yes, surface that instead of timing out.
		if cmd.ProcessState != nil && cmd.ProcessState.Exited() {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}

	// Tear down the creator — it's done its job (URL written) and will
	// otherwise sit on a WebSocket forever holding memory. We ignore
	// the error from Kill/Wait since the URL is the only success
	// criterion that matters.
	_ = cmd.Process.Kill()
	_ = cmd.Wait()

	if !got {
		errOut := strings.TrimSpace(buf.String())
		// Check the actual exit status — if the process was killed by
		// the kernel before we could grab its URL, the creator log
		// usually contains "Failed to create call" or similar. A real
		// OOM-kill happens via SIGKILL which doesn't appear in the
		// program log; we only know via exit status.
		if cmd.ProcessState != nil {
			if ws, ok := cmd.ProcessState.Sys().(interface{ Signaled() bool }); ok && ws.Signaled() {
				// Process was killed by a signal — most likely SIGKILL
				// from OOM-killer. Surface with the marker the app
				// maps to the friendly "out of memory" dialog.
				if cmd.ProcessState.ExitCode() == -1 ||
					strings.Contains(errOut, "out of memory") {
					return fmt.Errorf("OOM_KILLED: creator was killed before writing the room URL: %s", errOut)
				}
			}
		}
		return fmt.Errorf("creator did not produce a room URL within 90s: %s", errOut)
	}
	return nil
}

// countNonBlankLines reports the number of non-empty trimmed lines in
// path. Returns 0 (no error) if the file doesn't exist yet — that lets
// the caller use it as a "baseline" without special-casing the first
// call.
func countNonBlankLines(path string) (int, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, err
	}
	n := 0
	for _, l := range strings.Split(string(data), "\n") {
		if strings.TrimSpace(l) != "" {
			n++
		}
	}
	return n, nil
}

// readNonBlankLines reads the file and returns each non-empty trimmed
// line. Used to surface the just-created rooms back to the client.
func readNonBlankLines(path string) ([]string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var out []string
	for _, l := range strings.Split(string(data), "\n") {
		if s := strings.TrimSpace(l); s != "" {
			out = append(out, s)
		}
	}
	return out, nil
}

// UninstallTelemost wipes the install — used both by the operator and
// by the rollback path inside DeployTelemost when a hop fails halfway.
func UninstallTelemost(ctx context.Context) tasks.Runner {
	return func(ctx context.Context, h *tasks.Handle) tasks.Outcome {
		h.SetStep("stop", 30)
		// Best-effort stop of any wlb-telemost@N instance.
		_, _ = exec.CommandContext(ctx, "sh", "-c",
			"systemctl list-units --type=service 'wlb-telemost@*' --no-legend --plain | awk '{print $1}' | xargs -r systemctl disable --now").CombinedOutput()

		h.SetStep("remove", 60)
		for _, p := range []string{telemostUnitPath, telemostInstallPath,
			telemostCookiesFile, telemostLinksFile} {
			_ = os.Remove(p)
		}
		_, _ = exec.CommandContext(ctx, "systemctl", "daemon-reload").CombinedOutput()
		// Keep /etc/whitelist-bypass + logs around for the operator to
		// inspect; rm -rf would also nuke audio decoys / future state.
		return h.Ok(map[string]any{"removed": true})
	}
}

// _ stops the compiler from complaining about an unused stdlib symbol
// path on the rare codepath where we don't actually call filepath.
var _ = filepath.Join
