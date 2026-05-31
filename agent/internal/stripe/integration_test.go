package stripe

import (
	"bytes"
	"context"
	"io"
	"math/rand"
	"net"
	"sync"
	"testing"
	"time"
)

// startEcho starts a TCP server that echoes everything it reads back to the
// sender, then half-closes when the peer half-closes. It is the stand-in for
// the "real destination" the stripe server dials.
func startEcho(t *testing.T) net.Listener {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("echo listen: %v", err)
	}
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				io.Copy(c, c)
				if cw, ok := c.(*net.TCPConn); ok {
					cw.CloseWrite()
				}
			}(c)
		}
	}()
	return l
}

// striper is a minimal in-test client: the Go stand-in for the Kotlin
// StripeMux, exercising the full server path (hello grouping, lazy open/dial,
// striped reassembly to dest, striped echo back, window/ack on both halves).
type striper struct {
	t      *testing.T
	pipes  []net.Conn
	cursor int
	sendMu sync.Mutex // serialize writes across pipes from the c2s sender

	mu        sync.Mutex
	cond      *sync.Cond
	c2sAcked  uint64 // server's cumulative ack of what we sent
	rx        *Reorder
	recv      []byte // s2c bytes reassembled in order (for byte-exact compare)
	s2cFinal  uint64
	s2cFinSet bool
	rst       bool
	lastAck   uint64
}

func newStriper(t *testing.T, serverAddr string, k int) *striper {
	s := &striper{t: t, rx: NewReorder(Window)}
	s.cond = sync.NewCond(&s.mu)
	var sid [16]byte
	rand.Read(sid[:])
	for i := 0; i < k; i++ {
		c, err := net.Dial("tcp", serverAddr)
		if err != nil {
			t.Fatalf("pipe %d dial: %v", i, err)
		}
		hello := Frame{Type: FrameHello, Payload: append(append([]byte(nil), sid[:]...), byte(i))}
		if _, err := c.Write(hello.Encode(nil)); err != nil {
			t.Fatalf("pipe %d hello: %v", i, err)
		}
		s.pipes = append(s.pipes, c)
	}
	for i := range s.pipes {
		go s.readPipe(s.pipes[i])
	}
	return s
}

// writeStriped sends one frame on the next pipe round-robin.
func (s *striper) writeStriped(f Frame) {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	p := s.pipes[s.cursor%len(s.pipes)]
	s.cursor++
	if _, err := p.Write(f.Encode(nil)); err != nil {
		s.t.Errorf("writeStriped: %v", err)
	}
}

func (s *striper) readPipe(c net.Conn) {
	for {
		f, err := ReadFrame(c)
		if err != nil {
			return
		}
		s.mu.Lock()
		switch f.Type {
		case FrameAck:
			if f.Seq > s.c2sAcked {
				s.c2sAcked = f.Seq
			}
		case FrameData:
			out, _ := s.rx.Insert(f.Seq, f.Payload)
			// rx returns contiguous bytes in order, so appending rebuilds the
			// exact s2c stream for a byte-for-byte compare in the test.
			s.recv = append(s.recv, out...)
			// Send our own cumulative ack so the server's tx window advances.
			d := s.rx.Delivered()
			if d-s.lastAck >= AckThreshold {
				s.lastAck = d
				go s.writeStriped(Frame{Type: FrameAck, FlowID: 1, Seq: d})
			}
		case FrameFin:
			s.s2cFinal = f.Seq
			s.s2cFinSet = true
		case FrameRst:
			s.rst = true
		}
		s.cond.Broadcast()
		s.mu.Unlock()
	}
}

