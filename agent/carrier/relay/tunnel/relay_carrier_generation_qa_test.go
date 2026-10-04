package tunnel

import (
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

type qaLifetimeTunnel struct {
	qaPendingTunnel
	done chan struct{}
}

type qaCapturedDataTunnel struct {
	qaPendingTunnel
	data func([]byte)
}

func (s *qaCapturedDataTunnel) SetOnData(fn func([]byte)) { s.data = fn }

func TestRelayQALateOldControlCallbackCannotConfirmReplacement(t *testing.T) {
	old := &qaCapturedDataTunnel{qaPendingTunnel: qaPendingTunnel{frames: make(chan []byte, 8)}}
	fresh := &qaCapturedDataTunnel{qaPendingTunnel: qaPendingTunnel{frames: make(chan []byte, 8)}}
	rb := NewRelayBridge(old, "joiner", 4096, func(string, ...any) {})
	t.Cleanup(rb.Close)
	oldDelivery := old.data // A callback captured by the old per-connection consumer.
	rb.SwapTunnel(fresh)
	var ready atomic.Int32
	rb.SetOnConfigAck(func() { ready.Add(1) })
	oldDelivery(EncodeFrame(ControlConnID, MsgConfigAck, nil))
	if ready.Load() != 0 {
		t.Fatal("late old-generation control ACK confirmed replacement carrier")
	}
	fresh.data(EncodeFrame(ControlConnID, MsgConfigAck, nil))
	if ready.Load() != 1 {
		t.Fatal("current carrier config ACK was lost")
	}
}

func (s *qaLifetimeTunnel) Done() <-chan struct{} { return s.done }

func qaSOCKSRequest(t *testing.T, rb *RelayBridge, conn net.Conn) (net.Conn, <-chan struct{}) {
	t.Helper()
	var client net.Conn
	if conn == nil {
		conn, client = net.Pipe()
	} else {
		t.Fatal("unsupported fixture conn")
	}
	t.Cleanup(func() { conn.Close(); client.Close() })
	client.SetDeadline(time.Now().Add(2 * time.Second))
	done := make(chan struct{})
	go func() { rb.handleSOCKS(conn); close(done) }()
	if _, err := client.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	b := make([]byte, 2)
	if _, err := io.ReadFull(client, b); err != nil || b[1] != 0 {
		t.Fatalf("greeting %v %v", b, err)
	}
	if _, err := client.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, 1, 187}); err != nil {
		t.Fatal(err)
	}
	return client, done
}

func TestRelayQARequestsDuringRecoveryFailWithoutTwentySecondWait(t *testing.T) {
	for _, phase := range []string{"already-closed", "closes-after-admission"} {
		t.Run(phase, func(t *testing.T) {
			old := &qaLifetimeTunnel{qaPendingTunnel: qaPendingTunnel{frames: make(chan []byte, 8)}, done: make(chan struct{})}
			rb := NewRelayBridge(old, "joiner", 4096, func(string, ...any) {})
			rb.MarkReady()
			t.Cleanup(rb.Close)
			if phase == "already-closed" {
				close(old.done)
			}
			client, done := qaSOCKSRequest(t, rb, nil)
			if phase == "closes-after-admission" {
				select {
				case <-old.frames:
				case <-time.After(time.Second):
					t.Fatal("no connect intent")
				}
				close(old.done) // No callback/reset: lifetime must cover admission races.
			} else {
				answer := make([]byte, 10)
				if _, err := io.ReadFull(client, answer); err != nil || answer[1] == 0 {
					t.Fatalf("dead carrier returned success/no failure: %v %v", answer, err)
				}
			}
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("dead carrier retained pending CONNECT")
			}
			if n, _, _ := rb.Stats(); n != 0 {
				t.Fatalf("dead carrier retained %d sockets", n)
			}
			if phase == "already-closed" {
				select {
				case <-old.frames:
					t.Fatal("new request sent to dead carrier")
				default:
				}
			}
		})
	}
}

