package tunnel

import (
	"encoding/binary"
	"testing"
	"time"
)

func qaPCGapStart(t *testing.T, s *VP8DataTunnel, id uint32) time.Time {
	t.Helper()
	s.pcRecvMu.Lock()
	defer s.pcRecvMu.Unlock()
	at, ok := s.pcGapAt[id]
	if !ok {
		t.Fatal("fixture did not create a missing first unit")
	}
	return at
}

func qaPCExpectNoNack(t *testing.T, s *VP8DataTunnel) {
	t.Helper()
	select {
	case p := <-s.ctrlQueue:
		t.Fatalf("premature application NACK/control: %x", p)
	default:
	}
}

func TestPCQAGapRepairedWithinRTTGraceDoesNotBecomeCongestionLoss(t *testing.T) {
	s, r := pcTestTunnel(t), pcTestTunnel(t)
	r.pcSendMu.Lock()
	r.pcFlow.srtt = 100 * time.Millisecond
	r.pcFlow.rttvar = 0
	r.pcSendMu.Unlock()
	s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("first")), EncodeFrame(7, MsgData, []byte("second"))})
	delivered := make(chan struct{}, 2)
	r.OnData = func([]byte) { delivered <- struct{}{} }
	// Later complete video frame arrives while Pion still recovers an RTP gap.
	r.pcRecvData(7, 2, EncodeFrame(7, MsgData, []byte("second")))
	at := qaPCGapStart(t, r, 7)
	for _, age := range []time.Duration{time.Millisecond, 100 * time.Millisecond, 249 * time.Millisecond} {
		r.pcCheckGaps(at.Add(age))
		select {
		case nack := <-r.ctrlQueue:
			s.pcOnNack(nack)
			t.Fatalf("reorder counted as a fresh loss after %s (fresh=%d)", age, s.arqNacked.Load())
		default:
		}
	}
	// Actual late delivery removes the gap before its grace deadline.
	r.pcRecvData(7, 1, EncodeFrame(7, MsgData, []byte("first")))
	for i := 0; i < 2; i++ {
		select {
		case <-delivered:
		case <-time.After(time.Second):
			t.Fatal("reordered data not delivered")
		}
	}
	r.pcCheckGaps(at.Add(time.Second))
	qaPCExpectNoNack(t, r)
	if s.arqNacked.Load() != 0 {
		t.Fatal("RTX/reorder healing reduced application congestion window")
	}
}

func TestPCQADurableGapNacksAfterBoundedAdaptiveGrace(t *testing.T) {
	for _, tc := range []struct {
		name                string
		srtt, rttvar, grace time.Duration
	}{
		{"floor", 30 * time.Millisecond, 0, 250 * time.Millisecond},
		{"measured", 200 * time.Millisecond, 50 * time.Millisecond, 400 * time.Millisecond},
		{"unknown", 0, 0, 750 * time.Millisecond},
		{"ceiling", 3 * time.Second, time.Second, 750 * time.Millisecond},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, r := pcTestTunnel(t), pcTestTunnel(t)
			r.pcSendMu.Lock()
			r.pcFlow.srtt = tc.srtt
			r.pcFlow.rttvar = tc.rttvar
			r.pcSendMu.Unlock()
			s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("missing")), EncodeFrame(7, MsgData, []byte("later"))})
			r.pcRecvData(7, 2, EncodeFrame(7, MsgData, []byte("later")))
			at := qaPCGapStart(t, r, 7)
			r.pcCheckGaps(at.Add(tc.grace - time.Nanosecond))
			qaPCExpectNoNack(t, r)
			r.pcCheckGaps(at.Add(tc.grace))
			select {
			case p := <-r.ctrlQueue:
				if len(p) != 11 || p[0] != pcNack || binary.BigEndian.Uint32(p[3:7]) != 7 || binary.BigEndian.Uint32(p[7:11]) != 1 {
					t.Fatalf("wrong durable-gap NACK: %x", p)
				}
				s.pcOnNack(p)
			default:
				t.Fatal("grace expired but durable loss was not NACKed")
			}
			if s.arqNacked.Load() != 1 {
				t.Fatal("genuine durable loss not counted once")
			}
			// A duplicated later frame cannot restart the age of the unresolved gap.
			r.pcRecvData(7, 2, EncodeFrame(7, MsgData, []byte("later")))
			r.pcCheckGaps(at.Add(tc.grace + 200*time.Millisecond))
			select {
			case p := <-r.ctrlQueue:
				s.pcOnNack(p)
			default:
				t.Fatal("duplicate later data indefinitely postponed durable gap")
			}
			if s.arqNacked.Load() != 1 {
				t.Fatal("repeated durable-gap NACK double counted loss")
			}
		})
	}
}

func TestPCQAGapGraceDoesNotDelayThirtySecondConnectionAbort(t *testing.T) {
	r := pcTestTunnel(t)
	closed := make(chan uint32, 2)
	r.OnData = func(b []byte) {
		if len(b) >= 9 && b[8] == MsgClose {
			closed <- binary.BigEndian.Uint32(b[4:8])
		}
	}
	r.pcSendMu.Lock()
	r.pcFlow.srtt = 30 * time.Second
	r.pcFlow.rttvar = 30 * time.Second
	r.pcSendMu.Unlock()
	r.pcRecvData(7, 2, EncodeFrame(7, MsgData, []byte("unrecoverable")))
	at := qaPCGapStart(t, r, 7)
	r.pcCheckGaps(at.Add(arqSkipAfter + time.Nanosecond))
	select {
	case id := <-closed:
		if id != 7 {
			t.Fatal("unrelated connection closed")
		}
	case <-time.After(time.Second):
		t.Fatal("grace postponed permanent failure timeout")
	}
	select {
	case <-r.stopCh:
		t.Fatal("one expired gap stopped entire tunnel")
	default:
	}
}

func TestPCQAGapGraceDoesNotChangeLostTailRTO(t *testing.T) {
	s := pcTestTunnel(t)
	s.pcOnAck([]byte{pcAck, 0, 0})
	s.pcSendMu.Lock()
	s.pcFlow.srtt = 100 * time.Millisecond
	s.pcFlow.rttvar = 25 * time.Millisecond
	s.pcSendMu.Unlock()
	s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("last packet without successor"))})
	s.pcSendMu.Lock()
	at := s.pcFlow.sentAt[pcKey(7, 1)]
	rto := s.pcRTOLocked()
	s.pcSendMu.Unlock()
	if packet := s.pcNextData(15000, at.Add(rto-time.Nanosecond)); len(packet) != 0 {
		t.Fatal("tail retransmission fired before sender RTO")
	}
	packet := s.pcNextData(15000, at.Add(rto))
	if len(packet) < 5 || packet[0] != pcData || binary.BigEndian.Uint32(packet[1:5]) != 1 {
		t.Fatal("tail RTO waited for a receive gap or its grace")
	}
}
