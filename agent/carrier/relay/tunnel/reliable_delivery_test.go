package tunnel

import (
	"bytes"
	"sync"
	"testing"
	"time"
)

func TestDelayedRetransmissionNeverSkipsApplicationBytes(t *testing.T) {
	for _, pc := range []bool{false, true} {
		name := "ARQ"
		if pc {
			name = "PCARQ"
		}
		t.Run(name, func(t *testing.T) {
			obf, _ := NewTunnelObfuscator([]byte("test"))
			recv := NewVP8DataTunnelMulti(nil, obf, func(string, ...any) {})
			recv.running.Store(true)
			defer recv.Stop()
			var mu sync.Mutex
			var got []byte
			recv.OnData = func(b []byte) { mu.Lock(); got = append(got, b...); mu.Unlock() }
			put := func(seq uint32, b string) {
				if pc {
					recv.pcRecvData(7, seq, []byte(b))
				} else {
					recv.arqRecvData(seq, []byte(b))
				}
			}
			if pc {
				go recv.pcNackLoop()
			} else {
				go recv.arqNackLoop()
			}
			put(1, "A")
			put(3, "C")
			// Exceeds the old 600ms skip timeout.
			time.Sleep(850 * time.Millisecond)
			mu.Lock()
			before := string(got)
			mu.Unlock()
			if before != "A" {
				t.Fatalf("delivered around a missing byte: %q", before)
			}
			put(2, "B")
			until := time.Now().Add(time.Second)
			for time.Now().Before(until) {
				mu.Lock()
				done := len(got) == 3
				mu.Unlock()
				if done {
					break
				}
				time.Sleep(time.Millisecond)
			}
			mu.Lock()
			defer mu.Unlock()
			if !bytes.Equal(got, []byte("ABC")) {
				t.Fatalf("stream corrupted: %q", got)
			}
		})
	}
}

func TestUnrecoverableGapClosesInsteadOfCorruptingStream(t *testing.T) {
	obf, _ := NewTunnelObfuscator([]byte("test"))
	recv := NewVP8DataTunnelMulti(nil, obf, func(string, ...any) {})
	recv.running.Store(true)
	defer recv.Stop()
	var got []byte
	recv.OnData = func(b []byte) { got = append(got, b...) }
	recv.arqRecvData(1, []byte("A"))
	recv.arqRecvData(3, []byte("C"))
	recv.arqRecvMu.Lock()
	recv.arqGapAt = time.Now().Add(-31 * time.Second)
	recv.arqRecvMu.Unlock()
	go recv.arqNackLoop()
	select {
	case <-recv.stopCh:
	case <-time.After(time.Second):
		t.Fatal("unrecoverable stream not closed")
	}
	recv.arqRecvData(2, []byte("B"))
	if string(got) != "A" {
		t.Fatalf("delivered after closing: %q", got)
	}
}
