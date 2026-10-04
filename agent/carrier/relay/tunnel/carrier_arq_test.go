package tunnel

import (
	"bytes"
	"fmt"
	"testing"
)

func TestARQRoundtrip(t *testing.T) {
	if !carrierARQ {
		t.Skip("set WLB_CARRIER_ARQ=1 WLB_VALID_VP8_TUNNEL=1")
	}
	enc, _ := NewTunnelObfuscator([]byte("secret-token"))
	dec, _ := NewTunnelObfuscator([]byte("secret-token"))
	send := NewVP8DataTunnelMulti(nil, enc, func(string, ...any) {})
	recv := NewVP8DataTunnelMulti(nil, dec, func(string, ...any) {})
	var got [][]byte
	recv.OnData = func(b []byte) { got = append(got, append([]byte(nil), b...)) }

	mk := func(i int) []byte { return []byte(fmt.Sprintf("MSG-%03d", i)) }
	frame := func(i int) []byte {
		p := send.arqWrapData(mk(i))
		return send.obf.EncodeData(p)
	}
	// in-order 1..5
	for i := 1; i <= 5; i++ {
		recv.HandleFrame(frame(i))
	}
	// out-of-order: build 6,7,8 then feed 6,8,7
	f6, f7, f8 := frame(6), frame(7), frame(8)
	recv.HandleFrame(f6)
	recv.HandleFrame(f8) // buffered
	recv.HandleFrame(f7) // fills gap -> delivers 7,8
	if len(got) != 8 {
		t.Fatalf("delivered %d, want 8: %q", len(got), got)
	}
	for i := 1; i <= 8; i++ {
		if !bytes.Equal(got[i-1], mk(i)) {
			t.Fatalf("order wrong at %d: got %q want %q", i, got[i-1], mk(i))
		}
	}
	t.Logf("OK in-order+reorder, delivered %d", len(got))
}

// TestARQWarmupAnchor reproduces the connect-hang bug: VP8 keyframe warmup drops
// the first carrier frames, so the receiver's FIRST decoded ARQ frame is seq>1.
// The old anchor (= first seen seq) silently skipped seqs 1..N-1, permanently
// losing the genuinely-first payload (the SOCKS MsgConnect, which has no resend)
// — connections then hang waiting for a reply that never comes. With the warmup
// anchor, seq 1..N-1 stay below maxSeen as a recoverable gap and NACK fills them.
func TestARQWarmupAnchor(t *testing.T) {
	if !carrierARQ {
		t.Skip("set WLB_CARRIER_ARQ=1 WLB_VALID_VP8_TUNNEL=1")
	}
	enc, _ := NewTunnelObfuscator([]byte("secret-token"))
	dec, _ := NewTunnelObfuscator([]byte("secret-token"))
	send := NewVP8DataTunnelMulti(nil, enc, func(string, ...any) {})
	recv := NewVP8DataTunnelMulti(nil, dec, func(string, ...any) {})
	var got [][]byte
	recv.OnData = func(b []byte) { got = append(got, append([]byte(nil), b...)) }

	mk := func(i int) []byte { return []byte(fmt.Sprintf("MSG-%03d", i)) }
	frame := func(i int) []byte { return send.obf.EncodeData(send.arqWrapData(mk(i))) }

	// Sender produces 1,2,3 but warmup eats 1,2: receiver decodes seq=3 first.
	f1, f2, f3 := frame(1), frame(2), frame(3)
	recv.HandleFrame(f3) // first decoded is seq=3
	// Old behaviour would anchor expected=3 and deliver MSG-003 immediately,
	// stranding 1,2. New behaviour anchors at 1 and buffers 3, delivering nothing
	// until the gap is filled.
	if len(got) != 0 {
		t.Fatalf("seq=3 should buffer until 1,2 arrive; delivered %q", got)
	}
	// NACK retransmit arrives (out of order is fine).
	recv.HandleFrame(f2)
	recv.HandleFrame(f1) // fills head -> delivers 1,2,3 in order
	if len(got) != 3 {
		t.Fatalf("delivered %d, want 3: %q", len(got), got)
	}
	for i := 1; i <= 3; i++ {
		if !bytes.Equal(got[i-1], mk(i)) {
			t.Fatalf("order wrong at %d: got %q want %q", i, got[i-1], mk(i))
		}
	}
	t.Logf("OK warmup anchor recovered seq 1,2 after seq 3 seen first")
}

