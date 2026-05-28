// Package auth handles pair-token issuance, bearer verification, rotation,
// and revocation.
//
// Lifecycle:
//  1. On startup the agent ensures a one-shot pair-token exists in the kv
//     table AND on disk at PairTokenPath (mode 0600). The bootstrap caller
//     (Android via SSH) reads the file, exchanges it for a bearer via
//     POST /v1/auth/pair, and deletes the file.
//  2. The bearer lives in the bearers table until /v1/auth/revoke,
//     /v1/auth/rotate, or natural expiry. Each authenticated request runs
//     through Verify which bumps last_seen_at.
//  3. /v1/auth/rotate issues a new bearer and revokes the old one when
//     the current bearer has < RotateWindow time left. The Android client
//     can also call rotate explicitly via header X-Bearer-Rotate.
package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
)

const (
	PairTokenTTL  = 30 * time.Minute
	BearerTTL     = 90 * 24 * time.Hour
	RotateWindow  = 7 * 24 * time.Hour // start rotating this long before expiry
	PairTokenPath = "/var/lib/netguard-agent/pair-token.txt"
	kvKeyPair     = "pair_token"
	kvKeyPairTime = "pair_token_created_at"
)

var (
	ErrPairTokenMissing = errors.New("pair token not set")
	ErrPairTokenWrong   = errors.New("pair token does not match")
	ErrPairTokenExpired = errors.New("pair token expired")
	ErrBearerMissing    = errors.New("missing bearer")
	ErrBearerInvalid    = errors.New("invalid or expired bearer")
)

type Manager struct {
	db *storage.DB
}

func New(db *storage.DB) *Manager { return &Manager{db: db} }

// EnsurePairToken writes a fresh pair-token to disk + DB if no valid one
// is outstanding. Called at agent startup.
func (m *Manager) EnsurePairToken() (string, error) {
	existing, ok, err := m.db.KVGet(kvKeyPair)
	if err != nil {
		return "", err
	}
	if ok {
		tsStr, _, _ := m.db.KVGet(kvKeyPairTime)
		ts, _ := time.Parse(time.RFC3339Nano, tsStr)
		if time.Since(ts) < PairTokenTTL {
			// Existing token still valid — rewrite the file in case it was rm'd.
			if err := writePairFile(existing); err != nil {
				return "", err
			}
			return existing, nil
		}
	}
	tok, err := randomToken(32)
	if err != nil {
		return "", err
	}
	now := time.Now()
	if err := m.db.KVPut(kvKeyPair, tok); err != nil {
		return "", err
	}
	if err := m.db.KVPut(kvKeyPairTime, now.Format(time.RFC3339Nano)); err != nil {
		return "", err
	}
	if err := writePairFile(tok); err != nil {
		return "", err
	}
	return tok, nil
}

// Pair exchanges a pair-token for a fresh bearer. One-shot: success deletes
// the pair token from both DB and disk.
func (m *Manager) Pair(submitted, deviceName, appVersion string) (*storage.Bearer, error) {
	current, ok, err := m.db.KVGet(kvKeyPair)
	if err != nil {
		return nil, err
	}
	if !ok || current == "" {
		return nil, ErrPairTokenMissing
	}
	tsStr, _, _ := m.db.KVGet(kvKeyPairTime)
	ts, _ := time.Parse(time.RFC3339Nano, tsStr)
	if time.Since(ts) > PairTokenTTL {
		return nil, ErrPairTokenExpired
	}
	if subtle.ConstantTimeCompare([]byte(submitted), []byte(current)) != 1 {
		return nil, ErrPairTokenWrong
	}

	bearer, err := randomToken(32)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	b := &storage.Bearer{
		Token:      bearer,
		DeviceName: deviceName,
		AppVersion: appVersion,
		CreatedAt:  now,
		ExpiresAt:  now.Add(BearerTTL),
		LastSeenAt: now,
	}
	if err := m.db.InsertBearer(b); err != nil {
		return nil, fmt.Errorf("insert bearer: %w", err)
	}
	if err := m.db.KVDelete(kvKeyPair); err != nil {
		return nil, err
	}
	_ = m.db.KVDelete(kvKeyPairTime)
	_ = os.Remove(PairTokenPath) // best-effort
	return b, nil
}

// Verify returns the bearer record if known and unexpired.
func (m *Manager) Verify(token string) (*storage.Bearer, error) {
	if token == "" {
		return nil, ErrBearerMissing
	}
	b, err := m.db.LookupBearer(token)
	if err != nil {
		return nil, ErrBearerInvalid
	}
	return b, nil
}

// Rotate issues a new bearer when the current one is close to expiry, and
// revokes the old one. Returns (nil, nil) if rotation is not needed yet.
func (m *Manager) Rotate(currentToken string) (*storage.Bearer, error) {
	b, err := m.db.LookupBearer(currentToken)
	if err != nil {
		return nil, ErrBearerInvalid
	}
	if time.Until(b.ExpiresAt) > RotateWindow {
		return nil, nil // not time yet
	}
	newTok, err := randomToken(32)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	nb := &storage.Bearer{
		Token:      newTok,
		DeviceName: b.DeviceName,
		AppVersion: b.AppVersion,
		CreatedAt:  now,
		ExpiresAt:  now.Add(BearerTTL),
		LastSeenAt: now,
	}
	if err := m.db.InsertBearer(nb); err != nil {
		return nil, err
	}
	if err := m.db.RevokeBearer(currentToken); err != nil {
		return nil, err
	}
	return nb, nil
}

// Revoke marks a bearer as revoked. Called from /v1/auth/revoke when the
// user removes the server from the Android app.
func (m *Manager) Revoke(token string) error {
	return m.db.RevokeBearer(token)
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
