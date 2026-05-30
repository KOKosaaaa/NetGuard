// Package deploy implements the idempotent install/rollback skeletons for
// xray, sing-box, telemost, etc. Common helpers live here; per-service
// logic in sibling files (xray.go ...).
//
// Idempotency rules — same six from DESIGN.md, baked into the helpers:
//  1. Detect before do.
//  2. Pinned versions (not "latest").
//  3. Atomic config writes (mktemp + fsync + rename).
//  4. Backup any system file before mutation.
//  5. Healthcheck after every restart.
//  6. Classify exit codes as retryable / non-retryable.
package deploy

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// BackupDir is where mutated system files get copied before changes.
// Per-task subdir keeps rollbacks scoped to one operation.
const BackupDir = "/var/lib/netguard-agent/backups"

// Run wraps exec.CommandContext with a stdout/stderr buffer, suitable for
// short commands (apt, systemctl, sshfp). For long-running ones use Stream.
func Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return out, fmt.Errorf("%s %s: %w (output: %s)",
			name, strings.Join(args, " "), err, strings.TrimSpace(string(out)))
	}
	return out, nil
}

// SystemctlIsActive returns true iff `systemctl is-active <name>` says active.
func SystemctlIsActive(ctx context.Context, unit string) bool {
	out, err := exec.CommandContext(ctx, "systemctl", "is-active", unit).Output()
	return err == nil && strings.TrimSpace(string(out)) == "active"
}

// WhichExists returns true if `which` finds the binary in $PATH.
func WhichExists(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}

// AptInstall installs packages via apt-get with DEBIAN_FRONTEND=noninteractive.
// Idempotent — apt-get itself skips already-installed.
func AptInstall(ctx context.Context, packages ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, "apt-get", append([]string{
		"install", "-y", "-qq", "--no-install-recommends",
	}, packages...)...)
	cmd.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return out, fmt.Errorf("apt-get install %v: %w (%s)",
			packages, err, strings.TrimSpace(string(out)))
	}
	return out, nil
}

// AptUpdate refreshes the package index. Cheap to call repeatedly.
func AptUpdate(ctx context.Context) ([]byte, error) {
	cmd := exec.CommandContext(ctx, "apt-get", "update", "-qq")
	cmd.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
	return cmd.CombinedOutput() // ignore err — stale index is acceptable
}

// EnsureBackupDir creates a per-task backup directory and returns its path.
func EnsureBackupDir(taskID string) (string, error) {
	dir := filepath.Join(BackupDir, taskID+"-"+time.Now().Format("20060102-150405"))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return dir, nil
}

// BackupFile copies src into backupDir, preserving its basename.
// No-op if src doesn't exist — that's normal on a fresh server.
func BackupFile(src, backupDir string) error {
	in, err := os.Open(src)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	defer in.Close()
	dst := filepath.Join(backupDir, filepath.Base(src))
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

// RestoreFile copies backupDir/basename(orig) back to orig. Used on rollback.
func RestoreFile(orig, backupDir string) error {
	src := filepath.Join(backupDir, filepath.Base(orig))
	in, err := os.Open(src)
	if err != nil {
		if os.IsNotExist(err) {
			// Nothing to restore — the original didn't exist either.
			return os.Remove(orig)
		}
		return err
	}
	defer in.Close()
	out, err := os.Create(orig)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

// AtomicWrite writes data to path through a temp file + rename. Either
// the new content is fully in place or the old content survives — never
// a half-written file.
func AtomicWrite(path string, data []byte, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, path)
}

// CacheDir holds release archives the agent has fetched, so a follow-up
// deploy can skip the GitHub round-trip. Survives upgrades because it
// lives under /var/cache instead of /var/lib (which the uninstall path
// would wipe).
const CacheDir = "/var/cache/netguard-agent"

// EnsureCachedDownload is the cache-aware twin of DownloadAndVerify:
// it returns immediately if `dst` already exists with a matching sha256,
// otherwise it downloads via [DownloadAndVerify] (which will create the
// parent dir, atomic-rename on success, etc).
//
// Use this from any deploy path that knows the URL + pinned SHA — e.g.
// XrayDeploy's xray.zip fetch or the warmup runner.
func EnsureCachedDownload(ctx context.Context, url, dst, wantSha string) error {
	if existingShaMatches(dst, wantSha) {
		return nil
	}
	return DownloadAndVerify(ctx, url, dst, wantSha)
}

// existingShaMatches returns true iff `path` already exists and its
// sha256 equals wantSha (case-insensitive). On any read error we
// pessimistically return false so the caller re-downloads.
func existingShaMatches(path, wantSha string) bool {
	if wantSha == "" {
		return false
	}
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return false
	}
	return strings.EqualFold(hex.EncodeToString(h.Sum(nil)), wantSha)
}

