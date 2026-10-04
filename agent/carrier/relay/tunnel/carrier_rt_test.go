package tunnel

import (
	"bytes"
	"testing"
)

func TestCarrierRoundtrip(t *testing.T) {
	if !carrierMode {
		t.Skip("carrier off (set WLB_VALID_VP8_TUNNEL=1)")
	}
	enc, _ := NewTunnelObfuscator([]byte("secret-token"))
	dec, _ := NewTunnelObfuscator([]byte("secret-token"))
	// force distinct epochs
	for i := 0; i < 100; i++ {
		payload := bytes.Repeat([]byte{byte(i)}, 1200)
		frame := enc.EncodeData(payload)
		if frame == nil {
			t.Fatalf("encode nil at %d", i)
		}
		r := dec.Decode(frame)
		if !r.HasFrame || r.Keepalive {
			t.Fatalf("frame %d: hasFrame=%v keepalive=%v len=%d head=%x", i, r.HasFrame, r.Keepalive, len(frame), frame[:6])
		}
		if !bytes.Equal(r.Payload, payload) {
			t.Fatalf("frame %d payload mismatch got %d want %d", i, len(r.Payload), len(payload))
		}
	}
	// keepalive roundtrip
	ka := enc.EncodeKeepalive()
	r := dec.Decode(ka)
	if !r.HasFrame || !r.Keepalive {
		t.Fatalf("keepalive decode: hasFrame=%v keepalive=%v", r.HasFrame, r.Keepalive)
	}
	t.Logf("OK: 100 data frames + keepalive roundtrip")
}
