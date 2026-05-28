// Package storage persists the agent's small state (pair-token, bearer
// tokens, deploy snapshots) as a single JSON file on disk.
//
// Phase 0 uses JSON because the schema is tiny (~4 fields) and we want
// zero dependencies. Once deploy tasks land in phase 1 we will switch to
// modernc.org/sqlite — JSON-blob updates are not safe under concurrent
// task workers.
package storage

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// State is what we persist to disk. JSON-encoded.
type State struct {
	// PairToken is the one-shot string the bootstrap script wrote to a
	// well-known path so the Android app could read it via SSH. Cleared on
	// first successful /v1/auth/pair call.
	PairToken          string    `json:"pair_token,omitempty"`
	PairTokenCreatedAt time.Time `json:"pair_token_created_at,omitempty"`

	// Bearers is the set of long-lived per-device bearer tokens issued by
	// pair / rotate. Keyed by token string for O(1) lookup on every request.
	Bearers map[string]*Bearer `json:"bearers,omitempty"`
}

// Bearer is one device's long-lived auth token.
type Bearer struct {
	DeviceName string    `json:"device_name"`
	AppVersion string    `json:"app_version"`
	CreatedAt  time.Time `json:"created_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	LastSeenAt time.Time `json:"last_seen_at"`
}

// Store is a thread-safe JSON-backed state holder.
type Store struct {
	path string
	mu   sync.Mutex
	s    State
}

// Open loads state from path (or initializes an empty one if missing).
func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	st := &Store{path: path, s: State{Bearers: map[string]*Bearer{}}}
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return st, nil
		}
		return nil, err
	}
	if len(data) == 0 {
		return st, nil
	}
	if err := json.Unmarshal(data, &st.s); err != nil {
		return nil, fmt.Errorf("parse %s: %w", path, err)
	}
	if st.s.Bearers == nil {
		st.s.Bearers = map[string]*Bearer{}
	}
	return st, nil
}

// Get returns a snapshot of the state. Callers must not mutate the returned
// value — they must call Update for that.
func (s *Store) Get() State {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.s
}

// Update applies fn to the state and persists the result. fn runs under the
// store lock so concurrent updates are serialized.
func (s *Store) Update(fn func(*State)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	fn(&s.s)
	return s.persistLocked()
}

// LookupBearer returns (bearer, true) if token is valid and unexpired.
// Side-effect: bumps LastSeenAt.
func (s *Store) LookupBearer(token string) (*Bearer, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, ok := s.s.Bearers[token]
	if !ok {
		return nil, false
	}
	if time.Now().After(b.ExpiresAt) {
		return nil, false
	}
	b.LastSeenAt = time.Now()
	_ = s.persistLocked() // best-effort; failure here doesn't deny the request
	return b, true
}

func (s *Store) persistLocked() error {
	data, err := json.MarshalIndent(&s.s, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}
