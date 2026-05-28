// Package auth handles pair-token issuance and bearer-token verification.
//
// Lifecycle:
//  1. At startup the agent ensures a one-shot pair-token exists on disk
//     (PairTokenPath, mode 0600). The bootstrap caller (Android via SSH)
//     reads it, exchanges it for a bearer through POST /v1/auth/pair, and
//     then deletes the file.
//  2. The bearer lives in state.json until /v1/auth/revoke or expiry. Each
//     authenticated request runs through Verify which bumps LastSeenAt.
//  3. /v1/auth/rotate issues a new bearer when the current one has < 7d
//     left, and the old token gets a 1h grace period.
package auth

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
)

const (
	// PairTokenTTL bounds how long the bootstrap window stays open. The
	// Android app should pair within this; otherwise the operator must
	// re-run bootstrap.
	PairTokenTTL = 30 * time.Minute
	// BearerTTL is the lifetime of a fresh bearer. Rotation kicks in
	// 7 days before this elapses.
	BearerTTL = 90 * 24 * time.Hour
	// PairTokenPath is where we drop the one-shot token. Mode 0600 so only
	// root can read it. The bootstrap script in the same trust boundary
	// reads + deletes after a successful pair.
	PairTokenPath = "/var/lib/netguard-agent/pair-token.txt"
)

// Errors returned by this package — exposed so the API layer can map them
// to specific HTTP responses rather than a generic 500.
var (
	ErrPairTokenMissing = errors.New("pair token not set")
	ErrPairTokenWrong   = errors.New("pair token does not match")
	ErrPairTokenExpired = errors.New("pair token expired")
	ErrBearerMissing    = errors.New("missing bearer")
	ErrBearerInvalid    = errors.New("invalid or expired bearer")
)

// Manager wraps a storage.Store with auth-specific operations.
type Manager struct {
	store *storage.Store
}

func New(s *storage.Store) *Manager { return &Manager{store: s} }

// EnsurePairToken writes a fresh pair-token to disk if there isn't one
// in state yet. Called on agent startup.
func (m *Manager) EnsurePairToken() (string, error) {
	st := m.store.Get()
	if st.PairToken != "" && time.Since(st.PairTokenCreatedAt) < PairTokenTTL {
		// Existing token still valid — re-write file in case it was deleted.
		if err := writePairFile(st.PairToken); err != nil {
			return "", err
		}
		return st.PairToken, nil
	}

	tok, err := randomToken(32)
	if err != nil {
		return "", err
	}
	if err := m.store.Update(func(s *storage.State) {
		s.PairToken = tok
		s.PairTokenCreatedAt = time.Now()
	}); err != nil {
		return "", err
	}
	if err := writePairFile(tok); err != nil {
		return "", err
	}
	return tok, nil
}

// Pair exchanges a pair-token for a fresh bearer. Side-effects: deletes the
// pair token (one-shot) and writes the new bearer to state.
func (m *Manager) Pair(submittedToken, deviceName, appVersion string) (*storage.Bearer, string, error) {
	st := m.store.Get()
	if st.PairToken == "" {
		return nil, "", ErrPairTokenMissing
	}
	if time.Since(st.PairTokenCreatedAt) > PairTokenTTL {
		return nil, "", ErrPairTokenExpired
	}
	if !constantTimeEq(submittedToken, st.PairToken) {
		return nil, "", ErrPairTokenWrong
	}
	bearer, err := randomToken(32)
	if err != nil {
		return nil, "", err
	}
	b := &storage.Bearer{
		DeviceName: deviceName,
		AppVersion: appVersion,
		CreatedAt:  time.Now(),
		ExpiresAt:  time.Now().Add(BearerTTL),
		LastSeenAt: time.Now(),
	}
	if err := m.store.Update(func(s *storage.State) {
		s.PairToken = ""
		s.PairTokenCreatedAt = time.Time{}
		s.Bearers[bearer] = b
	}); err != nil {
		return nil, "", err
	}
	_ = os.Remove(PairTokenPath) // best-effort; SSH bootstrap should also rm
	return b, bearer, nil
}

// Verify returns the bearer record if token is recognised and unexpired.
func (m *Manager) Verify(token string) (*storage.Bearer, error) {
	if token == "" {
		return nil, ErrBearerMissing
	}
	b, ok := m.store.LookupBearer(token)
	if !ok {
		return nil, ErrBearerInvalid
	}
	return b, nil
}

// --- helpers --------------------------------------------------------------

func randomToken(nBytes int) (string, error) {
	buf := make([]byte, nBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("rand: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

func writePairFile(tok string) error {
	if err := os.MkdirAll(filepath.Dir(PairTokenPath), 0o700); err != nil {
		return err
	}
	return os.WriteFile(PairTokenPath, []byte(tok+"\n"), 0o600)
}

func constantTimeEq(a, b string) bool {
	// crypto/subtle.ConstantTimeCompare without the import (one call site).
	if len(a) != len(b) {
		return false
	}
	var v byte
	for i := 0; i < len(a); i++ {
		v |= a[i] ^ b[i]
	}
	return v == 0
}
