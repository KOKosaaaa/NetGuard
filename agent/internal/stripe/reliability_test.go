package stripe

import (
	"net"
	"testing"
	"time"
)

func TestFullFlowQueueDoesNotBlockSharedReader(t *testing.T) {
	s := newSession([16]byte{}, func(string, ...any) {})
	defer s.shutdown()
	fl := newFlow(1, s)
	for i := 0; i < cap(fl.inbound); i++ {
		fl.inbound <- Frame{Type: FrameData, FlowID: 1}
	}
	done := make(chan struct{})
	go func() { fl.deliver(Frame{Type: FrameData, FlowID: 1}); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("shared reader blocked")
	}
	if !fl.isClosed() {
		t.Fatal("overflow must reset the affected flow")
	}
}

func TestSlowPipeCannotBlockBroadcastToHealthyPipe(t *testing.T) {
	s := newSession([16]byte{}, func(string, ...any) {})
	defer s.shutdown()
	a, b := net.Pipe()
	defer b.Close()
	c, d := net.Pipe()
	defer d.Close()
	slow := s.addPipe(a)
	defer s.removePipe(slow)
	fast := s.addPipe(c)
	defer s.removePipe(fast)
	start := time.Now()
	s.broadcast(Frame{Type: FrameAck, FlowID: 1, Seq: 42})
	if time.Since(start) > 100*time.Millisecond {
		t.Fatal("broadcast waited for socket I/O")
	}
	d.SetReadDeadline(time.Now().Add(time.Second))
	f, err := ReadFrame(d)
	if err != nil || f.Seq != 42 {
		t.Fatalf("healthy pipe did not receive ACK: %v", err)
	}
}

func TestShortBurstGetsTimerAckAndNoEarlyRetransmit(t *testing.T) {
	s := newSession([16]byte{}, func(string, ...any) {})
	defer s.shutdown()
	a, b := net.Pipe()
	defer b.Close()
	p := s.addPipe(a)
	defer s.removePipe(p)
	fl := newFlow(1, s)
	defer fl.close()
	fl.rxDelivered.Store(10)
	fl.ackWanted.Store(true)
	fl.txNext = 10
	fl.unacked = []*txChunk{{off: 0, data: make([]byte, 10), sentAt: time.Now()}}
	go fl.txRetransmit()
	b.SetReadDeadline(time.Now().Add(time.Second))
	f, err := ReadFrame(b)
	if err != nil || f.Type != FrameAck || f.Seq != 10 {
		t.Fatalf("missing tail ACK: %v", err)
	}
	b.SetReadDeadline(time.Now().Add(700 * time.Millisecond))
	if _, err := ReadFrame(b); err == nil {
		t.Fatal("retransmitted before ACK budget elapsed")
	}
}

func TestAdaptiveRtoBackoffIsBounded(t *testing.T) {
	var r retransmissionTimer
	if r.current() <= 700*time.Millisecond {
		t.Fatal("RTO below ACK interval")
	}
	for i := 0; i < 20; i++ {
		r.backoff()
	}
	if r.current() != 30*time.Second {
		t.Fatal("unbounded backoff")
	}
	r.sample(800 * time.Millisecond)
	if r.current() < 1500*time.Millisecond || r.current() > 5*time.Second {
		t.Fatal("invalid RTT estimate")
	}
}
