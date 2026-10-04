package agentupdate

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func fixture(t *testing.T) (string, []byte, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "agent")
	if err := os.WriteFile(path, []byte("previous version"), 0o755); err != nil {
		t.Fatal(err)
	}
	payload := bytes.Repeat([]byte("verified-candidate"), 65000)
	h := sha256.Sum256(payload)
	return path, payload, hex.EncodeToString(h[:])
}

func TestConcurrentUpdateCannotReplaceVerifiedCandidateOrConsumeSecondBody(t *testing.T) {
	path, payload, hash := fixture(t)
	inSmoke, release := make(chan struct{}), make(chan struct{})
	done := make(chan error, 1)
	go func() {
		done <- install(context.Background(), path, bytes.NewReader(payload), hash, func(_ context.Context, candidate string) error {
			close(inSmoke)
			<-release
			data, err := os.ReadFile(candidate)
			if err != nil || !bytes.Equal(data, payload) {
				return errors.New("candidate mutated after hash")
			}
			return nil
		})
	}()
	<-inSmoke
	ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
	defer cancel()
	reader := &forbiddenReader{t}
	err := install(ctx, path, reader, hash, func(context.Context, string) error { t.Error("second update entered smoke"); return nil })
	close(release)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("blocked caller: %v", err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	installed, _ := os.ReadFile(path)
	backup, _ := os.ReadFile(path + ".bak")
	if !bytes.Equal(installed, payload) || string(backup) != "previous version" {
		t.Fatal("installed or backup bytes differ")
	}
}

type forbiddenReader struct{ t *testing.T }

func (r *forbiddenReader) Read([]byte) (int, error) {
	r.t.Error("second upload consumed before ownership")
	return 0, errors.New("unexpected read")
}

func TestHashAndSmokeFailurePreserveLiveBinaryAndCleanTemp(t *testing.T) {
	for _, kind := range []string{"hash", "smoke"} {
		t.Run(kind, func(t *testing.T) {
			path, payload, hash := fixture(t)
			if kind == "hash" {
				if hash[0] == '0' {
					hash = "1" + hash[1:]
				} else {
					hash = "0" + hash[1:]
				}
			}
			smokeCalls := 0
			err := install(context.Background(), path, bytes.NewReader(payload), hash, func(context.Context, string) error { smokeCalls++; return errors.New("unexecutable candidate") })
			if kind == "hash" && smokeCalls != 0 {
				t.Fatal("hash mismatch still executed candidate")
			}
			if kind == "smoke" && smokeCalls != 1 {
				t.Fatal("smoke fixture was not reached")
			}
			if err == nil {
				t.Fatal("invalid binary accepted")
			}
			current, _ := os.ReadFile(path)
			files, _ := os.ReadDir(filepath.Dir(path))
			if string(current) != "previous version" || len(files) != 1 {
				t.Fatalf("live changed or temp leaked: %v", files)
			}
		})
	}
}

func TestMain(m *testing.M) {
	if os.Getenv("NETGUARD_QA_UPDATE_CHILD") == "hang" {
		time.Sleep(time.Hour)
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func TestSmokeActuallyKillsHungExecutable(t *testing.T) {
	t.Setenv("NETGUARD_QA_UPDATE_CHILD", "hang")
	self, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	start := time.Now()
	if err := smoke(ctx, self); err == nil {
		t.Fatal("hung binary passed")
	}
	if time.Since(start) > 2*time.Second {
		t.Fatal("smoke process not bounded")
	}
}