// sendFlow opens flow 1 to the echo dest and streams payload c2s, gating on
// the server's acks so we never exceed Window outstanding.
func (s *striper) sendFlow(dest string, payload []byte) {
	s.writeStriped(Frame{Type: FrameOpen, FlowID: 1, Payload: []byte(dest)})
	var off uint64
	for off < uint64(len(payload)) {
		end := off + ChunkSize
		if end > uint64(len(payload)) {
			end = uint64(len(payload))
		}
		// window gate
		s.mu.Lock()
		for end-s.c2sAcked > Window && !s.rst {
			s.cond.Wait()
		}
		rst := s.rst
		s.mu.Unlock()
		if rst {
			return
		}
		s.writeStriped(Frame{Type: FrameData, FlowID: 1, Seq: off, Payload: payload[off:end]})
		off = end
	}
	s.writeStriped(Frame{Type: FrameFin, FlowID: 1, Seq: uint64(len(payload))})
}

// waitDone blocks until the whole s2c stream has been reassembled (delivered
// count == the FIN's finalSeq == wantLen) or timeout/RST. The reassembled
// bytes are in s.recv for a byte-exact compare.
func (s *striper) waitDone(wantLen int, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	s.mu.Lock()
	defer s.mu.Unlock()
	for {
		if s.rst {
			return false
		}
		if s.s2cFinSet && s.rx.Delivered() == s.s2cFinal && int(s.s2cFinal) == wantLen {
			return true
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return false
		}
		// Cond has no timed wait; nudge with a watchdog goroutine.
		done := make(chan struct{})
		timer := time.AfterFunc(remaining, func() {
			s.mu.Lock()
			s.cond.Broadcast()
			s.mu.Unlock()
			close(done)
		})
		s.cond.Wait()
		timer.Stop()
		select {
		case <-done:
		default:
		}
	}
}

func (s *striper) close() {
	for _, c := range s.pipes {
		c.Close()
	}
}

func TestStripeEchoMultiPipe(t *testing.T) {
	echo := startEcho(t)
	defer echo.Close()

	srvL, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("server listen: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	srv := NewServer(func(string, ...any) {}) // quiet
	go srv.Serve(ctx, srvL)

	// 2 MiB payload over 4 pipes — well past the 512 KiB window so the
	// window/ack machinery on BOTH halves is exercised, not bypassed.
	const N = 2 << 20
	payload := make([]byte, N)
	rand.Read(payload)

	c := newStriper(t, srvL.Addr().String(), 4)
	defer c.close()

	go c.sendFlow(echo.Addr().String(), payload)

	if !c.waitDone(N, 20*time.Second) {
		t.Fatalf("did not receive full echo: delivered=%d want=%d rst=%v",
			c.rx.Delivered(), N, c.rst)
	}
	c.mu.Lock()
	got := c.recv
	c.mu.Unlock()
	if !bytes.Equal(got, payload) {
		t.Fatalf("echoed bytes differ: got %d bytes, want %d (byte-exact mismatch)",
			len(got), len(payload))
	}
}

func TestStripeDialFailureSendsRst(t *testing.T) {
	srvL, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("server listen: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	srv := NewServer(func(string, ...any) {})
	go srv.Serve(ctx, srvL)

	c := newStriper(t, srvL.Addr().String(), 2)
	defer c.close()

	// Dial a port nothing listens on -> server should RST the flow.
	c.writeStriped(Frame{Type: FrameOpen, FlowID: 1, Payload: []byte("127.0.0.1:1")})
	c.writeStriped(Frame{Type: FrameData, FlowID: 1, Seq: 0, Payload: []byte("hello")})

	deadline := time.Now().Add(5 * time.Second)
	c.mu.Lock()
	for !c.rst && time.Now().Before(deadline) {
		done := make(chan struct{})
		timer := time.AfterFunc(time.Until(deadline), func() {
			c.mu.Lock()
			c.cond.Broadcast()
			c.mu.Unlock()
			close(done)
		})
		c.cond.Wait()
		timer.Stop()
	}
	got := c.rst
	c.mu.Unlock()
	if !got {
		t.Fatalf("expected RST after dial failure")
	}
}
