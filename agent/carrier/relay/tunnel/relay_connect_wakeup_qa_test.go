package tunnel

import (
	"io"
	"net"
	"testing"
	"time"
)

// Exercise the actual SOCKS handshake and handler. Merely checking that Close
// closes a net.Conn misses the goroutine still waiting for MsgConnectOK.
func TestRelayQAAllLocalTeardownsWakePendingConnect(t *testing.T) {
	for _, action := range []string{"reset", "carrier-close", "swap", "shutdown"} {
		t.Run(action, func(t *testing.T) {
			wire := &qaPendingTunnel{frames: make(chan []byte, 16)}
			fresh := &qaPendingTunnel{frames: make(chan []byte, 16)}
			rb := NewRelayBridge(wire, "joiner", 4096, func(string, ...any) {})
			t.Cleanup(rb.Close)
			rb.SetPersistentListener(true)
			rb.MarkReady()
			a, b := net.Pipe()
			t.Cleanup(func() { a.Close(); b.Close() })
			b.SetDeadline(time.Now().Add(2 * time.Second))
			done := make(chan struct{})
			go func() { rb.handleSOCKS(a); close(done) }()
			if _, err := b.Write([]byte{5, 1, 0}); err != nil {
				t.Fatal(err)
			}
			greeting := make([]byte, 2)
			if _, err := io.ReadFull(b, greeting); err != nil || greeting[0] != 5 || greeting[1] != 0 {
				t.Fatalf("SOCKS greeting: %v %v", greeting, err)
			}
			if _, err := b.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, 1, 187}); err != nil {
				t.Fatal(err)
			}
			select {
			case f := <-wire.frames:
				if f[8] != MsgConnect {
					t.Fatal("expected real CONNECT request")
				}
			case <-time.After(time.Second):
				t.Fatal("CONNECT was not sent")
			}
			closed := make(chan struct{})
			go func() {
				switch action {
				case "reset":
					rb.Reset()
				case "carrier-close":
					rb.handleTunnelClose()
				case "swap":
					rb.SwapTunnel(fresh)
				case "shutdown":
					rb.Close()
				}
				close(closed)
			}()
			select {
			case <-closed:
			case <-time.After(time.Second):
				t.Fatal("teardown blocked on pending CONNECT")
			}
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("closed socket left CONNECT handler waiting for its 20-second timeout")
			}
			if n, _, _ := rb.Stats(); n != 0 {
				t.Fatalf("retained %d connections", n)
			}
			for _, q := range []chan []byte{wire.frames, fresh.frames} {
				select {
				case f := <-q:
					t.Fatalf("retired CONNECT emitted late frame kind=%d", f[8])
				default:
				}
			}
		})
	}
}

func TestRelayQATeardownDoesNotBlockOnAlreadyNotifiedConnect(t *testing.T) {
	for _, notification := range []error{nil, io.EOF} {
		wire := &qaPendingTunnel{frames: make(chan []byte, 16)}
		rb := NewRelayBridge(wire, "joiner", 4096, func(string, ...any) {})
		a, b := net.Pipe()
		sc := &socksConn{id: 1, conn: a, rb: rb, rdy: make(chan error, 1)}
		sc.rdy <- notification
		rb.conns.Store(uint32(1), sc)
		done := make(chan struct{})
		go func() { rb.Close(); close(done) }()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Fatal("teardown blocked because readiness notification was already queued")
		}
		b.Close()
	}
}