type qaLateReadConn struct {
	net.Conn
	reads            atomic.Int32
	entered, release chan struct{}
}

func (c *qaLateReadConn) Read(p []byte) (int, error) {
	if c.reads.Add(1) == 3 {
		close(c.entered)
		<-c.release // Delayed completion of a read admitted before socket Close.
		copy(p, []byte("old bytes"))
		return len("old bytes"), io.EOF
	}
	return c.Conn.Read(p)
}

func TestRelayQALateReadAndEOFAfterSwapCannotEnterReplacementCarrier(t *testing.T) {
	old := &qaPendingTunnel{frames: make(chan []byte, 8)}
	fresh := &qaPendingTunnel{frames: make(chan []byte, 8)}
	rb := NewRelayBridge(old, "joiner", 4096, func(string, ...any) {})
	rb.MarkReady()
	t.Cleanup(rb.Close)
	a, b := net.Pipe()
	t.Cleanup(func() { a.Close(); b.Close() })
	b.SetDeadline(time.Now().Add(2 * time.Second))
	conn := &qaLateReadConn{Conn: a, entered: make(chan struct{}), release: make(chan struct{})}
	go rb.handleSOCKS(conn)
	if _, err := b.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greet := make([]byte, 2)
	if _, err := io.ReadFull(b, greet); err != nil {
		t.Fatal(err)
	}
	if _, err := b.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, 1, 187}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-old.frames:
	case <-time.After(time.Second):
		t.Fatal("connect not admitted")
	}
	rb.handleJoinerMessage(1, MsgConnectOK, nil)
	answer := make([]byte, 10)
	if _, err := io.ReadFull(b, answer); err != nil || answer[1] != 0 {
		t.Fatalf("connect failed: %v", err)
	}
	select {
	case <-conn.entered:
	case <-time.After(time.Second):
		t.Fatal("read did not start")
	}
	rb.SwapTunnel(fresh)
	close(conn.release)
	for _, kind := range []byte{MsgData, MsgClose} {
		select {
		case f := <-old.frames:
			if f[8] != kind {
				t.Fatalf("old carrier kind%d want%d", f[8], kind)
			}
		case <-time.After(time.Second):
			t.Fatal("retired read was not confined to its original carrier")
		}
	}
	select {
	case f := <-fresh.frames:
		t.Fatalf("old read/EOF leaked kind%d into replacement", f[8])
	default:
	}
}

func TestRelayQALateOldParentStopCannotCloseFreshCarrierSockets(t *testing.T) {
	oldObf := qaBoundObf(t, true)
	oldChild, _ := qaReconnectPeer(t, oldObf)
	old := NewMultiTrackTunnel([]*VP8DataTunnel{oldChild})
	fresh := &qaLifetimeTunnel{qaPendingTunnel: qaPendingTunnel{frames: make(chan []byte, 8)}, done: make(chan struct{})}
	rb := NewRelayBridge(old, "joiner", 4096, func(string, ...any) {})
	rb.SetPersistentListener(true)
	rb.MarkReady()
	t.Cleanup(rb.Close)
	t.Cleanup(old.Stop)
	rb.SwapTunnel(fresh)
	client, _ := qaSOCKSRequest(t, rb, nil)
	select {
	case <-fresh.frames:
	case <-time.After(time.Second):
		t.Fatal("fresh CONNECT not admitted")
	}
	old.Stop() // A delayed teardown callback from the RETIRED parent carrier.
	if count, _, _ := rb.Stats(); count != 1 {
		t.Fatalf("old parent Stop closed fresh connection: retained=%d", count)
	}
	rb.handleJoinerMessage(1, MsgConnectOK, nil)
	answer := make([]byte, 10)
	if _, err := io.ReadFull(client, answer); err != nil || answer[1] != 0 {
		t.Fatalf("fresh connection could not complete after old Stop: %v %v", answer, err)
	}
}
