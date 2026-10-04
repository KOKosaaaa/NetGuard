package tunnel

import (
	"bytes"
	"testing"
)

func TestCarrierUnauthenticatedFramesCannotClaimPeer(t *testing.T) {
	if !carrierMode {
		t.Skip("requires carrier mode")
	}
	receiver, _ := NewTunnelObfuscator([]byte("room"))
	unrelated, _ := NewTunnelObfuscator([]byte("different room"))
	sender, _ := NewTunnelObfuscator([]byte("room"))
	for _, frame := range [][]byte{unrelated.EncodeKeepalive(), unrelated.EncodeData([]byte("camera data"))} {
		receiver.Decode(frame)
		if receiver.PeerEpoch() != 0 || receiver.lockedEpoch.Load() != 0 {
			t.Fatal("unauthenticated frame claimed peer state")
		}
	}
	want := []byte("first legitimate packet")
	if got := receiver.Decode(sender.EncodeData(want)); !bytes.Equal(got.Payload, want) {
		t.Fatal("legitimate peer shadowed by unauthenticated traffic")
	}
}

func TestCarrierCameraTrafficDoesNotDisplaceActivePeer(t *testing.T) {
	if !carrierMode {
		t.Skip("requires carrier mode")
	}
	receiver, _ := NewTunnelObfuscator([]byte("room"))
	sender, _ := NewTunnelObfuscator([]byte("room"))
	camera, _ := NewTunnelObfuscator([]byte("camera"))
	receiver.Decode(sender.EncodeData([]byte("hello")))
	for i := 0; i < 100; i++ {
		receiver.Decode(camera.EncodeKeepalive())
		receiver.Decode(camera.EncodeData([]byte("not tunnel data")))
		got := receiver.Decode(sender.EncodeData([]byte("payload")))
		if got.PeerRestart || !bytes.Equal(got.Payload, []byte("payload")) {
			t.Fatal("unrelated video disrupted active carrier")
		}
	}
}
