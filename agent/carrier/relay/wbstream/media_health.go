package wbstream

import (
	"crypto/rand"
	"sync"
	"time"
	"whitelist-bypass/relay/tunnel"
)

const (
	mediaProbeInterval = 3 * time.Second
	mediaEchoMaxAge    = 8 * time.Second
	mediaFailureAfter  = 24 * time.Second
)

// Only a timely answer to our own challenge proves both directions of the
// media path. RTP, discovery, signaling and data ACKs do not refresh this clock.
type mediaHealth struct {
	mu          sync.Mutex
	lastSuccess time.Time
	confirmed   bool // negotiated by a matching echo; legacy publishers ignore probes
	pending     map[[tunnel.MediaNonceSize]byte]time.Time
}

func (h *mediaHealth) start(now time.Time) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.lastSuccess = now
	h.confirmed = false
	h.pending = make(map[[tunnel.MediaNonceSize]byte]time.Time)
}

func (h *mediaHealth) probe(now time.Time) ([]byte, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.lastSuccess.IsZero() {
		return nil, false
	}
	if h.confirmed && now.Sub(h.lastSuccess) >= mediaFailureAfter {
		return nil, true
	}
	for nonce, at := range h.pending {
		if now.Sub(at) > mediaEchoMaxAge {
			delete(h.pending, nonce)
		}
	}
	var nonce [tunnel.MediaNonceSize]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, false
	}
	h.pending[nonce] = now
	return append([]byte{tunnel.MediaPing}, nonce[:]...), false
}

func (h *mediaHealth) echo(payload []byte, now time.Time) bool {
	if len(payload) != 1+tunnel.MediaNonceSize || payload[0] != tunnel.MediaPong {
		return false
	}
	var nonce [tunnel.MediaNonceSize]byte
	copy(nonce[:], payload[1:])
	h.mu.Lock()
	defer h.mu.Unlock()
	at, ok := h.pending[nonce]
	if !ok {
		return false
	}
	delete(h.pending, nonce)
	if now.Before(at) || now.Sub(at) > mediaEchoMaxAge {
		return false
	}
	h.lastSuccess = now
	h.confirmed = true
	return true
}

func (s *Session) watchMediaHealth(tun *tunnel.MultiTrackTunnel) {
	s.health.start(time.Now())
	s.cfg.LogFn("[wb-health] probing media every 3s; 24s watchdog arms after peer echo")
	tick := time.NewTicker(mediaProbeInterval)
	defer tick.Stop()
	for {
		payload, failed := s.health.probe(time.Now())
		if failed {
			s.cfg.LogFn("[wb-health] no timely media echo for 24s; reconnecting carrier")
			s.Close()
			return
		}
		if payload != nil {
			tun.SendMediaControl(payload)
		}
		select {
		case <-s.done:
			return
		case <-tun.Done():
			return
		case <-tick.C:
		}
	}
}
