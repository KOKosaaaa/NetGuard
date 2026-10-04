package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

type qaPendingTunnel struct {
	stubTunnel
	frames chan []byte
}

func (s *qaPendingTunnel) SendData(b []byte)             { s.frames <- append([]byte(nil), b...) }
func (s *qaPendingTunnel) SendDataTo(_ uint32, b []byte) { s.SendData(b) }

type qaPendingResult struct {
	conn net.Conn
	err  error
}
type qaPendingDial struct {
	ctx    context.Context
	result chan qaPendingResult
}
type qaClosedConn struct {
	net.Conn
	closed chan struct{}
	once   sync.Once
}

func (c *qaClosedConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() { close(c.closed) })
	return err
}

func qaPendingFixture(t *testing.T) (*RelayBridge, *qaPendingTunnel, <-chan qaPendingDial) {
	t.Helper()
	wire := &qaPendingTunnel{frames: make(chan []byte, 32)}
	rb := NewRelayBridge(wire, "creator", 4096, func(string, ...any) {})
	requests := make(chan qaPendingDial, 8)
	rb.dialTCP = func(ctx context.Context, _, _ string) (net.Conn, error) {
		request := qaPendingDial{ctx: ctx, result: make(chan qaPendingResult, 1)}
		requests <- request
		// Intentionally ignore cancellation: some in-flight dialers can finish
		// successfully at the same instant as cancellation. Ownership must still
		// be checked before publishing either a socket or MsgConnectOK.
		result := <-request.result
		return result.conn, result.err
	}
	t.Cleanup(rb.Close)
	return rb, wire, requests
}

func qaTakeDial(t *testing.T, requests <-chan qaPendingDial) qaPendingDial {
	t.Helper()
	select {
	case r := <-requests:
		return r
	case <-time.After(time.Second):
		t.Fatal("dial did not start")
		return qaPendingDial{}
	}
}

func qaLateSocket(t *testing.T, request qaPendingDial) *qaClosedConn {
	t.Helper()
	a, b := net.Pipe()
	t.Cleanup(func() { a.Close(); b.Close() })
	conn := &qaClosedConn{Conn: a, closed: make(chan struct{})}
	request.result <- qaPendingResult{conn: conn}
	select {
	case <-conn.closed:
	case <-time.After(time.Second):
		t.Fatal("late successful dial leaked a socket")
	}
	return conn
}

func TestRelayQAPendingDialCloseResetAndShutdownCancelLateSuccess(t *testing.T) {
	for _, action := range []string{"remote-close", "reset", "shutdown"} {
		t.Run(action, func(t *testing.T) {
			rb, wire, requests := qaPendingFixture(t)
			rb.handleCreatorMessage(7, MsgConnect, []byte("fixture.invalid:443"))
			r := qaTakeDial(t, requests)
			switch action {
			case "remote-close":
				rb.handleCreatorMessage(7, MsgClose, nil)
			case "reset":
				rb.Reset()
			case "shutdown":
				rb.Close()
			}
			select {
			case <-r.ctx.Done():
			case <-time.After(time.Second):
				t.Fatal("pending dial was not cancelled")
			}
			qaLateSocket(t, r)
			if _, ok := rb.conns.Load(uint32(7)); ok {
				t.Fatal("cancelled dial published a socket")
			}
			select {
			case frame := <-wire.frames:
				t.Fatalf("cancelled dial emitted stale frame kind=%d", frame[8])
			default:
			}
		})
	}
}

func TestRelayQAPendingDialResetCannotDeleteReusedID(t *testing.T) {
	rb, wire, requests := qaPendingFixture(t)
	rb.handleCreatorMessage(7, MsgConnect, []byte("old.invalid:443"))
	old := qaTakeDial(t, requests)
	rb.Reset()
	rb.handleCreatorMessage(7, MsgConnect, []byte("new.invalid:443"))
	fresh := qaTakeDial(t, requests)
	a, b := net.Pipe()
	t.Cleanup(func() { a.Close(); b.Close() })
	fresh.result <- qaPendingResult{conn: a}
	select {
	case f := <-wire.frames:
		if f[8] != MsgConnectOK {
			t.Fatal("fresh connection failed")
		}
	case <-time.After(time.Second):
		t.Fatal("fresh connection did not open")
	}
	qaLateSocket(t, old)
	if got, ok := rb.conns.Load(uint32(7)); !ok || got != a {
		t.Fatal("old completion deleted/replaced the new generation socket")
	}
	select {
	case f := <-wire.frames:
		t.Fatalf("old dial generated frame kind=%d for reused ID", f[8])
	default:
	}
	b.Close()
	select {
	case f := <-wire.frames:
		if f[8] != MsgClose {
			t.Fatal("fresh EOF did not send close")
		}
	case <-time.After(time.Second):
		t.Fatal("fresh EOF was lost")
	}
}

func TestRelayQAPendingDialLateFailureIsSilentAndDuplicateConnectDeduplicates(t *testing.T) {
	rb, wire, requests := qaPendingFixture(t)
	rb.handleCreatorMessage(7, MsgConnect, []byte("fixture.invalid:443"))
	r := qaTakeDial(t, requests)
	rb.handleCreatorMessage(7, MsgConnect, []byte("duplicate.invalid:443"))
	select {
	case <-requests:
		t.Fatal("duplicate connect started a second dial")
	default:
	}
	rb.handleCreatorMessage(7, MsgClose, nil)
	r.result <- qaPendingResult{err: errors.New("late fixture error")}
	// No arbitrary sleep: a second dial on another ID must still make progress.
	rb.handleCreatorMessage(8, MsgConnect, []byte("second.invalid:443"))
	r2 := qaTakeDial(t, requests)
	r2.result <- qaPendingResult{err: errors.New("expected fixture failure")}
	select {
	case f := <-wire.frames:
		if f[8] != MsgConnectErr || f[7] != 8 {
			t.Fatal("late cancelled failure escaped instead of current failure")
		}
	case <-time.After(time.Second):
		t.Fatal("current failure missing")
	}
}

func TestRelayQARemoteCloseFailsPendingSOCKSWithoutTwentySecondWait(t *testing.T) {
	wire := &qaPendingTunnel{frames: make(chan []byte, 8)}
	rb := NewRelayBridge(wire, "joiner", 4096, func(string, ...any) {})
	t.Cleanup(rb.Close)
	rb.MarkReady()
	a, b := net.Pipe()
	t.Cleanup(func() { a.Close(); b.Close() })
	b.SetDeadline(time.Now().Add(2 * time.Second))
	done := make(chan struct{})
	go func() { rb.handleSOCKS(a); close(done) }()
	if _, err := b.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	response := make([]byte, 2)
	if _, err := io.ReadFull(b, response); err != nil {
		t.Fatal(err)
	}
	if response[0] != 5 || response[1] != 0 {
		t.Fatal("SOCKS greeting failed")
	}
	if _, err := b.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, 1, 187}); err != nil {
		t.Fatal(err)
	}
	select {
	case f := <-wire.frames:
		if f[8] != MsgConnect {
			t.Fatal("missing CONNECT intent")
		}
	case <-time.After(time.Second):
		t.Fatal("CONNECT intent timeout")
	}
	rb.handleJoinerMessage(1, MsgClose, nil)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("remote close left SOCKS handler waiting for ConnectOK timeout")
	}
	if _, ok := rb.conns.Load(uint32(1)); ok {
		t.Fatal("failed SOCKS connection retained")
	}
}
