package tunnel

import (
	"testing"
	"time"
)

func qaPCCheckByteAccounting(t *testing.T, s *VP8DataTunnel) {
	t.Helper()
	s.pcSendMu.Lock()
	defer s.pcSendMu.Unlock()
	pending, cached, count := 0, 0, 0
	for _, q := range s.pcFlow.pending {
		for _, frame := range q {
			pending += len(frame)
			count++
		}
	}
	for _, unit := range s.pcSendBuf {
		cached += len(unit)
	}
	if pending != s.pcFlow.pendingBytes || cached != s.pcFlow.sendBytes || count != s.pcFlow.pendingCount {
		t.Fatalf("accounting mismatch pending actual=%d tracked=%d cache actual=%d tracked=%d count=%d/%d", pending, s.pcFlow.pendingBytes, cached, s.pcFlow.sendBytes, count, s.pcFlow.pendingCount)
	}
	if pending > pcPendingByteLimit || cached > pcSendByteLimit || pending < 0 || cached < 0 {
		t.Fatal("payload byte limit violated")
	}
}

func qaPCFillPendingBytes(t *testing.T, s *VP8DataTunnel) {
	t.Helper()
	for i := 0; i < pcPendingByteLimit/(4096+9); i++ {
		s.pcEnqueue(EncodeFrame(uint32(i%4+1), MsgData, make([]byte, 4096)))
	}
	qaPCCheckByteAccounting(t, s)
}

func TestPCQAByteBoundCachePreservesUnackedAndReleasesOnACK(t *testing.T) {
	s := pcTestTunnel(t)
	s.pcHandle(pcHelloPacket())
	s.dynRateKbps.Store(12000)
	s.pcFlow.srtt = time.Second
	now := time.Now()
	for i := 0; i < 2200; i++ {
		s.pcEnqueue(EncodeFrame(uint32(i%8+1), MsgData, make([]byte, 4096)))
		s.pcNextData(129096, now)
	}
	qaPCCheckByteAccounting(t, s)
	if len(s.pcSendBuf) != pcSendByteLimit/(4096+13) || s.pcFlow.pendingCount == 0 {
		t.Fatal("fixture did not fill byte-bounded cache before unit limit")
	}
	if s.pcSendBuf[pcKey(1, 1)] == nil {
		t.Fatal("ACK-enabled peer evicted unacknowledged reliable data")
	}
	before := s.pcFlow.sendBytes
	s.pcOnAck(qaPCSignal(pcAck, 1, s.pcSendSeq[1]))
	qaPCCheckByteAccounting(t, s)
	if s.pcFlow.sendBytes >= before {
		t.Fatal("ACK did not free byte quota")
	}
	pending := s.pcFlow.pendingCount
	s.pcNextData(129096, now)
	if s.pcFlow.pendingCount >= pending {
		t.Fatal("ACK release did not resume pending sends")
	}
	qaPCCheckByteAccounting(t, s)
}

func TestPCQALegacyNoACKCacheEvictsByBytesWithoutBlocking(t *testing.T) {
	s := pcTestTunnel(t)
	now := time.Now()
	for i := 0; i < 3000; i++ {
		s.pcEnqueue(EncodeFrame(7, MsgData, make([]byte, 4096)))
		if len(s.pcNextData(5000, now)) == 0 {
			t.Fatal("legacy producer blocked at byte limit")
		}
	}
	qaPCCheckByteAccounting(t, s)
	if s.pcSendBuf[pcKey(7, 1)] != nil || s.pcSendBuf[pcKey(7, 3000)] == nil || s.pcSendSeq[7] != 3000 {
		t.Fatal("legacy ring evicted wrong end or lost send progress")
	}
	if len(s.pcSendBuf) != len(s.pcSendFIFO) || len(s.pcFlow.sentAt) != len(s.pcSendBuf) {
		t.Fatal("eviction retained stale metadata")
	}
	s.pcResetSend()
	qaPCCheckByteAccounting(t, s)
	if s.pcFlow.sendBytes != 0 || s.pcFlow.pendingBytes != 0 {
		t.Fatal("reset retained byte debt")
	}
}

func TestPCQAByteBoundPendingBackpressureWakesOnDrainCloseAndReset(t *testing.T) {
	for _, action := range []string{"drain", "remote-close", "reset"} {
		t.Run(action, func(t *testing.T) {
			s := pcTestTunnel(t)
			s.pcWrapFrames([][]byte{EncodeFrame(1, MsgData, make([]byte, 4096)), EncodeFrame(2, MsgData, make([]byte, 4096))})
			qaPCFillPendingBytes(t, s)
			done := make(chan struct{})
			go func() { defer close(done); s.pcEnqueue(EncodeFrame(9, MsgData, make([]byte, 4096))) }()
			select {
			case <-done:
				t.Fatal("byte-full queue did not apply backpressure")
			case <-time.After(20 * time.Millisecond):
			}
			switch action {
			case "drain":
				s.pcNextData(4110, time.Now())
			case "remote-close":
				s.pcRecvData(1, 1, EncodeFrame(1, MsgClose, nil))
				qaPCWaitClosed(t, s, 1)
			case "reset":
				s.pcResetSend()
			}
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("quota release failed to wake producer")
			}
			qaPCCheckByteAccounting(t, s)
			if action == "remote-close" && (s.pcSendBuf[pcKey(1, 1)] != nil || len(s.pcFlow.pending[1]) != 0) {
				t.Fatal("remote close retained its byte debt")
			}
			if action == "reset" && (s.pcFlow.pendingBytes != 0 || s.pcFlow.sendBytes != 0) {
				t.Fatal("old blocked producer resurrected reset queue")
			}
		})
	}
}

func TestPCQACompatibilityDrainNeverExceedsPendingByteBound(t *testing.T) {
	s := pcTestTunnel(t)
	qaPCFillPendingBytes(t, s)
	for i := 0; i < 32; i++ {
		s.sendQueue <- EncodeFrame(17, MsgData, make([]byte, 4096))
	}
	s.pcNextData(0, time.Now())
	if len(s.sendQueue) != 32 {
		t.Fatal("drained compatibility queue without safe byte headroom")
	}
	qaPCCheckByteAccounting(t, s)
	for i := 0; i < 40; i++ {
		s.pcNextData(4110, time.Now())
	}
	s.pcNextData(0, time.Now())
	if len(s.sendQueue) == 32 {
		t.Fatal("compatibility drain did not resume after headroom released")
	}
	qaPCCheckByteAccounting(t, s)
}
