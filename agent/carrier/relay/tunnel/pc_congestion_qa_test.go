package tunnel

import (
	"bytes"
	"encoding/binary"
	"strings"
	"sync"
	"testing"
	"time"
)

func qaPCSignal(kind byte, id, seq uint32) []byte {
	p := make([]byte, 11)
	p[0] = kind
	binary.BigEndian.PutUint16(p[1:3], 1)
	binary.BigEndian.PutUint32(p[3:7], id)
	binary.BigEndian.PutUint32(p[7:11], seq)
	return p
}

func TestPCQAKeyframeRequestsCoalesceWithoutChangingEpochOrPayload(t *testing.T) {
	if !carrierMode {
		t.Skip("requires WLB_VALID_VP8_TUNNEL=1")
	}
	for _, keepalive := range []bool{false, true} {
		enc, _ := NewTunnelObfuscator([]byte("qa-keyframe"))
		dec, _ := NewTunnelObfuscator([]byte("qa-keyframe"))
		epoch := enc.LocalEpoch()
		enc.EncodeKeepalive() // normal first keyframe
		if f := enc.EncodeKeepalive(); f[0]&1 == 0 {
			t.Fatal("fixture is not at an interframe")
		}
		var wg sync.WaitGroup
		for i := 0; i < 16; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for j := 0; j < 100; j++ {
					enc.RequestKeyframe()
				}
			}()
		}
		wg.Wait()
		payload := bytes.Repeat([]byte("authenticated data"), 300)
		var frame []byte
		if keepalive {
			frame = enc.EncodeKeepalive()
		} else {
			frame = enc.EncodeData(payload)
		}
		if frame[0]&1 != 0 {
			t.Fatal("next sample ignored coalesced keyframe request")
		}
		decoded := dec.Decode(frame)
		if !decoded.HasFrame || decoded.PeerEpoch != epoch || enc.LocalEpoch() != epoch || decoded.Keepalive != keepalive {
			t.Fatal("keyframe changed epoch or frame type")
		}
		if !keepalive && !bytes.Equal(decoded.Payload, payload) {
			t.Fatal("requested keyframe corrupted encrypted payload")
		}
		if f := enc.EncodeData(payload); f[0]&1 == 0 {
			t.Fatal("1600 requests were queued instead of coalesced")
		}
	}
}

func TestPCQAConcurrentCloseAckEnqueueAndResetDoesNotDeadlock(t *testing.T) {
	s := pcTestTunnel(t)
	var wg sync.WaitGroup
	for worker := 0; worker < 4; worker++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			for i := 0; i < 100; i++ {
				id := uint32(1 + i%16)
				switch worker {
				case 0:
					s.pcEnqueue(EncodeFrame(id, MsgData, []byte("test")))
					s.pcNextData(15000, time.Now())
				case 1:
					s.pcRecvData(id, 1, EncodeFrame(id, MsgClose, nil))
					s.pcCheckGaps(time.Now())
				case 2:
					s.pcOnAck(qaPCSignal(pcAck, id, 1))
					s.pcOnNack(qaPCSignal(pcNack, id, 1))
				case 3:
					s.ResetPeerRestart()
				}
			}
		}(worker)
	}
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		s.Stop()
		t.Fatal("receive/send/reset lock order deadlocked")
	}
	// Stop consumers before inspecting mutable receive state.
	s.ResetPeerRestart()
	s.pcSendMu.Lock()
	defer s.pcSendMu.Unlock()
	if s.pcFlow.pendingCount < 0 || len(s.pcSendBuf) > arqSendBufMax {
		t.Fatal("concurrent close corrupted queue accounting")
	}
}

func qaPCWaitClosed(t *testing.T, s *VP8DataTunnel, id uint32) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.pcRecvMu.Lock()
		_, closed := s.pcFlow.closed[id]
		s.pcRecvMu.Unlock()
		if closed {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("connection %d close was not consumed", id)
}

