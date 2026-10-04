package agentupdate

import (
	"bytes"
	"context"
	"crypto/sha256"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// Optional Linux release gate: exercises actual old/new ELF smoke, installation
// and rollback in t.TempDir only. It never changes the live agent or systemd.
func TestReleaseELFUpgradeAndRollback(t *testing.T) {
	oldPath, newPath := os.Getenv("NETGUARD_QA_OLD_AGENT"), os.Getenv("NETGUARD_QA_NEW_AGENT")
	if oldPath == "" || newPath == "" {
		t.Skip("explicit old/new ELF fixtures required")
	}
	old, err := os.ReadFile(oldPath)
	if err != nil {
		t.Fatal(err)
	}
	next, err := os.ReadFile(newPath)
	if err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(t.TempDir(), "agent")
	if err := os.WriteFile(target, old, 0755); err != nil {
		t.Fatal(err)
	}
	version := func(want string) {
		t.Helper()
		out, err := exec.Command(target, "--version").CombinedOutput()
		if err != nil || strings.TrimSpace(string(out)) != want {
			t.Fatalf("version expected %s: %s %v", want, out, err)
		}
	}
	version("0.5.12")
	if err := Install(context.Background(), target, bytes.NewReader(next), fmt.Sprintf("%x", sha256.Sum256(next))); err != nil {
		t.Fatal(err)
	}
	version("0.5.13")
	backup, err := os.ReadFile(target + ".bak")
	if err != nil || !bytes.Equal(old, backup) {
		t.Fatal("old executable was not preserved exactly")
	}
	rollback := filepath.Join(filepath.Dir(target), "rollback")
	if err := os.WriteFile(rollback, backup, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(rollback, target); err != nil {
		t.Fatal(err)
	}
	version("0.5.12")
	restored, _ := os.ReadFile(target)
	if !bytes.Equal(old, restored) {
		t.Fatal("rollback did not restore exact ELF")
	}
}
