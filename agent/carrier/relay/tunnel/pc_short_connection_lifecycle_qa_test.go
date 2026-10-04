package tunnel

import (
	"bytes"
	"encoding/binary"
	"runtime"
	"testing"
	"time"
)

// Exercise both wire directions. The recipient intentionally does NOT echo
// MsgClose: a consumed full close cancels its reverse pending/send debt.
func qaShortTransfer(t *testing.T, src, dst *VP8DataTunnel, delivered <-chan []byte, frame []byte) {
	t.Helper()
	src.pcEnqueue(frame)
	p := src.pcNextData(15000, time.Now())
	if len(p) < 14 {
		t.Fatal("no outbound packet for short connection")
	}
	id, seq := binary.BigEndian.Uint32(frame[4:8]), binary.BigEndian.Uint32(p[1:5])
	dst.pcHandle(p)
	select {
	case actual := <-delivered:
		if !bytes.Equal(actual, frame) {
			t.Fatalf("connection %d: wanted kind=%d, received kind=%d (admission/ordering failure)", id, frame[8], actual[8])
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("connection %d kind=%d delivery timed out", id, frame[8])
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		dst.pcRecvMu.Lock()
		consumed := dst.pcFlow.consumed[id]
		dst.pcRecvMu.Unlock()
		if consumed >= seq {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("connection %d consumption ACK never committed", id)
		}
		runtime.Gosched()
	}
	src.pcHandle(dst.pcAckPacket())
}

func TestPCQAShortConnectionsThousandsWithoutReciprocalClose(t *testing.T) {
	left, right := pcTestTunnel(t), pcTestTunnel(t)
	left.pcHandle(pcHelloPacket())
	right.pcHandle(pcHelloPacket())
	l, r := make(chan []byte, 8), make(chan []byte, 8)
	left.OnData = func(b []byte) { l <- append([]byte(nil), b...) }
	right.OnData = func(b []byte) { r <- append([]byte(nil), b...) }
	for id := uint32(1); id <= 2300; id++ {
		qaShortTransfer(t, left, right, r, EncodeFrame(id, MsgConnect, []byte("fixture.invalid:443")))
		qaShortTransfer(t, right, left, l, EncodeFrame(id, MsgConnectOK, nil))
		qaShortTransfer(t, left, right, r, EncodeFrame(id, MsgData, []byte{byte(id), byte(id >> 8)}))
		qaShortTransfer(t, left, right, r, EncodeFrame(id, MsgClose, nil))
	}
	for _, peer := range []*VP8DataTunnel{left, right} {
		peer.pcRecvMu.Lock()
		active, consumers, reorder := len(peer.pcExpected), len(peer.pcFlow.consumers), len(peer.pcReorder)
		peer.pcRecvMu.Unlock()
		if active != 0 || consumers != 0 || reorder != 0 {
			t.Fatalf("closed receive state retained: active=%d consumers=%d reorder=%d", active, consumers, reorder)
		}
		peer.pcSendMu.Lock()
		pending, cache, cursors := peer.pcFlow.pendingCount, len(peer.pcSendBuf), len(peer.pcSendSeq)
		peer.pcSendMu.Unlock()
		if pending != 0 || cache != 0 || cursors != 0 {
			t.Fatalf("closed send state retained: pending=%d cache=%d cursors=%d", pending, cache, cursors)
		}
		peer.pcCheckGaps(time.Now().Add(2*arqSkipAfter + time.Second))
		peer.pcRecvMu.Lock()
		retained := len(peer.pcFlow.closed) + len(peer.pcFlow.consumed) + len(peer.pcFlow.ackDirty)
		peer.pcRecvMu.Unlock()
		if retained != 0 {
			t.Fatalf("expired receive tombstones retained: %d", retained)
		}
	}
}

func TestPCQAShortLocalCloseRetiresReceiveButKeepsLostReliableTail(t *testing.T) {
	left, right := pcTestTunnel(t), pcTestTunnel(t)
	left.pcHandle(pcHelloPacket())
	right.pcHandle(pcHelloPacket())
	l, r := make(chan []byte, 8), make(chan []byte, 8)
	left.OnData = func(b []byte) { l <- append([]byte(nil), b...) }
	right.OnData = func(b []byte) { r <- append([]byte(nil), b...) }
	qaShortTransfer(t, left, right, r, EncodeFrame(7, MsgConnect, []byte("fixture.invalid:443")))
	qaShortTransfer(t, right, left, l, EncodeFrame(7, MsgConnectOK, nil))
	data, closeFrame := EncodeFrame(7, MsgData, []byte("last bytes before close")), EncodeFrame(7, MsgClose, nil)
	left.pcEnqueue(data)
	left.pcEnqueue(closeFrame)
	left.pcRecvMu.Lock()
	consumer, expected := left.pcFlow.consumers[7], left.pcExpected[7]
	left.pcRecvMu.Unlock()
	if consumer != nil || expected != 0 {
		t.Fatal("local full close retained its receive worker/cursor")
	}
	// A final reverse packet in flight must not resurrect the closed socket.
	left.pcHandle(right.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("late reverse bytes"))}))
	select {
	case <-l:
		t.Fatal("late reverse data delivered after local full close")
	default:
	}
	now := time.Now()
	packet := left.pcNextData(1+4+len(data), now)
	if len(packet) < 14 || !bytes.Equal(packet[5:], data) {
		t.Fatal("queued data lost or reordered by local close")
	}
	right.pcHandle(packet)
	select {
	case got := <-r:
		if !bytes.Equal(got, data) {
			t.Fatal("wrong data")
		}
	case <-time.After(time.Second):
		t.Fatal("data missing")
	}
	deadline := time.Now().Add(time.Second)
	for {
		right.pcRecvMu.Lock()
		consumed := right.pcFlow.consumed[7]
		right.pcRecvMu.Unlock()
		if consumed == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("last data consumption not committed")
		}
		runtime.Gosched()
	}
	left.pcHandle(right.pcAckPacket())
	lost := left.pcNextData(1+4+len(closeFrame), now)
	if len(lost) < 14 || !bytes.Equal(lost[5:], closeFrame) {
		t.Fatal("local close not transmitted after data")
	}
	// Lose the terminal packet. No new data comes to cause a gap NACK.
	retry := left.pcNextData(15000, now.Add(10*time.Second))
	if len(retry) == 0 {
		t.Fatal("local close ceased to retry after retiring receive state")
	}
	right.pcHandle(retry)
	qaPCWaitClosed(t, right, 7)
	left.pcHandle(right.pcAckPacket())
	left.pcSendMu.Lock()
	debt := len(left.pcSendBuf) + left.pcFlow.pendingCount
	left.pcSendMu.Unlock()
	if debt != 0 {
		t.Fatalf("ACKed tail retained send debt: %d", debt)
	}
	select {
	case got := <-r:
		if !bytes.Equal(got, closeFrame) {
			t.Fatal("terminal order changed")
		}
	case <-time.After(time.Second):
		t.Fatal("retried terminal missing")
	}
}