func TestPCQARemoteCloseReleasesOnlyItsDebtAndWakesBlockedEnqueue(t *testing.T) {
	for _, kind := range []byte{MsgClose, MsgConnectErr} {
		t.Run(string(rune('A'+kind)), func(t *testing.T) {
			s := pcTestTunnel(t)
			s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("dead")), EncodeFrame(8, MsgData, []byte("live"))})
			s.pcOnNack(qaPCSignal(pcNack, 7, 1))
			for i := 0; i < 256; i++ {
				s.pcEnqueue(EncodeFrame(7, MsgData, []byte("pending")))
			}
			s.pcEnqueue(EncodeFrame(8, MsgData, []byte("healthy pending")))
			done := make(chan struct{})
			go func() { defer close(done); s.pcEnqueue(EncodeFrame(7, MsgData, []byte("blocked"))) }()
			select {
			case <-done:
				t.Fatal("test did not fill per-ID queue")
			case <-time.After(20 * time.Millisecond):
			}
			s.pcRecvData(7, 1, EncodeFrame(7, kind, nil))
			qaPCWaitClosed(t, s, 7)
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("closed reader stayed blocked in enqueue")
			}
			// Queued legacy frames and late socket-reader sends must not recreate debt.
			s.sendQueue <- EncodeFrame(7, MsgData, []byte("legacy late"))
			s.pcEnqueue(EncodeFrame(7, MsgClose, nil))
			s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("direct late"))})
			s.pcNextData(15000, time.Now())
			s.pcSendMu.Lock()
			defer s.pcSendMu.Unlock()
			if s.pcFlow.pendingCount != 0 || len(s.pcFlow.pending[7]) != 0 || s.pcSendSeq[7] != 0 {
				t.Fatal("closed queue resurrected")
			}
			if len(s.pcSendBuf) != 2 || s.pcSendSeq[8] != 2 {
				t.Fatalf("healthy debt lost: units=%d seq=%v", len(s.pcSendBuf), s.pcSendSeq)
			}
			for _, k := range s.pcSendFIFO {
				if uint32(k>>32) == 7 {
					t.Fatal("closed FIFO retained")
				}
			}
			for _, m := range []map[uint64]bool{s.pcNackWin, s.pcFlow.resent, s.pcFlow.retry} {
				if m[pcKey(7, 1)] {
					t.Fatal("closed retry metadata retained")
				}
			}
			if _, ok := s.pcFlow.sentAt[pcKey(7, 1)]; ok {
				t.Fatal("sent timestamp retained")
			}
			if _, ok := s.pcFlow.probeUntil[pcKey(7, 1)]; ok {
				t.Fatal("probe timestamp retained")
			}
		})
	}
}

func TestPCQALocalClosePreservesOrderedDataAndRecoversLostTailUntilAck(t *testing.T) {
	s, r := pcTestTunnel(t), pcTestTunnel(t)
	s.pcOnAck([]byte{pcAck, 0, 0})
	delivered := make(chan []byte, 8)
	r.OnData = func(b []byte) { delivered <- append([]byte(nil), b...) }
	frames := [][]byte{EncodeFrame(7, MsgData, []byte("first")), EncodeFrame(7, MsgData, []byte("second")), EncodeFrame(7, MsgClose, nil)}
	for _, f := range frames {
		s.pcEnqueue(f)
	}
	now := time.Now()
	for i := 0; i < 3; i++ {
		packet := s.pcNextData(1+4+len(frames[i]), now)
		if len(packet) != 1+4+len(frames[i]) || binary.BigEndian.Uint32(packet[1:5]) != uint32(i+1) || !bytes.Equal(packet[5:], frames[i]) {
			t.Fatalf("local close changed ordering at %d", i)
		}
		if i < 2 {
			r.pcHandle(packet)
		} // intentionally lose final close, no later data
	}
	for i := 0; i < 2; i++ {
		select {
		case got := <-delivered:
			if !bytes.Equal(got, frames[i]) {
				t.Fatal("payload/order changed")
			}
		case <-time.After(time.Second):
			t.Fatal("data not delivered")
		}
	}
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.pcOnAck(r.pcAckPacket())
		if s.pcFlow.acked[7] == 2 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if len(s.pcSendBuf) != 1 || s.pcSendBuf[pcKey(7, 3)] == nil {
		t.Fatal("local close dropped before ACK")
	}
	retry := s.pcNextData(15000, now.Add(10*time.Second))
	if len(retry) < 5 || binary.BigEndian.Uint32(retry[1:5]) != 3 {
		t.Fatal("lost final close did not retry")
	}
	r.pcHandle(retry)
	qaPCWaitClosed(t, r, 7)
	select {
	case got := <-delivered:
		if !bytes.Equal(got, frames[2]) {
			t.Fatal("recovered tail changed")
		}
	case <-time.After(time.Second):
		t.Fatal("close missing")
	}
	s.pcOnAck(r.pcAckPacket())
	if len(s.pcSendBuf) != 0 || s.pcAckedUnits.Load() != 3 {
		t.Fatal("close ACK did not release reliable tail")
	}
	// A lost ACK causes a duplicate close; tombstone must repeat the final ACK.
	r.pcHandle(retry)
	ack := r.pcAckPacket()
	if len(ack) != 11 || binary.BigEndian.Uint32(ack[7:]) != 3 {
		t.Fatal("duplicate tail did not repeat close ACK")
	}
	select {
	case <-delivered:
		t.Fatal("duplicate close delivered twice")
	default:
	}
}