// DownloadAndVerify GETs url, writes to dst, and checks sha256.
// Lets the caller pass an empty wantSha to skip verification — only
// acceptable for trusted internal mirrors. For pinned releases ALWAYS
// pass a known hash.
func DownloadAndVerify(ctx context.Context, url, dst, wantSha string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("GET %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("GET %s: HTTP %d", url, resp.StatusCode)
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	tmp := dst + ".dl"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	h := sha256.New()
	if _, err := io.Copy(io.MultiWriter(f, h), resp.Body); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	got := hex.EncodeToString(h.Sum(nil))
	if wantSha != "" && !strings.EqualFold(got, wantSha) {
		_ = os.Remove(tmp)
		return fmt.Errorf("sha256 mismatch: got %s want %s", got, wantSha)
	}
	return os.Rename(tmp, dst)
}

// WaitPortListening tries to connect to host:port up to maxAttempts times
// with 1s sleep between attempts. Used as a generic healthcheck after a
// service restart — if it never opens, the unit failed to bind.
func WaitPortListening(host string, port int, maxAttempts int) error {
	addr := fmt.Sprintf("%s:%d", host, port)
	for i := 0; i < maxAttempts; i++ {
		c, err := net.DialTimeout("tcp", addr, 1*time.Second)
		if err == nil {
			_ = c.Close()
			return nil
		}
		time.Sleep(1 * time.Second)
	}
	return fmt.Errorf("port %s did not open after %d attempts", addr, maxAttempts)
}

// EnsureBinaryInstalled checks if `which name` resolves; otherwise installs
// via apt. Use for prerequisites like curl/tar/iptables on fresh servers.
//
// On a freshly provisioned VPS the apt cache is empty — `apt-get install`
// then errors with "E: Unable to locate package". We retry once with an
// `apt-get update` in front to absorb that case without forcing every
// caller to remember to update first.
func EnsureBinaryInstalled(ctx context.Context, name, pkg string) error {
	if WhichExists(name) {
		return nil
	}
	if _, err := AptInstall(ctx, pkg); err != nil {
		if !strings.Contains(err.Error(), "Unable to locate package") {
			return err
		}
		if _, updErr := AptUpdate(ctx); updErr != nil {
			return fmt.Errorf("apt-get update (after %v): %w", err, updErr)
		}
		if _, err2 := AptInstall(ctx, pkg); err2 != nil {
			return err2
		}
	}
	return nil
}

// SysctlSet writes a key=value to /etc/sysctl.d/99-netguard-agent.conf
// (atomic) and applies via `sysctl -w`. Backs up the file if it existed.
func SysctlSet(ctx context.Context, backupDir, key, value string) error {
	const path = "/etc/sysctl.d/99-netguard-agent.conf"
	if backupDir != "" {
		if err := BackupFile(path, backupDir); err != nil {
			return err
		}
	}
	existing, _ := os.ReadFile(path)
	lines := strings.Split(string(existing), "\n")
	out := lines[:0]
	for _, l := range lines {
		if strings.HasPrefix(l, key+" =") || strings.HasPrefix(l, key+"=") {
			continue
		}
		out = append(out, l)
	}
	out = append(out, fmt.Sprintf("%s = %s", key, value))
	body := strings.Join(out, "\n")
	if !strings.HasSuffix(body, "\n") {
		body += "\n"
	}
	if err := AtomicWrite(path, []byte(body), 0o644); err != nil {
		return err
	}
	_, err := Run(ctx, "sysctl", "-w", fmt.Sprintf("%s=%s", key, value))
	return err
}
