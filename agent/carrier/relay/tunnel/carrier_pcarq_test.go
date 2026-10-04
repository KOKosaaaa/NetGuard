package tunnel

import (
	"fmt"
	"sync"
	"testing"
	"time"
)

// Run with: WLB_CARRIER_PCARQ=1 WLB_VALID_VP8_TUNNEL=1 go test ./tunnel/

func pcNoop(string, ...any) {}

// pcCapture wires a receiver tunnel to record delivered (connID, payload) in
// order, decoding the relay framing the way the real bridge does.
type pcRecord struct {
	conn uint32
	data string
}
type pcRecorder struct {
	mu  sync.Mutex
	got []pcRecord
}

func pcCapture(recv *VP8DataTunnel) *pcRecorder {
	r := &pcRecorder{}
	recv.OnData = func(b []byte) {
		r.mu.Lock()
		defer r.mu.Unlock()
		DecodeFrames(b, func(id uint32, _ byte, p []byte) { r.got = append(r.got, pcRecord{id, string(p)}) })
	}
	return r
}
func (r *pcRecorder) snapshot() []pcRecord {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]pcRecord(nil), r.got...)
}
func (r *pcRecorder) wait(t *testing.T, n int) []pcRecord {
	t.Helper()
	until := time.Now().Add(time.Second)
	for time.Now().Before(until) {
		g := r.snapshot()
		if len(g) >= n {
			return g
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("delivery timeout: got %d want %d", len(r.snapshot()), n)
	return nil
}

// TestPCARQRoundtripReorder: within one conn, in-order delivery + reorder buffer.
func TestPCARQRoundtripReorder(t *testing.T) {
	if !carrierPCARQ {
		t.Skip("set WLB_CARRIER_PCARQ=1 WLB_VALID_VP8_TUNNEL=1")
	}
	enc, _ := NewTunnelObfuscator([]byte("s"))
	dec, _ := NewTunnelObfuscator([]byte("s"))
	send := NewVP8DataTunnelMulti(nil, enc, pcNoop)
	recv := NewVP8DataTunnelMulti(nil, dec, pcNoop)
	rec := pcCapture(recv)
	recv.running.Store(true)
	defer recv.Stop()
	const C = uint32(7)
	mk := func(s string) []byte {
		rf := EncodeFrame(C, MsgData, []byte(s))
		return enc.EncodeData(send.pcWrapFrames([][]byte{rf}))
	}
	f1, f2, f3 := mk("m1"), mk("m2"), mk("m3")
	recv.HandleFrame(f1)
	recv.HandleFrame(f3) // ahead -> buffered
	got := rec.wait(t, 1)
	if len(got) != 1 {
		t.Fatalf("after f1,f3: want 1 delivered, got %d", len(got))
	}
	recv.HandleFrame(f2) // fills gap -> delivers m2, m3
	got = rec.wait(t, 3)
	if len(got) != 3 || got[0].data != "m1" || got[1].data != "m2" || got[2].data != "m3" {
		t.Fatalf("reorder wrong: %+v", got)
	}
	t.Logf("OK per-conn in-order+reorder")
}

// TestPCARQHeadOfLineIsolation is the core fix: a loss on conn A must NOT block
// conn B's delivery (no cross-conn head-of-line blocking), and conn A recovers
// independently once its gap is filled by retransmit.
func TestPCARQHeadOfLineIsolation(t *testing.T) {
	if !carrierPCARQ {
		t.Skip("set WLB_CARRIER_PCARQ=1 WLB_VALID_VP8_TUNNEL=1")
	}
	enc, _ := NewTunnelObfuscator([]byte("s"))
	dec, _ := NewTunnelObfuscator([]byte("s"))
	send := NewVP8DataTunnelMulti(nil, enc, pcNoop)
	recv := NewVP8DataTunnelMulti(nil, dec, pcNoop)
	rec := pcCapture(recv)
	recv.running.Store(true)
	defer recv.Stop()
	const A, B = uint32(10), uint32(20)
	mk := func(conn uint32, s string) []byte {
		rf := EncodeFrame(conn, MsgData, []byte(s))
		return enc.EncodeData(send.pcWrapFrames([][]byte{rf}))
	}
	onlyConn := func(conn uint32) []string {
		var out []string
		for _, r := range rec.snapshot() {
			if r.conn == conn {
				out = append(out, r.data)
			}
		}
		return out
	}

	recv.HandleFrame(mk(A, "a1")) // A seq1 deliver
	recv.HandleFrame(mk(B, "b1")) // B seq1 deliver
	a2 := mk(A, "a2")             // A seq2 BUILT then LOST (not fed)
	recv.HandleFrame(mk(B, "b2")) // B seq2 deliver — must NOT be blocked by A's gap
	recv.HandleFrame(mk(B, "b3")) // B seq3 deliver
	recv.HandleFrame(mk(A, "a3")) // A seq3 buffered (waits for a2)

	rec.wait(t, 4)
	// B fully flows through despite A being stuck — this is the HOL isolation.
	if b := onlyConn(B); len(b) != 3 || b[0] != "b1" || b[1] != "b2" || b[2] != "b3" {
		t.Fatalf("conn B should deliver b1,b2,b3 despite conn A gap; got %v", b)
	}
	if a := onlyConn(A); len(a) != 1 || a[0] != "a1" {
		t.Fatalf("conn A should only have a1 while gap open; got %v", a)
	}

	// Retransmit the lost A seq2 -> A recovers a2 then a3, independently.
	recv.HandleFrame(a2)
	rec.wait(t, 6)
	if a := onlyConn(A); len(a) != 3 || a[0] != "a1" || a[1] != "a2" || a[2] != "a3" {
		t.Fatalf("conn A should recover to a1,a2,a3 after retransmit; got %v", a)
	}
	t.Logf("OK head-of-line isolation: B unblocked by A's loss, A recovered independently")
}

// TestPCARQManyConnsNoCrossBlock stresses isolation: one stuck conn must not
// stall N other conns each delivering many frames.
func TestPCARQManyConnsNoCrossBlock(t *testing.T) {
	if !carrierPCARQ {
		t.Skip("set WLB_CARRIER_PCARQ=1 WLB_VALID_VP8_TUNNEL=1")
	}
	enc, _ := NewTunnelObfuscator([]byte("s"))
	dec, _ := NewTunnelObfuscator([]byte("s"))
	send := NewVP8DataTunnelMulti(nil, enc, pcNoop)
	recv := NewVP8DataTunnelMulti(nil, dec, pcNoop)
	rec := pcCapture(recv)
	recv.running.Store(true)
	defer recv.Stop()
	mk := func(conn uint32, s string) []byte {
		rf := EncodeFrame(conn, MsgData, []byte(s))
		return enc.EncodeData(send.pcWrapFrames([][]byte{rf}))
	}
	const stuck = uint32(99)
	mk(stuck, "lost1") // stuck conn loses seq1 (built, not fed)
	delivered := 0
	for c := uint32(1); c <= 5; c++ {
		for i := 1; i <= 20; i++ {
			recv.HandleFrame(mk(c, fmt.Sprintf("c%d-%d", c, i)))
			delivered++
		}
	}
	// feed stuck conn's seq2 -> still blocked (seq1 missing) but others unaffected
	recv.HandleFrame(mk(stuck, "after"))
	rec.wait(t, delivered)
	n := 0
	for _, r := range rec.snapshot() {
		if r.conn != stuck {
			n++
		}
	}
	if n != delivered {
		t.Fatalf("5 healthy conns x20 should all deliver despite a stuck conn; got %d want %d", n, delivered)
	}
	for _, r := range rec.snapshot() {
		if r.conn == stuck {
			t.Fatalf("stuck conn must not deliver while its head gap is open; got %q", r.data)
		}
	}
	t.Logf("OK %d frames across 5 conns delivered, stuck conn isolated", n)
}
