package wbstream

import (
	"os"
	"testing"
	"time"
	"whitelist-bypass/relay/tunnel"
)

func TestCarrierCameraAndExtraTrackPreserveConnection(t *testing.T) {
	if os.Getenv("WLB_VALID_VP8_TUNNEL") == "" {
		t.Skip("requires carrier mode")
	}
	receiver, _ := tunnel.NewTunnelObfuscator([]byte("room"))
	sender, _ := tunnel.NewTunnelObfuscator([]byte("room"))
	camera, _ := tunnel.NewTunnelObfuscator([]byte("camera"))
	s := NewSession(SessionConfig{Obfuscator: receiver})
	resets := 0
	s.OnPeerRestart = func() { resets++ }
	var primary, extra, browser uint32
	if !s.handleCarrierFrame(sender.EncodeData([]byte("first")), &primary) {
		t.Fatal("first peer rejected")
	}
	for i := 0; i < 100; i++ {
		s.handleCarrierFrame(camera.EncodeData([]byte("camera")), &browser)
		s.handleCarrierFrame(camera.EncodeKeepalive(), &browser)
		if !s.handleCarrierFrame(sender.EncodeData([]byte("second track")), &extra) {
			t.Fatal("same peer additional track rejected")
		}
	}
	if resets != 0 || browser != 0 {
		t.Fatal("camera/additional track reset working connection")
	}
}

func TestCarrierPeerReplacementRequiresDepartureOrIdle(t *testing.T) {
	now := time.Now()
	g := carrierPeerGate{}
	var first, replacement uint32
	g.accept(1, &first, now)
	if ok, _ := g.accept(2, &replacement, now.Add(time.Second)); ok {
		t.Fatal("live peer displaced")
	}
	g.closeTrack(first)
	if ok, reset := g.accept(2, &replacement, now.Add(2*time.Second)); !ok || !reset {
		t.Fatal("departed peer not replaced")
	}
	var later uint32
	if ok, reset := g.accept(3, &later, now.Add(33*time.Second)); !ok || !reset {
		t.Fatal("stale peer not replaced")
	}
	if ok, _ := g.accept(2, &replacement, now.Add(34*time.Second)); ok {
		t.Fatal("old track displaced replacement")
	}
}