// TestARQPeerRestartReset reproduces the peer-restart desync and proves the fix.
// After the peer's process restarts it returns with a fresh obfuscator epoch AND
// an ARQ send seq that starts at 1, while our receive cursor is still high from
// the dead session. Two things then block the new stream forever: the ARQ cursor
// drops every seq<expected (the NACK loop is silent because expected>maxSeen),
// and the obfuscator phantom-lock shadows the new epoch for lockIdleMs. The
// SFU-level restart signal calls ResetPeerRestart, which clears both.
func TestARQPeerRestartReset(t *testing.T) {
	if !carrierARQ {
		t.Skip("set WLB_CARRIER_ARQ=1 WLB_VALID_VP8_TUNNEL=1")
	}
	dec, _ := NewTunnelObfuscator([]byte("secret-token"))
	recv := NewVP8DataTunnelMulti(nil, dec, func(string, ...any) {})
	var got [][]byte
	recv.OnData = func(b []byte) { got = append(got, append([]byte(nil), b...)) }
	mk := func(tag string, i int) []byte { return []byte(fmt.Sprintf("%s-%03d", tag, i)) }

	// Old peer establishes a stream: deliver seq 1..50 (cursor advances to 51).
	enc1, _ := NewTunnelObfuscator([]byte("secret-token"))
	send1 := NewVP8DataTunnelMulti(nil, enc1, func(string, ...any) {})
	for i := 1; i <= 50; i++ {
		recv.HandleFrame(enc1.EncodeData(send1.arqWrapData(mk("OLD", i))))
	}
	if len(got) != 50 {
		t.Fatalf("old stream: delivered %d, want 50", len(got))
	}

	// Peer restarts -> new epoch + fresh send seq. Its first frame BEFORE the
	// reset must be dropped: below the dead cursor AND phantom-locked out.
	enc2, _ := NewTunnelObfuscator([]byte("secret-token"))
	send2 := NewVP8DataTunnelMulti(nil, enc2, func(string, ...any) {})
	recv.HandleFrame(enc2.EncodeData(send2.arqWrapData(mk("DROP", 1))))
	if len(got) != 50 {
		t.Fatalf("pre-reset: new-epoch frame should be dropped, got %d", len(got))
	}

	// SFU new-track signal fires -> re-anchor receive state.
	recv.ResetPeerRestart()

	// The restarted peer (modelled as a fresh sender, seq from 1) is now adopted
	// and delivered in order on top of the old 50.
	enc3, _ := NewTunnelObfuscator([]byte("secret-token"))
	send3 := NewVP8DataTunnelMulti(nil, enc3, func(string, ...any) {})
	for i := 1; i <= 5; i++ {
		recv.HandleFrame(enc3.EncodeData(send3.arqWrapData(mk("NEW", i))))
	}
	if len(got) != 55 {
		t.Fatalf("post-reset: delivered %d, want 55 (new stream recovered)", len(got))
	}
	for i := 1; i <= 5; i++ {
		if !bytes.Equal(got[50+i-1], mk("NEW", i)) {
			t.Fatalf("post-reset order wrong at %d: got %q want %q", i, got[50+i-1], mk("NEW", i))
		}
	}
	t.Logf("OK peer-restart reset recovered new-epoch stream after old cursor was high")
}

// TestMultiTrackARQSingleSeqSpace guards the multi-track ARQ seq-collision fix:
// under ARQ, MultiTrackTunnel must route ALL data frames through tunnels[0] (one
// seq space) regardless of connID, so the single receive cursor never sees two
// colliding seq spaces. Without the fix, connIDs hashing to different tracks
// would split the stream across sub-tunnels and the 2nd track would be dropped
// as duplicates on receive.
func TestMultiTrackARQSingleSeqSpace(t *testing.T) {
	if !carrierARQ {
		t.Skip("set WLB_CARRIER_ARQ=1 WLB_VALID_VP8_TUNNEL=1")
	}
	obf, _ := NewTunnelObfuscator([]byte("secret-token"))
	a := NewVP8DataTunnelMulti(nil, obf, func(string, ...any) {})
	b := NewVP8DataTunnelMulti(nil, obf, func(string, ...any) {})
	m := NewMultiTrackTunnel([]*VP8DataTunnel{a, b})

	// connID=2 hashes to track 0 (2%2==0); connID=3 to track 1 (3%2==1). Under
	// ARQ both must still land on tunnel[0]. The writers aren't started, so the
	// frames stay queued and we can read the queue depths directly.
	m.SendData(EncodeFrame(2, MsgData, []byte("x")))
	m.SendData(EncodeFrame(3, MsgData, []byte("y")))
	if len(a.sendQueue) != 2 || len(b.sendQueue) != 0 {
		t.Fatalf("ARQ must route all data to tunnel[0]: tunnel0=%d tunnel1=%d (want 2,0)",
			len(a.sendQueue), len(b.sendQueue))
	}
	t.Logf("OK ARQ keeps a single seq space (both frames on tunnel[0])")
}

// TestARQMidStreamJoin checks the other side of the anchor: a first seq well past
// the warmup window is a mid-stream join, so we anchor there and do NOT stall
// trying to recover thousands of seqs we were never sent.
func TestARQMidStreamJoin(t *testing.T) {
	if !carrierARQ {
		t.Skip("set WLB_CARRIER_ARQ=1 WLB_VALID_VP8_TUNNEL=1")
	}
	enc, _ := NewTunnelObfuscator([]byte("secret-token"))
	dec, _ := NewTunnelObfuscator([]byte("secret-token"))
	send := NewVP8DataTunnelMulti(nil, enc, func(string, ...any) {})
	recv := NewVP8DataTunnelMulti(nil, dec, func(string, ...any) {})
	var got [][]byte
	recv.OnData = func(b []byte) { got = append(got, append([]byte(nil), b...)) }
	mk := func(i int) []byte { return []byte(fmt.Sprintf("MSG-%03d", i)) }
	frame := func(i int) []byte { return send.obf.EncodeData(send.arqWrapData(mk(i))) }

	// Advance the sender's seq far past the warmup window, then deliver one frame.
	for i := 1; i <= 5000; i++ {
		send.arqWrapData(mk(i)) // bump arqSendSeq only
	}
	f := frame(5001)
	recv.HandleFrame(f)
	if len(got) != 1 || !bytes.Equal(got[0], mk(5001)) {
		t.Fatalf("mid-stream join should deliver seq=5001 immediately, got %q", got)
	}
	t.Logf("OK mid-stream join anchored at first seq, no stall")
}