func TestPCQAUDPReceiveCompletionDoesNotCancelReverseReply(t *testing.T) {
	for _, kind := range []byte{MsgUDP, MsgUDPReply} {
		s := pcTestTunnel(t)
		s.pcWrapFrames([][]byte{EncodeFrame(7, MsgUDP, []byte("request"))})
		s.pcRecvData(7, 1, EncodeFrame(7, kind, []byte("received")))
		qaPCWaitClosed(t, s, 7)
		s.pcEnqueue(EncodeFrame(7, MsgUDPReply, []byte("reply")))
		s.pcNextData(15000, time.Now())
		if s.pcSendSeq[7] != 2 || len(s.pcSendBuf) != 2 {
			t.Fatal("UDP completion canceled its response")
		}
	}
}

func TestPCQAStaleConsumerCannotCancelResetSendGeneration(t *testing.T) {
	s := pcTestTunnel(t)
	entered, release, finished := make(chan struct{}), make(chan struct{}), make(chan struct{})
	s.OnData = func([]byte) { close(entered); <-release; close(finished) }
	s.pcRecvData(7, 1, EncodeFrame(7, MsgClose, nil))
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("consumer not started")
	}
	// Exercise the dangerous interval between send reset and receive reset.
	s.pcResetSend()
	s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("new peer data"))})
	close(release)
	<-finished
	qaPCWaitClosed(t, s, 7)
	s.pcSendMu.Lock()
	defer s.pcSendMu.Unlock()
	if _, dead := s.pcFlow.sendClosed[7]; dead || len(s.pcSendBuf) != 1 || s.pcSendSeq[7] != 1 {
		t.Fatal("old consumer canceled the new generation")
	}
}

func TestPCQACloseTombstonesExpireAndResetWithoutKeepingPayload(t *testing.T) {
	s := pcTestTunnel(t)
	s.pcRecvData(7, 1, EncodeFrame(7, MsgClose, nil))
	qaPCWaitClosed(t, s, 7)
	s.pcCheckGaps(time.Now().Add(2*arqSkipAfter + time.Second))
	if len(s.pcFlow.sendClosed) != 0 || len(s.pcFlow.closed) != 0 {
		t.Fatal("tombstones not bounded by retransmission horizon")
	}
	s.pcRecvData(8, 1, EncodeFrame(8, MsgClose, nil))
	qaPCWaitClosed(t, s, 8)
	s.ResetPeerRestart()
	if len(s.pcFlow.sendClosed) != 0 || len(s.pcFlow.closed) != 0 {
		t.Fatal("old peer tombstones survived reset")
	}
	s.pcEnqueue(EncodeFrame(8, MsgConnect, []byte("new")))
	s.pcNextData(15000, time.Now())
	if s.pcSendSeq[8] != 1 {
		t.Fatal("new peer could not reuse old closed ID")
	}
}

