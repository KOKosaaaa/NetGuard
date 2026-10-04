package wbstream

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"whitelist-bypass/relay/livekit"
)

type qaLocalRoomTransport struct {
	target *url.URL
	base   http.RoundTripper
}

func (r qaLocalRoomTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	clone := req.Clone(req.Context())
	u := *req.URL
	u.Scheme, u.Host = r.target.Scheme, r.target.Host
	clone.URL = &u
	clone.Host = r.target.Host
	return r.base.RoundTrip(clone)
}

func TestQANewParticipantNeverKicksExistingActivePeer(t *testing.T) {
	for _, authenticated := range []bool{false, true} {
		name := "guest"
		if authenticated {
			name = "authenticated owner"
		}
		t.Run(name, func(t *testing.T) {
			var deletes, puts atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.Method {
				case http.MethodDelete:
					deletes.Add(1)
				case http.MethodPut:
					puts.Add(1)
				}
				w.WriteHeader(http.StatusNoContent)
			}))
			defer server.Close()
			target, _ := url.Parse(server.URL)
			previous := http.DefaultClient
			http.DefaultClient = &http.Client{Transport: qaLocalRoomTransport{target, http.DefaultTransport}, Timeout: time.Second}
			defer func() { http.DefaultClient = previous }()
			promotionDone := make(chan struct{}, 2)
			cfg := SessionConfig{LogFn: func(format string, _ ...any) {
				if strings.Contains(format, "promoted to moderator") || strings.Contains(format, "promote failed") {
					promotionDone <- struct{}{}
				}
			}}
			if authenticated {
				cfg.AccessToken = "qa-local-only"
				cfg.RoomID = "qa-room"
			}
			s := NewSession(cfg)
			s.lk = livekit.NewClient(livekit.Config{})
			s.peersBySID = map[string]peerEntry{"old-active": {sid: "old-active", identity: "existing-user", state: livekit.ParticipantStateActive, firstSeen: time.Now().Add(-24 * time.Hour), promoted: true}}
			newPeer := livekit.ParticipantInfo{SID: "new-active", Identity: "new-user", State: livekit.ParticipantStateActive}
			s.onParticipantUpdate([]livekit.ParticipantInfo{newPeer})
			if authenticated {
				select {
				case <-promotionDone:
				case <-time.After(2 * time.Second):
					t.Fatal("new peer promotion did not finish")
				}
			}
			// Repeated snapshots and another non-active newcomer must not evict
			// either existing active participant or repeatedly promote the same ID.
			s.onParticipantUpdate([]livekit.ParticipantInfo{newPeer, {SID: "joining", Identity: "joining-user", State: livekit.ParticipantStateJoining}})
			s.mu.Lock()
			old, oldOK := s.peersBySID["old-active"]
			_, newOK := s.peersBySID["new-active"]
			kicked := len(s.kickedSIDs)
			s.mu.Unlock()
			if deletes.Load() != 0 || !oldOK || !newOK || old.state != livekit.ParticipantStateActive || kicked != 0 {
				t.Fatalf("newcomer evicted active peer: DELETE=%d old=%v new=%v kicked=%d", deletes.Load(), oldOK, newOK, kicked)
			}
			wantPuts := int32(0)
			if authenticated {
				wantPuts = 1
			}
			if puts.Load() != wantPuts {
				t.Fatalf("unexpected permission calls: %d want %d", puts.Load(), wantPuts)
			}
			s.onParticipantUpdate([]livekit.ParticipantInfo{{SID: "new-active", State: livekit.ParticipantStateDisconnected}})
			s.mu.Lock()
			_, newOK = s.peersBySID["new-active"]
			_, oldOK = s.peersBySID["old-active"]
			s.mu.Unlock()
			if newOK || !oldOK || deletes.Load() != 0 {
				t.Fatal("explicit disconnect removed an unrelated active peer or made a DELETE")
			}
		})
	}
}