func TestPCQAShortLocalCloseFromDeliveryCallbackDoesNotDeadlock(t *testing.T) {
	peer := pcTestTunnel(t)
	done := make(chan struct{})
	peer.OnData = func([]byte) { peer.pcEnqueue(EncodeFrame(7, MsgClose, nil)); close(done) }
	peer.pcRecvData(7, 1, EncodeFrame(7, MsgData, []byte("socket write failed")))
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("local close from delivery callback deadlocked")
	}
	peer.pcRecvMu.Lock()
	defer peer.pcRecvMu.Unlock()
	if peer.pcExpected[7] != 0 || peer.pcFlow.consumers[7] != nil {
		t.Fatal("callback close left receive state alive")
	}
}

func TestPCQAShortStaleLocalRetirementCannotCloseRestartedID(t *testing.T) {
	peer := pcTestTunnel(t)
	peer.pcSendMu.Lock()
	oldGeneration := peer.pcFlow.sendGeneration
	peer.pcSendMu.Unlock()
	peer.ResetPeerRestart()
	done := make(chan struct{}, 1)
	peer.OnData = func([]byte) { done <- struct{}{} }
	peer.pcRecvData(7, 1, EncodeFrame(7, MsgData, []byte("new generation")))
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("new generation did not deliver")
	}
	peer.pcRetireLocalReceive(7, oldGeneration, time.Now())
	peer.pcRecvMu.Lock()
	defer peer.pcRecvMu.Unlock()
	if peer.pcExpected[7] == 0 || peer.pcFlow.consumers[7] == nil {
		t.Fatal("old local close retired reused ID after peer restart")
	}
}