// This exercises the REAL interval rollover instead of reproducing the old
// map-reset implementation in the test. Re-reporting an unresolved gap is a
// retransmission request, not evidence of another newly lost original unit.
func TestPCQADuplicateNackAcrossRateIntervalIsNotNewLoss(t *testing.T) {
	if !carrierPCARQ {
		t.Skip("requires WLB_CARRIER_PCARQ=1")
	}
	s := pcTestTunnel(t)
	s.dynRateKbps.Store(2800)
	for seq := uint32(1); seq <= 8; seq++ {
		s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, make([]byte, 1126))})
		s.pcOnNack(qaPCSignal(pcNack, 7, seq))
	}
	if got := s.arqNacked.Load(); got != 8 {
		t.Fatalf("eight distinct missing units must count once each, got %d", got)
	}
	ticks := make(chan struct{}, 8)
	s.logFn = func(format string, _ ...any) {
		if strings.Contains(format, "ARQ-AIMD") {
			select {
			case ticks <- struct{}{}:
			default:
			}
		}
	}
	done := make(chan struct{})
	go func() { defer close(done); s.arqRateLoop(2800) }()
	defer func() {
		s.Stop()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("rate worker did not stop")
		}
	}()
	select {
	case <-ticks:
	case <-time.After(4 * time.Second):
		t.Fatal("real rate interval did not execute")
	}
	for i := 0; i < 20; i++ {
		s.pcOnNack(qaPCSignal(pcNack, 7, 1))
	}
	if got := s.arqNacked.Load(); got != 0 {
		t.Fatalf("same unacknowledged unit became fresh loss after interval rollover: %d", got)
	}
	s.pcSendMu.Lock()
	wantsRetry := s.pcFlow.retry[pcKey(7, 1)]
	s.pcSendMu.Unlock()
	if !wantsRetry {
		t.Fatal("deduplicating the loss signal must not suppress recovery requests")
	}
}

func TestPCQANewLossStillCountsWhileStaleAndImpossibleNacksDoNot(t *testing.T) {
	s := pcTestTunnel(t)
	s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("first"))})
	for i := 0; i < 20; i++ {
		s.pcOnNack(qaPCSignal(pcNack, 7, 1))
	}
	if got := s.arqNacked.Swap(0); got != 1 {
		t.Fatalf("duplicate initial NACKs counted %d losses", got)
	}
	s.pcOnAck(qaPCSignal(pcAck, 7, 1))
	s.pcOnNack(qaPCSignal(pcNack, 7, 1))
	s.pcOnNack(qaPCSignal(pcNack, 7, 9999))
	s.pcOnNack(qaPCSignal(pcNack, 9999, 1))
	if got := s.arqNacked.Load(); got != 0 {
		t.Fatalf("stale/impossible NACK counted loss: %d", got)
	}
	s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("second"))})
	s.pcOnNack(qaPCSignal(pcNack, 7, 2))
	if got := s.arqNacked.Load(); got != 1 {
		t.Fatalf("distinct missing unit must still count: %d", got)
	}
}

func TestPCQARetryPressureDoesNotStarveHealthyConnectionOrExceedBudget(t *testing.T) {
	s := pcTestTunnel(t)
	s.dynRateKbps.Store(2800)
	s.pcOnAck([]byte{pcAck, 0, 0}) // conservative legacy receive window
	start := time.Now()
	frames := make([][]byte, pcWindow)
	for i := range frames {
		frames[i] = EncodeFrame(7, MsgData, make([]byte, 1126))
	}
	s.pcWrapFrames(frames)
	for seq := uint32(1); seq <= pcWindow; seq++ {
		s.pcOnNack(qaPCSignal(pcNack, 7, seq))
	}
	for i := 0; i < 180; i++ {
		s.pcEnqueue(EncodeFrame(8, MsgData, make([]byte, 1126)))
	}
	const budget = 15000
	for tick := 0; tick < 30; tick++ {
		now := start.Add(2*time.Second + time.Duration(tick)*time.Second/24)
		before := s.pcSendSeq[8]
		packet := s.pcNextData(budget, now)
		if len(packet) > budget {
			t.Fatalf("retry+new data exceeded byte budget: %d > %d", len(packet), budget)
		}
		if before < 180 && s.pcSendSeq[8] == before {
			t.Fatalf("healthy stream made no progress at tick %d under unrelated retries", tick)
		}
		if s.pcSendSeq[8] > 0 {
			s.pcOnAckAt(qaPCSignal(pcAck, 8, s.pcSendSeq[8]), now.Add(50*time.Millisecond))
		}
	}
	if s.pcSendSeq[8] < 150 {
		t.Fatalf("healthy stream unfairly throttled: %d/180 units", s.pcSendSeq[8])
	}
	if s.pcSendSeq[7] != pcWindow {
		t.Fatal("retransmission allocated new sequence numbers")
	}
	if len(s.pcSendBuf) > arqSendBufMax {
		t.Fatal("unacknowledged cache bound exceeded")
	}
}

