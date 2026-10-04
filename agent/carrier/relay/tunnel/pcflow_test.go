package tunnel

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"net"
	"sync"
	"testing"
	"time"
)

func pcTestTunnel(t *testing.T) *VP8DataTunnel {
	t.Helper()
	obf, _ := NewTunnelObfuscator([]byte("test"))
	v := NewVP8DataTunnelMulti(nil, obf, pcNoop)
	v.running.Store(true)
	t.Cleanup(v.Stop)
	return v
}

func pcFileLossRun(t *testing.T, upgraded bool) {
	s, r := pcTestTunnel(t), pcTestTunnel(t)
	s.pcOnAck([]byte{pcAck, 0, 0})
	if upgraded {
		s.pcHandle(pcHelloPacket())
		s.dynRateKbps.Store(2800)
		s.pcFlow.srtt = 900 * time.Millisecond
	}
	const count = 10000 // 11.26 MB: much larger than both 1.5 MB and the old cache.
	want := make([]byte, 1126*count)
	for i := range want {
		want[i] = byte(i*37 + i/1126)
	}
	var mu sync.Mutex
	var got bytes.Buffer
	r.OnData = func(b []byte) {
		DecodeFrames(b, func(_ uint32, typ byte, p []byte) {
			if typ == MsgData {
				mu.Lock()
				got.Write(p)
				mu.Unlock()
			}
		})
	}
	go func() {
		for i := 0; i < count; i++ {
			s.SendData(EncodeFrame(7, MsgData, want[i*1126:(i+1)*1126]))
		}
	}()
	attempts := map[uint32]int{}
	now := time.Now()
	deadline := now.Add(90 * time.Second) // slow Android emulator scheduling
	ticks := 0
	for time.Now().Before(deadline) {
		ticks++
		now = now.Add(time.Second / 24)
		p := s.pcNextData(6000, now)
		if len(p) > 6000 {
			t.Fatal("byte budget exceeded")
		}
		if len(p) > 0 {
			body := p[1:]
			for len(body) >= 13 {
				seq := binary.BigEndian.Uint32(body)
				n := int(binary.BigEndian.Uint32(body[4:])) + 8
				u := body[:n]
				body = body[n:]
				attempts[seq]++
				// Drop a burst just after 1.5 MB, initial packets beyond the old
				// warmup slack, random losses, and the LAST packet without a successor.
				drop := attempts[seq] == 1 && (seq <= 80 || (seq >= 1333 && seq < 1383) || seq%29 == 0 || seq == count)
				if !drop {
					r.pcHandle(append([]byte{pcData}, u...))
					if seq%47 == 0 {
						r.pcHandle(append([]byte{pcData}, u...))
					}
				}
			}
		}
		r.pcCheckGaps(time.Now())
		for len(r.ctrlQueue) > 0 {
			s.pcHandle(<-r.ctrlQueue)
		}
		ack := r.pcAckPacket()
		if ticks%11 != 0 {
			s.pcHandle(ack)
		} // ACK loss also recovers
		mu.Lock()
		done := got.Len() >= len(want)
		mu.Unlock()
		if done {
			break
		}
		time.Sleep(time.Millisecond)
	}
	mu.Lock()
	actual := append([]byte(nil), got.Bytes()...)
	mu.Unlock()
	if !bytes.Equal(actual, want) {
		t.Fatalf("file corrupted/stalled: got %d of %d bytes", len(actual), len(want))
	}
	if attempts[count] < 2 {
		t.Fatal("last packet was not recovered")
	}
	select {
	case <-s.stopCh:
		t.Fatal("sender tunnel stopped")
	case <-r.stopCh:
		t.Fatal("receiver tunnel stopped")
	default:
	}
	t.Logf("%d bytes delivered intact; SHA256 %x; last packet attempts=%d", len(actual), sha256.Sum256(actual), attempts[count])
}

func TestPCBlockedSocketDoesNotBlockOtherConnectionsOrACKReader(t *testing.T) {
	r := pcTestTunnel(t)
	s := pcTestTunnel(t)
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	entered := make(chan struct{})
	healthy := make(chan struct{}, 1)
	r.OnData = func(b []byte) {
		DecodeFrames(b, func(id uint32, typ byte, p []byte) {
			if id == 7 && typ == MsgData {
				select {
				case <-entered:
				default:
					close(entered)
				}
				_, _ = left.Write(p)
			}
			if id == 8 {
				healthy <- struct{}{}
			}
		})
	}
	r.pcRecvData(7, 1, EncodeFrame(7, MsgData, []byte("blocked")))
	<-entered
	r.pcRecvData(8, 1, EncodeFrame(8, MsgData, []byte("healthy")))
	select {
	case <-healthy:
	case <-time.After(time.Second):
		t.Fatal("one socket blocked all connections")
	}
	s.pcOnAck(r.pcAckPacket())
	s.pcSendMu.Lock()
	consumed := s.pcFlow.acked[7]
	capable := s.pcFlow.peerACK
	s.pcSendMu.Unlock()
	if consumed != 0 || !capable {
		t.Fatal("ACKed unconsumed data or blocked control processing")
	}
}

