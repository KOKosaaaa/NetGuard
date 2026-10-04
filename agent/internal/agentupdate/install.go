// Package agentupdate serializes and verifies self-update commits from both HTTP entry points.
package agentupdate

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

var updateGate = make(chan struct{}, 1)

func Install(ctx context.Context, target string, body io.Reader, wantSHA string) error {
	return install(ctx, target, body, wantSHA, smoke)
}

func install(ctx context.Context, target string, body io.Reader, wantSHA string, check func(context.Context, string) error) error {
	expected, err := hex.DecodeString(wantSHA)
	if err != nil || len(expected) != sha256.Size {
		return fmt.Errorf("valid sha256 is required")
	}
	select {
	case updateGate <- struct{}{}:
	case <-ctx.Done():
		return ctx.Err()
	}
	defer func() { <-updateGate }()
	if err := ctx.Err(); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(target), ".netguard-agent-update-*")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	hash := sha256.New()
	n, copyErr := io.Copy(io.MultiWriter(f, hash), body)
	closeErr := f.Close()
	if copyErr != nil {
		return fmt.Errorf("stream upload: %w", copyErr)
	}
	if closeErr != nil {
		return closeErr
	}
	if n < 1_000_000 {
		return fmt.Errorf("uploaded binary suspiciously small (%d bytes)", n)
	}
	if !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), wantSHA) {
		return fmt.Errorf("sha256 mismatch")
	}
	if err := os.Chmod(name, 0o755); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	checkCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	err = check(checkCtx, name)
	cancel()
	if err != nil {
		return fmt.Errorf("uploaded binary won't run here: %w", err)
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	// Copy the previous version instead of renaming it away: the executable
	// path must remain valid until the single final atomic rename succeeds.
	if err := backup(target); err != nil {
		return fmt.Errorf("backup current binary: %w", err)
	}
	if err := os.Rename(name, target); err != nil {
		return fmt.Errorf("swap binary: %w", err)
	}
	return nil
}

func backup(target string) error {
	old, err := os.Open(target)
	if err != nil {
		return err
	}
	defer old.Close()
	tmp, err := os.CreateTemp(filepath.Dir(target), ".netguard-agent-backup-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	_, err = io.Copy(tmp, old)
	closeErr := tmp.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err := os.Chmod(name, 0o755); err != nil {
		return err
	}
	return os.Rename(name, target+".bak")
}

func smoke(ctx context.Context, name string) error {
	// Output is diagnostic only; a corrupt executable must not allocate an
	// unbounded CombinedOutput buffer or hold the update lock indefinitely.
	out := &limitedOutput{}
	cmd := exec.CommandContext(ctx, name, "--version")
	cmd.Stdout, cmd.Stderr = out, out
	cmd.WaitDelay = time.Second
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("%w (%s)", err, strings.TrimSpace(string(out.bytes)))
	}
	return ctx.Err()
}

type limitedOutput struct{ bytes []byte }

func (w *limitedOutput) Write(p []byte) (int, error) {
	n := len(p)
	if keep := 4096 - len(w.bytes); keep > 0 {
		if len(p) > keep {
			p = p[:keep]
		}
		w.bytes = append(w.bytes, p...)
	}
	return n, nil
}