func TestPCQARateBoundsAndCleanRecoveryPreserveConfiguredCeiling(t *testing.T) {
	for _, cap := range []int64{100, 200, 1000, 2800} {
		rate := cap
		for i := 0; i < 80; i++ {
			rate = pcAdjustedRate(rate, cap, 1)
			floor := int64(200)
			if cap < floor {
				floor = cap
			}
			if rate < floor || rate > cap {
				t.Fatalf("loss response outside [%d,%d]: %d", floor, cap, rate)
			}
		}
		for i := 0; i < 40; i++ {
			previous := rate
			rate = pcAdjustedRate(rate, cap, 0)
			if rate < previous || rate > cap {
				t.Fatalf("clean recovery regressed or exceeded cap: %d -> %d (cap=%d)", previous, rate, cap)
			}
		}
		if rate != cap {
			t.Fatalf("clean rate failed to recover within 40 intervals: %d, cap=%d", rate, cap)
		}
	}
}

func TestPCQAWindowRateStepClampsImpossibleRatioAndHistoricEWMA(t *testing.T) {
	for _, previous := range []float64{0, 0.8, 64} {
		for _, sent := range []uint64{0, 1, 2} {
			next, ewma := pcRateStep(2800, 2800, sent, 64, 0, previous)
			if ewma < 0 || ewma > 1 {
				t.Fatalf("unbounded loss EWMA=%v for sent=%d previous=%v", ewma, sent, previous)
			}
			if next < 200 || next >= 2800 {
				t.Fatalf("fresh large loss must reduce rate within bounds: %d", next)
			}
		}
	}
}

func TestPCQAOldLossCannotReduceRateWithoutNewLossAndDeliveredTrafficRecovers(t *testing.T) {
	rate, ewma := int64(400), 0.99
	for tick := 0; tick < 20; tick++ {
		previous := rate
		rate, ewma = pcRateStep(rate, 2800, 80, 0, 80, ewma)
		if rate <= previous && rate < 2800 {
			t.Fatalf("old loss trapped successful traffic at tick %d: %d -> %d EWMA=%v", tick, previous, rate, ewma)
		}
		if rate > 2800 {
			t.Fatal("recovery exceeded configured room cap")
		}
	}
	if rate != 2800 {
		t.Fatalf("clean ACK-confirmed traffic failed bounded recovery: %d", rate)
	}
}

func TestPCQAIdleControlAndUnacknowledgedProbesCannotDriveRate(t *testing.T) {
	for _, input := range []struct{ sent, nacked, acked uint64 }{{0, 0, 0}, {1, 0, 1}, {1, 1, 0}, {7, 7, 0}, {200, 0, 0}} {
		for _, previous := range []float64{0, 0.99} {
			next, ewma := pcRateStep(1000, 2800, input.sent, input.nacked, input.acked, previous)
			if next != 1000 {
				t.Fatalf("idle/minimal/unconfirmed traffic changed rate: %+v previous=%v -> %d", input, previous, next)
			}
			if ewma < 0 || ewma > 1 {
				t.Fatalf("invalid EWMA: %v", ewma)
			}
		}
	}
}

func TestPCQAFreshBulkLossStillBacksOffAndAllRateStepsRespectCaps(t *testing.T) {
	next, _ := pcRateStep(2800, 2800, 128, 32, 0, 0)
	if next >= 2800 {
		t.Fatal("bulk packet loss no longer reduces pacing")
	}
	for _, cap := range []int64{100, 200, 1000, 2800} {
		for _, loss := range []uint64{0, 1, 8, 64, 10000} {
			next, ewma := pcRateStep(cap, cap, 64, loss, 64, 0.5)
			floor := int64(200)
			if cap < floor {
				floor = cap
			}
			if next < floor || next > cap || ewma < 0 || ewma > 1 {
				t.Fatalf("rate bounds violated: cap=%d loss=%d next=%d EWMA=%v", cap, loss, next, ewma)
			}
		}
	}
}

