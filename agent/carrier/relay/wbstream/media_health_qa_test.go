package wbstream

import (
	"bytes"
	"testing"
	"time"

	"whitelist-bypass/relay/tunnel"
)

func qaMediaPong(p []byte) []byte {
	r := append([]byte(nil), p...)
	r[0] = tunnel.MediaPong
	return r
}

func TestWBQAMediaIdleAndTransientLossDoNotRestart(t *testing.T) {
	base := time.Unix(1000, 0)
	var h mediaHealth
	h.start(base)
	for sec := 0; sec <= 120; sec += 3 {
		now := base.Add(time.Duration(sec) * time.Second)
		p, failed := h.probe(now)
		if failed || len(p) != 17 {
			t.Fatalf("healthy idle/transient loss failed at %ds", sec)
		}
		// No application traffic at all, with a ten-second network outage.
		if sec >= 30 && sec < 40 {
			continue
		}
		if !h.echo(qaMediaPong(p), now.Add(400*time.Millisecond)) {
			t.Fatal("timely end-to-end response rejected")
		}
	}
}

func TestWBQAMediaSilenceExpiresAt24SecondsAndPendingIsBounded(t *testing.T) {
	base := time.Unix(1000, 0)
	var h mediaHealth
	h.start(base)
	initial, _ := h.probe(base)
	if !h.echo(qaMediaPong(initial), base) {
		t.Fatal("watchdog capability was not negotiated")
	}
	for sec := 0; sec <= 24; sec += 3 {
		p, failed := h.probe(base.Add(time.Duration(sec) * time.Second))
		if failed != (sec == 24) {
			t.Fatalf("silence deadline at %ds: failed=%v", sec, failed)
		}
		if failed && p != nil {
			t.Fatal("expired health emitted another probe")
		}
		if len(h.pending) > 3 {
			t.Fatal("expired challenges accumulate without bound")
		}
	}
}

func TestWBQALegacyPeerDoesNotEnterTwentyFourSecondReconnectLoop(t *testing.T) {
	base := time.Unix(1000, 0)
	var h mediaHealth
	h.start(base)
	for sec := 0; sec <= 180; sec += 3 {
		p, failed := h.probe(base.Add(time.Duration(sec) * time.Second))
		if failed || len(p) != 17 || h.confirmed {
			t.Fatalf("legacy peer without capability echo was marked failed/confirmed at %ds", sec)
		}
		if len(h.pending) > 3 {
			t.Fatal("legacy capability probes leaked pending nonces")
		}
	}
	// Discovery/normal ACK cannot negotiate capability. A genuine late-added
	// upgraded peer may negotiate using a CURRENT nonce, then loses the legacy
	// exemption permanently for the rest of this session.
	p, _ := h.probe(base.Add(183 * time.Second))
	if !h.echo(qaMediaPong(p), base.Add(184*time.Second)) || !h.confirmed {
		t.Fatal("current capability echo did not arm health")
	}
	if _, failed := h.probe(base.Add(208 * time.Second)); !failed {
		t.Fatal("confirmed peer silently fell back to legacy after silence")
	}
	h.start(base.Add(209 * time.Second))
	if h.confirmed {
		t.Fatal("new session inherited previous capability")
	}
	if h.echo(qaMediaPong(p), base.Add(210*time.Second)) || h.confirmed {
		t.Fatal("old-session echo re-armed replacement session")
	}
}

func TestWBQAMediaEchoRejectsReplayExpiredMalformedAndPreviousSession(t *testing.T) {
	base := time.Unix(1000, 0)
	var h mediaHealth
	h.start(base)
	p, _ := h.probe(base)
	pong := qaMediaPong(p)
	for _, bad := range [][]byte{nil, p, pong[:16], append(append([]byte(nil), pong...), 0), append([]byte{tunnel.MediaPong}, bytes.Repeat([]byte{255}, 16)...)} {
		if h.echo(bad, base.Add(time.Second)) {
			t.Fatal("invalid/unsolicited response proved liveness")
		}
	}
	if !h.echo(pong, base.Add(8*time.Second)) || h.echo(pong, base.Add(9*time.Second)) {
		t.Fatal("echo age boundary/replay validation failed")
	}
	p, _ = h.probe(base.Add(9 * time.Second))
	if h.echo(qaMediaPong(p), base.Add(17*time.Second+time.Nanosecond)) {
		t.Fatal("response older than eight seconds revived health")
	}
	p, _ = h.probe(base.Add(18 * time.Second))
	h.start(base.Add(19 * time.Second))
	if h.echo(qaMediaPong(p), base.Add(20*time.Second)) {
		t.Fatal("previous-session nonce revived current session")
	}
}

func TestWBQAMediaAuthenticatedSessionRejectsFalseProof(t *testing.T) {
	client, server, stranger := qaRecipientSession(t, true), qaRecipientSession(t, false), qaRecipientSession(t, false)
	server.cfg.Obfuscator.SetRecipientEpoch(client.cfg.Obfuscator.LocalEpoch(), true)
	stranger.cfg.Obfuscator.SetRecipientEpoch(client.cfg.Obfuscator.LocalEpoch(), true)
	var track, strangerTrack uint32
	if !client.handleCarrierFrame(server.cfg.Obfuscator.EncodeData([]byte{0x13, 1, 0, 128}), &track) {
		t.Fatal("could not establish authenticated server identity")
	}
	base := time.Now().Add(-time.Second)
	client.health.start(base)
	ping, _ := client.health.probe(base)
	pong := qaMediaPong(ping)
	good := server.cfg.Obfuscator.EncodeData(pong)
	tampered := append([]byte(nil), good...)
	tampered[len(tampered)-1] ^= 1
	bad := [][]byte{
		server.cfg.Obfuscator.EncodeDataFor(pong, 0),
		server.cfg.Obfuscator.EncodeDataFor(pong, 1<<32),
		server.cfg.Obfuscator.EncodeDataFor(pong, 1<<32|uint64(stranger.cfg.Obfuscator.LocalEpoch())),
		tampered,
		server.cfg.Obfuscator.EncodeKeepalive(),
		server.cfg.Obfuscator.EncodeData([]byte{0x13, 1, 0, 128}),
		server.cfg.Obfuscator.EncodeData(append([]byte{tunnel.MediaPong}, bytes.Repeat([]byte{254}, 16)...)),
	}
	for _, frame := range bad {
		client.handleCarrierFrame(frame, &track)
		if !client.health.lastSuccess.Equal(base) || client.health.confirmed {
			t.Fatal("wrong address, raw hello, malformed or unsolicited echo refreshed health")
		}
	}
	client.handleCarrierFrame(stranger.cfg.Obfuscator.EncodeData(pong), &strangerTrack)
	if !client.health.lastSuccess.Equal(base) || client.health.confirmed {
		t.Fatal("unaccepted authenticated peer refreshed health")
	}
	if !client.handleCarrierFrame(good, &track) || !client.health.lastSuccess.After(base) || !client.health.confirmed {
		t.Fatal("valid accepted-peer echo failed to prove health")
	}
	acceptedAt := client.health.lastSuccess
	client.handleCarrierFrame(good, &track)
	if !client.health.lastSuccess.Equal(acceptedAt) {
		t.Fatal("replayed encrypted echo refreshed health twice")
	}
}