func TestPCSendWindowBackpressureAndFairness(t *testing.T) {
	s := pcTestTunnel(t)
	s.pcOnAck([]byte{pcAck, 0, 0})
	for i := 0; i < pcWindow+40; i++ {
		s.SendData(EncodeFrame(7, MsgData, make([]byte, 1126)))
	}
	s.SendData(EncodeFrame(8, MsgData, []byte("other")))
	now := time.Now()
	for i := 0; i < 60; i++ {
		s.pcNextData(6000, now)
	}
	if s.pcSendSeq[7] != pcWindow || s.pcSendSeq[8] != 1 {
		t.Fatalf("window/fairness failed: %v", s.pcSendSeq)
	}
	if len(s.pcSendBuf) > pcWindow+1 {
		t.Fatal("unbounded retransmission buffer")
	}
	ack := make([]byte, 11)
	ack[0] = pcAck
	binary.BigEndian.PutUint16(ack[1:], 1)
	binary.BigEndian.PutUint32(ack[3:], 7)
	binary.BigEndian.PutUint32(ack[7:], pcWindow)
	s.pcOnAck(ack)
	for i := 0; i < 10; i++ {
		s.pcNextData(6000, now)
	}
	if s.pcSendSeq[7] != pcWindow+40 {
		t.Fatal("ACK did not resume sender")
	}
}

func TestPCGapTimeoutClosesOnlyAffectedConnection(t *testing.T) {
	r := pcTestTunnel(t)
	closed := make(chan uint32, 2)
	good := make(chan struct{}, 1)
	r.OnData = func(b []byte) {
		DecodeFrames(b, func(id uint32, typ byte, _ []byte) {
			if typ == MsgClose {
				closed <- id
			} else if id == 8 {
				good <- struct{}{}
			}
		})
	}
	r.pcRecvData(7, 2, EncodeFrame(7, MsgData, []byte("gap")))
	r.pcRecvMu.Lock()
	r.pcGapAt[7] = time.Now().Add(-31 * time.Second)
	r.pcRecvMu.Unlock()
	r.pcCheckGaps(time.Now())
	select {
	case id := <-closed:
		if id != 7 {
			t.Fatal(id)
		}
	case <-time.After(time.Second):
		t.Fatal("did not close bad connection")
	}
	r.pcRecvData(7, 1, EncodeFrame(7, MsgData, []byte("late")))
	r.pcRecvData(8, 1, EncodeFrame(8, MsgData, []byte("ok")))
	select {
	case <-good:
	case <-time.After(time.Second):
		t.Fatal("healthy connection stopped")
	}
	select {
	case <-r.stopCh:
		t.Fatal("whole tunnel stopped")
	default:
	}
}

func TestPCNackDeduplicationAndRateBackoff(t *testing.T) {
	s := pcTestTunnel(t)
	s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, make([]byte, 1126))})
	p := make([]byte, 11)
	p[0] = pcNack
	binary.BigEndian.PutUint16(p[1:], 1)
	binary.BigEndian.PutUint32(p[3:], 7)
	binary.BigEndian.PutUint32(p[7:], 1)
	for i := 0; i < 1000; i++ {
		s.pcOnNack(p)
	}
	if len(s.pcFlow.retry) != 1 || len(s.ctrlQueue) != 0 {
		t.Fatal("NACK storm duplicated payload queues")
	}
	if n := len(s.pcNextData(1024, time.Now().Add(time.Second))); n != 0 {
		t.Fatalf("retry exceeded byte budget: %d", n)
	}
	if rate := pcAdjustedRate(2800, 2800, .20); rate >= 2800 {
		t.Fatal("default rate cannot back off")
	}
	if pcAdjustedRate(200, 2800, 1) != 200 || pcAdjustedRate(2800, 2800, 0) != 2800 {
		t.Fatal("invalid rate bounds")
	}
}

func TestPCPeerRestartDropsOldSendWindows(t *testing.T) {
	s := pcTestTunnel(t)
	s.pcOnAck([]byte{pcAck, 0, 0})
	for i := 0; i < 200; i++ {
		s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("old"))})
	}
	s.ResetPeerRestart()
	p := s.pcWrapFrames([][]byte{EncodeFrame(7, MsgConnectOK, nil)})
	if binary.BigEndian.Uint32(p[1:5]) != 1 || len(s.pcSendBuf) != 1 || s.pcFlow.peerACK {
		t.Fatal("new peer inherited old conn's sequence/window")
	}
}

func TestPCFileUploadSurvivesBurstAndLostTail(t *testing.T) {
	t.Run("legacy-window", func(t *testing.T) { pcFileLossRun(t, false) })
	t.Run("adaptive-window", func(t *testing.T) { pcFileLossRun(t, true) })
}
