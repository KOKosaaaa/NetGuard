package wbstream

import (
	"sync"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/tunnel"
)

func qaWatchedCarrier(t *testing.T, joiner bool) (*Session, *tunnel.MultiTrackTunnel, *tunnel.VP8DataTunnel, *tunnel.VP8DataTunnel) {
	t.Helper()
	obf, err := tunnel.NewTunnelObfuscator([]byte("session-close-qa"))
	if err != nil {
		t.Fatal(err)
	}
	var children []*tunnel.VP8DataTunnel
	for _, id := range []string{"camera", "screen"} {
		track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, id, "qa")
		if err != nil {
			t.Fatal(err)
		}
		children = append(children, tunnel.NewVP8DataTunnel(track, obf, func(string, ...any) {}))
	}
	m := tunnel.NewMultiTrackTunnel(children)
	s := NewSession(SessionConfig{IsJoiner: joiner, TunnelMode: TunnelModeVideo, Obfuscator: obf, LogFn: func(string, ...any) {}})
	s.vp8tun = m
	// This replaces the carrier's public close callback, exactly as the real
	// bridge does. Recovery must observe independent lifecycle, not that slot.
	bridge := tunnel.NewRelayBridge(m, "joiner", 4096, func(string, ...any) {})
	bridge.SetPersistentListener(true)
	m.Start(24, 1)
	t.Cleanup(func() { s.Close(); bridge.Close(); m.Stop() })
	return s, m, children[0], children[1]
}

func TestWBQACarrierFatalClosesSessionAfterBridgeReplacesOnClose(t *testing.T) {
	s, m, camera, screen := qaWatchedCarrier(t, true)
	s.MarkConfigAcked()
	go s.watchCarrier(m, time.Second)
	screen.Stop()
	select {
	case <-m.Done():
		t.Fatal("screenshare close killed healthy camera")
	case <-s.Done():
		t.Fatal("screenshare close killed session")
	default:
	}
	camera.Stop() // fatal control-stream Stop reaches this exact child path
	select {
	case <-m.Done():
	case <-time.After(time.Second):
		t.Fatal("fatal camera stop did not terminate carrier")
	}
	select {
	case <-s.Done():
	case <-time.After(time.Second):
		t.Fatal("dead carrier left session signaling alive, preventing reconnect")
	}
	// Concurrent normal teardown must be idempotent with watcher-triggered Close.
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); s.Close(); m.Stop(); camera.Stop() }()
	}
	wg.Wait()
}

func TestWBQAInitialConfigDeadlineEndsUnconfirmedSession(t *testing.T) {
	s, m, _, _ := qaWatchedCarrier(t, true)
	go s.watchCarrier(m, 40*time.Millisecond)
	select {
	case <-s.Done():
	case <-time.After(time.Second):
		t.Fatal("missing configuration ACK did not end session")
	}
	select {
	case <-m.Done():
	case <-time.After(time.Second):
		t.Fatal("configuration timeout leaked running carrier")
	}
}

func TestWBQAConfigAckDisarmsDeadlineButNotLaterCarrierFailure(t *testing.T) {
	s, m, camera, _ := qaWatchedCarrier(t, true)
	if !s.MarkConfigAcked() {
		t.Fatal("initial ACK not accepted")
	}
	go s.watchCarrier(m, 30*time.Millisecond)
	select {
	case <-s.Done():
		t.Fatal("acknowledged session ended at stale deadline")
	case <-time.After(100 * time.Millisecond):
	}
	camera.Stop()
	select {
	case <-s.Done():
	case <-time.After(time.Second):
		t.Fatal("ACK disabled subsequent fatal-carrier recovery")
	}
}

func TestWBQACreatorDoesNotRequireJoinerConfigurationAck(t *testing.T) {
	s, m, camera, _ := qaWatchedCarrier(t, false)
	go s.watchCarrier(m, 30*time.Millisecond)
	select {
	case <-s.Done():
		t.Fatal("creator waited for an ACK only joiners receive")
	case <-time.After(100 * time.Millisecond):
	}
	camera.Stop()
	select {
	case <-s.Done():
	case <-time.After(time.Second):
		t.Fatal("creator fatal carrier was not propagated")
	}
}