func TestPCQAShortCrossedTerminalAndLostAckDoNotLeakOrResurrect(t *testing.T) {
	for _, terminal := range []byte{MsgClose, MsgConnectErr} {
		t.Run(string(rune('A'+terminal)), func(t *testing.T) {
			left, right := pcTestTunnel(t), pcTestTunnel(t)
			left.pcHandle(pcHelloPacket())
			right.pcHandle(pcHelloPacket())
			l, r := make(chan []byte, 8), make(chan []byte, 8)
			left.OnData = func(b []byte) { l <- append([]byte(nil), b...) }
			right.OnData = func(b []byte) { r <- append([]byte(nil), b...) }
			qaShortTransfer(t, left, right, r, EncodeFrame(7, MsgConnect, []byte("fixture.invalid:443")))
			qaShortTransfer(t, right, left, l, EncodeFrame(7, MsgConnectOK, nil))
			for _, peer := range []*VP8DataTunnel{left, right} {
				peer.pcEnqueue(EncodeFrame(7, MsgData, []byte("already in flight at full socket close")))
				peer.pcEnqueue(EncodeFrame(7, terminal, nil))
			}
			now := time.Now()
			lp, rp := left.pcNextData(15000, now), right.pcNextData(15000, now)
			if len(lp) < 14 || len(rp) < 14 {
				t.Fatal("crossed terminal packets were not emitted")
			}
			// Right receives a terminal after retiring its receive state. Its own
			// terminal was sent too but is delayed on the network. Lose the ACK.
			right.pcHandle(lp)
			lostAck := right.pcAckPacket()
			if len(lostAck) != 11 || binary.BigEndian.Uint32(lostAck[7:]) != 3 {
				t.Fatal("retired receiver did not acknowledge crossed terminal sequence")
			}
			left.pcSendMu.Lock()
			beforeRetry := len(left.pcSendBuf)
			left.pcSendMu.Unlock()
			if beforeRetry != 2 {
				t.Fatal("sender assumed an undelivered close ACK")
			}
			retry := left.pcNextData(15000, now.Add(10*time.Second))
			if len(retry) == 0 {
				t.Fatal("lost terminal ACK did not trigger recovery")
			}
			right.pcHandle(retry)
			repeatedAck := right.pcAckPacket()
			if !bytes.Equal(repeatedAck, lostAck) {
				t.Fatal("duplicate data/close did not repeat the terminal ACK")
			}
			left.pcHandle(repeatedAck)
			// Deliver the crossed terminal very late, then duplicate it and send
			// out-of-order nonterminal data beyond the known terminal sequence.
			left.pcHandle(rp)
			left.pcHandle(rp)
			left.pcRecvData(7, 100, EncodeFrame(7, MsgData, []byte("late nonterminal")))
			ack := left.pcAckPacket()
			if len(ack) != 11 || binary.BigEndian.Uint32(ack[7:]) != 3 {
				t.Fatal("late nonterminal packet advanced the closed cursor")
			}
			right.pcHandle(ack)
			for _, delivered := range []chan []byte{l, r} {
				select {
				case <-delivered:
					t.Fatal("closed connection delivered late payload/terminal twice")
				default:
				}
			}
			for _, peer := range []*VP8DataTunnel{left, right} {
				peer.pcRecvMu.Lock()
				active := len(peer.pcExpected) + len(peer.pcFlow.consumers) + len(peer.pcReorder)
				peer.pcRecvMu.Unlock()
				peer.pcSendMu.Lock()
				debt := len(peer.pcSendBuf) + peer.pcFlow.pendingCount + len(peer.pcSendSeq)
				peer.pcSendMu.Unlock()
				if active != 0 || debt != 0 {
					t.Fatalf("crossed terminal retained state: active=%d send=%d", active, debt)
				}
			}
		})
	}
}