func TestPCQAAckProgressCountsOnlyNewConfirmedUnitsAndSurvivesReset(t *testing.T) {
	s := pcTestTunnel(t)
	for i := 0; i < 4; i++ {
		s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("x"))})
	}
	s.pcOnAck(qaPCSignal(pcAck, 7, 3))
	if got := s.pcAckedUnits.Load(); got != 3 {
		t.Fatalf("cumulative ACK must count three unique units: %d", got)
	}
	for _, seq := range []uint32{3, 3, 2, 0, 9999} {
		s.pcOnAck(qaPCSignal(pcAck, 7, seq))
	}
	s.pcOnAck(qaPCSignal(pcAck, 9999, 1))
	if got := s.pcAckedUnits.Load(); got != 3 {
		t.Fatalf("duplicate/stale/impossible ACK changed delivery counter: %d", got)
	}
	s.pcOnAck(qaPCSignal(pcAck, 7, 4))
	if got := s.pcAckedUnits.Load(); got != 4 {
		t.Fatalf("new ACK delta must be one, total=%d", got)
	}
	s.pcResetSend()
	if got := s.pcAckedUnits.Load(); got != 4 {
		t.Fatalf("reset made rate-loop counter nonmonotonic: %d", got)
	}
	s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("new epoch"))})
	s.pcOnAck(qaPCSignal(pcAck, 7, 1))
	if got := s.pcAckedUnits.Load(); got != 5 {
		t.Fatalf("fresh sequence space failed to add exactly one delivery: %d", got)
	}
}

func TestPCQALossDedupeStorageIsReleasedOnAckEvictionAndReset(t *testing.T) {
	s := pcTestTunnel(t)
	for i := 0; i < 100; i++ {
		s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("unit"))})
		seq := s.pcSendSeq[7]
		s.pcOnNack(qaPCSignal(pcNack, 7, seq))
		s.pcOnAck(qaPCSignal(pcAck, 7, seq))
	}
	if len(s.pcNackWin) != 0 {
		t.Fatalf("ACKed loss history retained indefinitely: %d keys", len(s.pcNackWin))
	}
	for i := 0; i < arqSendBufMax+50; i++ {
		s.pcWrapFrames([][]byte{EncodeFrame(8, MsgData, []byte("unit"))})
		s.pcOnNack(qaPCSignal(pcNack, 8, s.pcSendSeq[8]))
	}
	if len(s.pcNackWin) > len(s.pcSendBuf) {
		t.Fatalf("evicted loss history grew beyond live retransmit cache: %d > %d", len(s.pcNackWin), len(s.pcSendBuf))
	}
	for key := range s.pcNackWin {
		if s.pcSendBuf[key] == nil {
			t.Fatalf("loss dedupe retained evicted unit %d", key)
		}
	}
	s.pcResetSend()
	if len(s.pcNackWin) != 0 {
		t.Fatal("old peer's loss history survived reset")
	}
}

// Remote close already ends that application's socket. Keeping its undeliverable
// send debt forever eventually consumes the GLOBAL ARQ cap, blocking other IDs.
// No room/server is involved: bounded local peers and the real sender scheduler.
func TestPCQARemotelyClosedConnectionsCannotExhaustGlobalWindow(t *testing.T) {
	s := pcTestTunnel(t)
	s.pcOnAck([]byte{pcAck, 0, 0})
	s.dynRateKbps.Store(2800)
	s.OnData = func([]byte) {}
	remaining := arqSendBufMax - 1
	for id := uint32(1); remaining > 0; id++ {
		count := pcWindow
		if remaining < count {
			count = remaining
		}
		frames := make([][]byte, count)
		for i := range frames {
			frames[i] = EncodeFrame(id, MsgData, make([]byte, 1126))
		}
		s.pcWrapFrames(frames)
		s.pcRecvData(id, 1, EncodeFrame(id, MsgClose, nil))
		deadline := time.Now().Add(time.Second)
		closed := false
		for time.Now().Before(deadline) {
			s.pcRecvMu.Lock()
			_, closed = s.pcFlow.closed[id]
			s.pcRecvMu.Unlock()
			if closed {
				break
			}
			time.Sleep(time.Millisecond)
		}
		if !closed {
			t.Fatalf("remote close for connection %d was not consumed", id)
		}
		remaining -= count
	}
	const healthy = uint32(1000)
	s.pcEnqueue(EncodeFrame(healthy, MsgData, []byte("healthy request must leave")))
	start := time.Now()
	for tick := 0; tick < 48 && s.pcSendSeq[healthy] == 0; tick++ {
		packet := s.pcNextData(15000, start.Add(time.Duration(tick)*time.Second/24))
		if len(packet) > 15000 {
			t.Fatal("send budget exceeded")
		}
	}
	if s.pcSendSeq[healthy] == 0 {
		t.Fatalf("closed connections stranded %d unacknowledged units and blocked healthy traffic for 2 virtual seconds", len(s.pcSendBuf))
	}
}
