package tunnel

import (
	"bytes"
	"encoding/binary"
	"testing"
	"time"
)

// Each SOCKS UDP datagram receives a new connection ID. Exercise an actual
// request/reply/consumption-ACK cycle, independently of receive tombstones.
func TestPCQAUDPThousandsOfCompletedRoundTripsReleaseSendCursors(t *testing.T) {
	left, right := pcTestTunnel(t), pcTestTunnel(t)
	left.pcHandle(pcHelloPacket())
	right.pcHandle(pcHelloPacket())
	leftDelivered, rightDelivered := make(chan []byte, 8), make(chan []byte, 8)
	left.OnData = func(frame []byte) { leftDelivered <- append([]byte(nil), frame...) }
	right.OnData = func(frame []byte) { rightDelivered <- append([]byte(nil), frame...) }
	const requests = 2300
	for id := uint32(1); id <= requests; id++ {
		qaShortTransfer(t, left, right, rightDelivered, EncodeFrame(id, MsgUDP, []byte("request")))
		qaShortTransfer(t, right, left, leftDelivered, EncodeFrame(id, MsgUDPReply, []byte("reply")))
	}
	// A delayed duplicate reply within the reliability horizon must be
	// acknowledged without delivering the UDP response to the application twice.
	duplicate := binary.BigEndian.AppendUint32([]byte{pcData}, 1)
	duplicate = append(duplicate, EncodeFrame(requests, MsgUDPReply, []byte("reply"))...)
	left.pcHandle(duplicate)
	select {
	case <-leftDelivered:
		t.Fatal("a delayed duplicate UDP reply was delivered twice")
	default:
	}
	left.pcRecvMu.Lock()
	duplicateAck := left.pcFlow.ackDirty[requests] && left.pcFlow.consumed[requests] == 1
	left.pcRecvMu.Unlock()
	if !duplicateAck {
		t.Fatal("a delayed duplicate UDP reply was not acknowledged")
	}
	right.pcHandle(left.pcAckPacket())

	// Expire closed RECEIVE IDs using the explicit clock accepted by the
	// scanner. This must not hide persistent SEND cursors behind tombstones.
	expiry := time.Now().Add(2*arqSkipAfter + time.Second)
	for name, peer := range map[string]*VP8DataTunnel{"requester": left, "responder": right} {
		peer.pcNextData(15000, time.Now()) // remove drained pending queue entries
		peer.pcCheckGaps(expiry)
		peer.pcRecvMu.Lock()
		active, consumers, reorder := len(peer.pcExpected), len(peer.pcFlow.consumers), len(peer.pcReorder)
		tombstones := len(peer.pcFlow.closed) + len(peer.pcFlow.consumed) + len(peer.pcFlow.ackDirty)
		peer.pcRecvMu.Unlock()
		if active != 0 || consumers != 0 || reorder != 0 || tombstones != 0 {
			t.Fatalf("%s receive cleanup failed: active=%d consumers=%d reorder=%d tombstones=%d", name, active, consumers, reorder, tombstones)
		}
		peer.pcSendMu.Lock()
		pending, pendingBytes, cache, cacheBytes := peer.pcFlow.pendingCount, peer.pcFlow.pendingBytes, len(peer.pcSendBuf), peer.pcFlow.sendBytes
		cursors, acked := len(peer.pcSendSeq), len(peer.pcFlow.acked)
		peer.pcSendMu.Unlock()
		if pending != 0 || pendingBytes != 0 || cache != 0 || cacheBytes != 0 {
			t.Fatalf("%s still owns completed UDP payload: pending=%d pendingBytes=%d cache=%d cacheBytes=%d", name, pending, pendingBytes, cache, cacheBytes)
		}
		if cursors != 0 || acked != 0 {
			t.Errorf("%s retains send cursors after %d ACKed UDP round trips and receive expiry: seqIDs=%d ackIDs=%d", name, requests, cursors, acked)
		}
	}
}

func TestPCQAUDPLostACKsKeepRetriesAndReverseReplyReliable(t *testing.T) {
	left, right := pcTestTunnel(t), pcTestTunnel(t)
	left.pcHandle(pcHelloPacket())
	right.pcHandle(pcHelloPacket())
	leftDelivered, rightDelivered := make(chan []byte, 8), make(chan []byte, 8)
	left.OnData = func(frame []byte) { leftDelivered <- append([]byte(nil), frame...) }
	right.OnData = func(frame []byte) { rightDelivered <- append([]byte(nil), frame...) }
	const id = uint32(17)
	left.pcEnqueue(EncodeFrame(id, MsgUDP, []byte("request")))
	request := left.pcNextData(15000, time.Now())
	if len(request) == 0 {
		t.Fatal("request was not emitted")
	}
	right.pcHandle(request)
	qaPCWaitClosed(t, right, id)
	if len(rightDelivered) != 1 {
		t.Fatal("request did not reach its consumer exactly once")
	}
	<-rightDelivered
	_ = right.pcAckPacket() // lose the first request consumption ACK
	right.pcEnqueue(EncodeFrame(id, MsgUDPReply, []byte("reply")))
	reply := right.pcNextData(15000, time.Now())
	if len(reply) == 0 {
		t.Fatal("receiving a request suppressed its reverse reply")
	}
	left.pcHandle(reply)
	qaPCWaitClosed(t, left, id)
	if len(leftDelivered) != 1 {
		t.Fatal("reply did not reach its consumer exactly once")
	}
	<-leftDelivered
	_ = left.pcAckPacket() // lose the first reply consumption ACK
	if len(left.pcSendBuf) != 1 || len(right.pcSendBuf) != 1 {
		t.Fatal("UDP send debt disappeared before its ACK")
	}
	retryAt := time.Now().Add(6 * time.Second) // beyond the maximum RTO
	requestRetry := left.pcNextData(15000, retryAt)
	if !bytes.Equal(requestRetry, request) {
		t.Fatal("lost request ACK did not preserve the exact retry")
	}
	right.pcHandle(requestRetry)
	if len(rightDelivered) != 0 || len(right.pcSendBuf) != 1 {
		t.Fatal("duplicate request was delivered again or canceled the unACKed reverse reply")
	}
	left.pcHandle(right.pcAckPacket())
	if len(left.pcSendBuf) != 0 || len(left.pcSendSeq) != 0 || len(left.pcFlow.acked) != 0 {
		t.Fatal("recovered request ACK did not retire only its send state")
	}
	replyRetry := right.pcNextData(15000, retryAt)
	if !bytes.Equal(replyRetry, reply) {
		t.Fatal("lost reply ACK did not preserve the exact retry")
	}
	left.pcHandle(replyRetry)
	if len(leftDelivered) != 0 {
		t.Fatal("duplicate reply was delivered to the application twice")
	}
	right.pcHandle(left.pcAckPacket())
	if len(right.pcSendBuf) != 0 || len(right.pcSendSeq) != 0 || len(right.pcFlow.acked) != 0 {
		t.Fatal("recovered reply ACK did not retire its send state")
	}
}
