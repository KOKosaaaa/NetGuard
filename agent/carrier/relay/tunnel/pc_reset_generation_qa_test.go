package tunnel

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestPCQAExpiredScanCannotAbortReplacementControlOrSocket(t *testing.T) {
	peer := pcTestTunnel(t)
	peer.pcRecvData(ControlConnID, 2, EncodeFrame(ControlConnID, MsgConfigAck, nil))
	peer.pcRecvData(7, 2, EncodeFrame(7, MsgData, []byte("old gap")))
	peer.pcRecvMu.Lock()
	oldGeneration := peer.pcFlow.recvGeneration
	peer.pcRecvMu.Unlock()
	// Model a completed deadline scan paused before dispatching its aborts.
	peer.ResetPeerRestart()
	peer.pcRecvData(ControlConnID, 1, EncodeFrame(ControlConnID, MsgConfigAck, nil))
	peer.pcRecvData(7, 1, EncodeFrame(7, MsgData, []byte("replacement connection")))
	peer.pcAbortInGeneration(ControlConnID, "old scan", &oldGeneration)
	peer.pcAbortInGeneration(7, "old scan", &oldGeneration)
	peer.pcRecvMu.Lock()
	control, conn := peer.pcExpected[ControlConnID], peer.pcExpected[7]
	peer.pcRecvMu.Unlock()
	if !peer.running.Load() || control != 2 || conn != 2 {
		t.Fatalf("old scan killed replacement: running=%v control=%d socket=%d", peer.running.Load(), control, conn)
	}
}

func TestPCQACurrentFatalGapNotifiesOutsideReliabilityLocksExactlyOnce(t *testing.T) {
	peer := pcTestTunnel(t)
	var called atomic.Int32
	peer.OnClose = func() { called.Add(1); peer.ResetPeerRestart() }
	peer.pcRecvData(ControlConnID, 2, EncodeFrame(ControlConnID, MsgConfigAck, nil))
	peer.pcRecvMu.Lock()
	at := peer.pcGapAt[ControlConnID]
	peer.pcRecvMu.Unlock()
	done := make(chan struct{})
	go func() { peer.pcCheckGaps(at.Add(arqSkipAfter + time.Second)); peer.Stop(); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("fatal notification ran under receive/send lock")
	}
	if peer.running.Load() || called.Load() != 1 {
		t.Fatalf("fatal stop lost/duplicated: running=%v callbacks=%d", peer.running.Load(), called.Load())
	}
}

func TestPCQAConcurrentGapPublicationAndResetLeavesNoOldNack(t *testing.T) {
	peer := pcTestTunnel(t)
	for i := 0; i < 3000; i++ {
		peer.pcRecvData(7, 2, EncodeFrame(7, MsgData, []byte("gap pending repair")))
		peer.pcRecvMu.Lock()
		at := peer.pcGapAt[7]
		peer.pcRecvMu.Unlock()
		var wg sync.WaitGroup
		wg.Add(2)
		start := make(chan struct{})
		go func() { defer wg.Done(); <-start; peer.pcCheckGaps(at.Add(2 * time.Second)) }()
		go func() { defer wg.Done(); <-start; peer.ResetPeerRestart() }()
		close(start)
		wg.Wait()
		if len(peer.ctrlQueue) != 0 {
			t.Fatalf("old repair request survived reset at iteration%d", i)
		}
	}
}

func TestPCQAOldAbortTerminalCannotEnterNewSendGeneration(t *testing.T) {
	peer := pcTestTunnel(t)
	peer.pcSendMu.Lock()
	old := peer.pcFlow.sendGeneration
	peer.pcSendMu.Unlock()
	peer.ResetPeerRestart()
	peer.pcEnqueue(EncodeFrame(7, MsgData, []byte("new live socket")))
	peer.pcEnqueueInGeneration(EncodeFrame(7, MsgClose, nil), &old)
	peer.pcSendMu.Lock()
	count := peer.pcFlow.pendingCount
	frame := peer.pcFlow.pending[7][0]
	peer.pcSendMu.Unlock()
	peer.pcRecvMu.Lock()
	_, retired := peer.pcFlow.closed[7]
	peer.pcRecvMu.Unlock()
	if count != 1 || frame[8] != MsgData || retired {
		t.Fatal("old asynchronous close entered or retired replacement socket")
	}
}
