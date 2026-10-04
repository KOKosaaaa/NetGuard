package tunnel

// WB's video carrier is lossy. Keep the wire format compatible with older
// peers, but negotiate cumulative ACKs before using a bounded send window.
// ACKs are sent AFTER delivery to the socket, so TCP backpressure stays local
// to that connection rather than blocking the WebRTC reader or dropping bytes.
import (
	"encoding/binary"
	"github.com/pion/webrtc/v4/pkg/media"
	"time"
)

const (
	pcAck              = 0x12 // [type][count:u16][conn:u32, consumed-seq:u32]...
	pcHello            = 0x13 // [type][version=1][receive-window:u16]
	pcBundle           = 0x14 // [type][length:u32][control/data payload]...
	pcWindow           = 128
	pcMaxWindow        = 512
	pcRetryAfter       = 750 * time.Millisecond
	pcDeliveryLimit    = pcMaxWindow + 128
	pcSendByteLimit    = 8 * 1024 * 1024
	pcPendingByteLimit = 2 * 1024 * 1024
	pcMaxQueuedFrame   = 128 * 1024 // larger than the carrier's complete sample limit
)

type pcDelivery struct {
	seq   uint32
	frame []byte
}
type pcConsumer struct {
	queue          chan pcDelivery
	stop           chan struct{}
	sendGeneration uint64
}
type pcFlowState struct {
	// Protected by pcSendMu.
	peerACK        bool
	peerWindow     int
	peerBundle     bool
	srtt           time.Duration
	rttvar         time.Duration
	resent         map[uint64]bool
	acked          map[uint32]uint32
	sentAt         map[uint64]time.Time
	probeUntil     map[uint64]time.Time
	retry          map[uint64]bool
	pending        map[uint32][][]byte
	order          []uint32
	pendingCount   int
	pendingBytes   int
	sendBytes      int
	rr             int
	preferNew      bool // previous retry burst displaced an otherwise eligible unit
	space          chan struct{}
	sendClosed     map[uint32]time.Time
	sendGeneration uint64
	// Protected by pcRecvMu.
	recvGeneration uint64
	consumers      map[uint32]*pcConsumer
	consumed       map[uint32]uint32
	ackDirty       map[uint32]bool
	closed         map[uint32]time.Time
}

func newPCFlow() *pcFlowState {
	return &pcFlowState{resent: map[uint64]bool{}, peerWindow: pcWindow, space: make(chan struct{}), sendClosed: map[uint32]time.Time{}, probeUntil: map[uint64]time.Time{}, acked: map[uint32]uint32{}, sentAt: map[uint64]time.Time{}, retry: map[uint64]bool{}, pending: map[uint32][][]byte{}, consumers: map[uint32]*pcConsumer{}, consumed: map[uint32]uint32{}, ackDirty: map[uint32]bool{}, closed: map[uint32]time.Time{}}
}

func (t *VP8DataTunnel) pcEnqueue(fr []byte) {
	t.pcEnqueueInGeneration(fr, nil)
}

// An asynchronous terminal notification must not enter a replacement peer's
// sequence space after its originating receive generation was reset.
func (t *VP8DataTunnel) pcEnqueueInGeneration(fr []byte, expected *uint64) {
	if len(fr) < 9 {
		return
	}
	id := binary.BigEndian.Uint32(fr[4:8])
	if len(fr) > pcMaxQueuedFrame {
		t.pcAbort(id, "relay frame exceeds carrier sample limit")
		return
	}
	var generation uint64
	first := true
	if expected != nil {
		generation, first = *expected, false
	}
	for {
		select {
		case <-t.stopCh:
			return
		default:
		}
		t.pcSendMu.Lock()
		f := t.pcFlow
		if first {
			generation, first = f.sendGeneration, false
		}
		if _, closed := f.sendClosed[id]; closed || generation != f.sendGeneration {
			t.pcSendMu.Unlock()
			return
		}
		if len(f.pending[id]) < 256 && f.pendingCount < sendQueueDepth*2 && f.pendingBytes+len(fr) <= pcPendingByteLimit {
			if _, ok := f.pending[id]; !ok {
				f.order = append(f.order, id)
			}
			f.pending[id] = append(f.pending[id], append([]byte(nil), fr...))
			f.pendingCount++
			f.pendingBytes += len(fr)
			t.pcSendMu.Unlock()
			if fr[8] == MsgClose || fr[8] == MsgConnectErr {
				t.pcRetireLocalReceive(id, generation, time.Now())
			}
			return
		}
		space := f.space
		t.pcSendMu.Unlock()
		select {
		case <-space:
		case <-t.stopCh:
			return
		}
	}
}

func (t *VP8DataTunnel) pcWrapFrames(frames [][]byte) []byte {
	t.pcSendMu.Lock()
	defer t.pcSendMu.Unlock()
	return t.pcWrapLocked(frames, time.Now())
}

func (t *VP8DataTunnel) pcWrapLocked(frames [][]byte, now time.Time) []byte {
	out := []byte{pcData}
	for _, fr := range frames {
		if len(fr) < 9 {
			continue
		}
		id := binary.BigEndian.Uint32(fr[4:8])
		if _, closed := t.pcFlow.sendClosed[id]; closed {
			continue
		}
		t.pcSendSeq[id]++
		seq := t.pcSendSeq[id]
		u := make([]byte, 4+len(fr))
		binary.BigEndian.PutUint32(u, seq)
		copy(u[4:], fr)
		k := pcKey(id, seq)
		t.pcSendBuf[k] = u
		t.pcFlow.sendBytes += len(u)
		t.pcFlow.sentAt[k] = now
		t.pcFlow.probeUntil[k] = now.Add(10 * time.Second)
		t.pcSendFIFO = append(t.pcSendFIFO, k)
		// Legacy peers have no ACKs. Their retransmit cache is still bounded.
		for len(t.pcSendFIFO) > arqSendBufMax || t.pcFlow.sendBytes > pcSendByteLimit {
			old := t.pcSendFIFO[0]
			t.pcSendFIFO = t.pcSendFIFO[1:]
			t.pcFlow.sendBytes -= len(t.pcSendBuf[old])
			delete(t.pcSendBuf, old)
			delete(t.pcNackWin, old)
			delete(t.pcFlow.sentAt, old)
			delete(t.pcFlow.resent, old)
			delete(t.pcFlow.probeUntil, old)
			delete(t.pcFlow.retry, old)
		}
		out = append(out, u...)
		t.pcSentUnits.Add(1)
	}
	if len(out) == 1 {
		return nil
	}
	return out
}

func (t *VP8DataTunnel) pcHandle(p []byte) {
	if len(p) == 0 {
		return
	}
	switch p[0] {
	case pcData:
		t.pcParseData(p[1:])
	case pcNack:
		t.pcOnNack(p)
	case pcAck:
		t.pcOnAck(p)
	case pcHello:
		if len(p) == 4 && p[1] == 1 {
			w := int(binary.BigEndian.Uint16(p[2:]))
			if w < pcWindow || w > pcMaxWindow {
				return
			}
			t.pcSendMu.Lock()
			t.pcFlow.peerWindow = w
			t.pcFlow.peerBundle = true
			t.pcFlow.peerACK = true
			t.pcSendMu.Unlock()
		}
	case pcBundle:
		if packets := pcUnpack(p); packets != nil {
			for _, packet := range packets {
				t.pcHandle(packet)
			}
		}
	}
}

func (t *VP8DataTunnel) pcOnAck(p []byte) {
	t.pcOnAckAt(p, time.Now())
}

func (t *VP8DataTunnel) pcOnAckAt(p []byte, now time.Time) {
	if len(p) < 3 {
		return
	}
	n := int(binary.BigEndian.Uint16(p[1:3]))
	if n > arqMaxNack || len(p) != 3+n*8 {
		return
	}
	t.pcSendMu.Lock()
	defer t.pcSendMu.Unlock()
	t.pcFlow.peerACK = true
	for i := 0; i < n; i++ {
		b := p[3+i*8:]
		id, seq := binary.BigEndian.Uint32(b), binary.BigEndian.Uint32(b[4:])
		if seq > t.pcSendSeq[id] || seq <= t.pcFlow.acked[id] {
			continue
		}
		// Karn's rule: a retransmitted unit cannot identify which send this
		// ACK acknowledges. Sampling it would underestimate RTT and cause floods.
		k := pcKey(id, seq)
		if at, ok := t.pcFlow.sentAt[k]; ok && !t.pcFlow.resent[k] {
			t.pcObserveRTTLocked(now.Sub(at))
		}
		t.pcAckedUnits.Add(uint64(seq - t.pcFlow.acked[id]))
		t.pcFlow.acked[id] = seq
	}
	// Compact instead of retaining an ever-growing FIFO of ACKed keys.
	keys := t.pcSendFIFO[:0]
	var completed []uint32
	for _, k := range t.pcSendFIFO {
		if uint32(k) <= t.pcFlow.acked[uint32(k>>32)] {
			unit := t.pcSendBuf[k]
			if len(unit) >= 13 && (unit[12] == MsgClose || unit[12] == MsgConnectErr) {
				completed = append(completed, uint32(k>>32))
			}
			t.pcFlow.sendBytes -= len(t.pcSendBuf[k])
			delete(t.pcSendBuf, k)
			delete(t.pcNackWin, k)
			delete(t.pcFlow.sentAt, k)
			delete(t.pcFlow.resent, k)
			delete(t.pcFlow.probeUntil, k)
			delete(t.pcFlow.retry, k)
		} else {
			keys = append(keys, k)
		}
	}
	t.pcSendFIFO = keys
	for _, id := range completed {
		t.pcCancelSendLocked(id, now)
	}
}

func (t *VP8DataTunnel) pcAckPacket() []byte {
	t.pcRecvMu.Lock()
	defer t.pcRecvMu.Unlock()
	p := []byte{pcAck, 0, 0}
	n := 0
	for id := range t.pcFlow.ackDirty {
		var b [8]byte
		binary.BigEndian.PutUint32(b[:4], id)
		binary.BigEndian.PutUint32(b[4:], t.pcFlow.consumed[id])
		p = append(p, b[:]...)
		delete(t.pcFlow.ackDirty, id)
		n++
		if n == arqMaxNack {
			break
		}
	}
	binary.BigEndian.PutUint16(p[1:3], uint16(n))
	return p
}

func (t *VP8DataTunnel) pcParseData(body []byte) {
	for len(body) >= 13 {
		seq := binary.BigEndian.Uint32(body)
		rf := body[4:]
		size := int(binary.BigEndian.Uint32(rf))
		if seq == 0 || size < 5 || size > len(rf)-4 {
			return
		}
		fr := rf[:4+size]
		t.pcRecvData(binary.BigEndian.Uint32(fr[4:8]), seq, fr)
		body = rf[4+size:]
	}
}

func (t *VP8DataTunnel) pcRecvData(id, seq uint32, frame []byte) {
	select {
	case <-t.stopCh:
		return
	default:
	}
	t.pcRecvMu.Lock()
	if _, dead := t.pcFlow.closed[id]; dead {
		// Crossed local closes need a terminal ACK even after receive retirement.
		// Otherwise each side can keep retrying its final close forever.
		if len(frame) >= 9 && (frame[8] == MsgClose || frame[8] == MsgConnectErr) {
			if seq > t.pcFlow.consumed[id] {
				t.pcFlow.consumed[id] = seq
			}
			t.pcSendMu.Lock()
			t.pcCancelSendLocked(id, time.Now())
			t.pcSendMu.Unlock()
		}
		t.pcFlow.ackDirty[id] = true
		t.pcRecvMu.Unlock()
		return
	}
	exp := t.pcExpected[id]
	initialExp := exp
	if exp == 0 {
		exp = 1
		t.pcExpected[id] = 1
	}
	if seq < exp {
		t.pcFlow.ackDirty[id] = true
		t.pcRecvMu.Unlock()
		return
	}
	if seq > t.pcMaxSeen[id] {
		t.pcMaxSeen[id] = seq
	}
	rb := t.pcReorder[id]
	if rb == nil {
		rb = map[uint32][]byte{}
		t.pcReorder[id] = rb
	}
	if _, dup := rb[seq]; !dup {
		rb[seq] = append([]byte(nil), frame...)
	}
	if len(rb) > pcDeliveryLimit || len(t.pcExpected) > 2048 {
		reason := "connection receive window exceeded"
		if len(t.pcExpected) > 2048 {
			reason = "active receive connection limit exceeded"
		}
		t.pcRecvMu.Unlock()
		t.pcAbort(id, reason)
		return
	}
	c := t.pcFlow.consumers[id]
	if c == nil {
		// When both locks are needed the order is receive -> send. Callbacks
		// always run outside both locks; reset never holds send while taking receive.
		t.pcSendMu.Lock()
		generation := t.pcFlow.sendGeneration
		t.pcSendMu.Unlock()
		c = &pcConsumer{queue: make(chan pcDelivery, pcDeliveryLimit), stop: make(chan struct{}), sendGeneration: generation}
		t.pcFlow.consumers[id] = c
		go t.pcConsume(id, c)
	}
	for {
		fr, ok := rb[exp]
		if !ok {
			break
		}
		select {
		case c.queue <- pcDelivery{exp, fr}:
			delete(rb, exp)
			exp++
		default:
			t.pcRecvMu.Unlock()
			t.pcAbort(id, "socket delivery queue exceeded")
			return
		}
	}
	t.pcExpected[id] = exp
	if exp > t.pcMaxSeen[id] {
		delete(t.pcGapAt, id)
	} else if _, ok := t.pcGapAt[id]; !ok || exp != initialExp {
		t.pcGapAt[id] = time.Now()
	}
	t.pcRecvMu.Unlock()
}

func (t *VP8DataTunnel) pcConsume(id uint32, c *pcConsumer) {
	for {
		select {
		case <-t.stopCh:
			return
		case <-c.stop:
			return
		case d := <-c.queue:
			select {
			case <-c.stop:
				return
			case <-t.stopCh:
				return
			default:
			}
			if t.OnData != nil {
				t.OnData(d.frame)
			}
			t.pcRecvMu.Lock()
			if t.pcFlow.consumers[id] != c {
				t.pcRecvMu.Unlock()
				return
			}
			t.pcFlow.consumed[id] = d.seq
			t.pcFlow.ackDirty[id] = true
			closed := len(d.frame) >= 9 && (d.frame[8] == MsgClose || d.frame[8] == MsgConnectErr || d.frame[8] == MsgUDP || d.frame[8] == MsgUDPReply)
			if closed {
				now := time.Now()
				t.pcCloseLocked(id, now)
				if d.frame[8] == MsgClose || d.frame[8] == MsgConnectErr {
					t.pcCancelSend(id, c.sendGeneration, now)
				}
			}
			t.pcRecvMu.Unlock()
			if closed {
				return
			}
		}
	}
}

// A consumed remote close means its socket cannot accept our remaining bytes.
// Release that connection's debt, not a locally queued close: local data and
// close must still be delivered in order and retried until acknowledged. UDP
// receive completion is also different: its reverse reply still needs sending.
// The caller holds pcRecvMu so close observation and send cancellation are atomic
// to other receive operations. No send-locked path acquires pcRecvMu.
func (t *VP8DataTunnel) pcCancelSend(id uint32, generation uint64, now time.Time) {
	t.pcSendMu.Lock()
	defer t.pcSendMu.Unlock()
	f := t.pcFlow
	if generation != f.sendGeneration {
		return // a consumer from before reset must not cancel the new peer's ID
	}
	t.pcCancelSendLocked(id, now)
}

// Requires pcSendMu. Acknowledgement of a local terminal frame also releases
// the sender's per-ID cursors; keeping them forever leaks across short requests.
func (t *VP8DataTunnel) pcCancelSendLocked(id uint32, now time.Time) {
	f := t.pcFlow
	f.sendClosed[id] = now
	f.pendingCount -= len(f.pending[id])
	for _, frame := range f.pending[id] {
		f.pendingBytes -= len(frame)
	}
	delete(f.pending, id)
	order := f.order[:0]
	for _, queuedID := range f.order {
		if queuedID != id {
			order = append(order, queuedID)
		}
	}
	f.order = order
	if len(order) == 0 {
		f.rr = 0
	} else {
		f.rr %= len(order)
	}
	keys := t.pcSendFIFO[:0]
	for _, k := range t.pcSendFIFO {
		if uint32(k>>32) != id {
			keys = append(keys, k)
			continue
		}
		f.sendBytes -= len(t.pcSendBuf[k])
		delete(t.pcSendBuf, k)
		delete(t.pcNackWin, k)
		delete(f.sentAt, k)
		delete(f.probeUntil, k)
		delete(f.resent, k)
		delete(f.retry, k)
	}
	t.pcSendFIFO = keys
	delete(t.pcSendSeq, id)
	delete(f.acked, id)
	// In particular, wake a socket reader blocked on this ID's full queue.
	close(f.space)
	f.space = make(chan struct{})
}

// Relay MsgClose is a full socket close, not a TCP half-close. The peer may
// consume it and cancel its own queued close, so waiting for a reverse close
// leaks this receiver forever. Retire only the receive side here: all preceding
// outbound data and the terminal frame must remain reliable until acknowledged.
func (t *VP8DataTunnel) pcRetireLocalReceive(id uint32, generation uint64, now time.Time) {
	t.pcRecvMu.Lock()
	defer t.pcRecvMu.Unlock()
	t.pcSendMu.Lock()
	current := generation == t.pcFlow.sendGeneration
	t.pcSendMu.Unlock()
	if current {
		t.pcCloseLocked(id, now)
	}
}

func (t *VP8DataTunnel) pcCloseLocked(id uint32, now time.Time) {
	if c := t.pcFlow.consumers[id]; c != nil {
		close(c.stop)
		delete(t.pcFlow.consumers, id)
	}
	delete(t.pcExpected, id)
	delete(t.pcReorder, id)
	delete(t.pcMaxSeen, id)
	delete(t.pcGapAt, id)
	t.pcFlow.closed[id] = now
}

func (t *VP8DataTunnel) pcAbort(id uint32, reason string) {
	t.pcAbortInGeneration(id, reason, nil)
}

func (t *VP8DataTunnel) pcAbortInGeneration(id uint32, reason string, expected *uint64) {
	t.pcRecvMu.Lock()
	if expected != nil && *expected != t.pcFlow.recvGeneration {
		t.pcRecvMu.Unlock()
		return
	}
	if id == ControlConnID {
		// Linearize a fatal decision with peer reset while holding receive, but
		// notify the bridge/session outside every reliability lock.
		stopped := t.markStopped()
		t.pcRecvMu.Unlock()
		if stopped {
			t.logFn("carrier: control stream failed: %s", reason)
			if t.OnClose != nil {
				t.OnClose()
			}
		}
		return
	}
	if _, dead := t.pcFlow.closed[id]; dead {
		t.pcRecvMu.Unlock()
		return
	}
	t.pcCloseLocked(id, time.Now())
	t.pcSendMu.Lock()
	sendGeneration := t.pcFlow.sendGeneration
	t.pcSendMu.Unlock()
	t.pcRecvMu.Unlock()
	t.logFn("carrier: closing connection %d: %s (other connections stay up)", id, reason)
	// Closing a socket unblocks its consumer. Never wait for carrier capacity
	// on the WebRTC reader; a stalled send queue must not stop receiving ACKs.
	if t.OnData != nil {
		t.OnData(EncodeFrame(id, MsgClose, nil))
	}
	go t.pcEnqueueInGeneration(EncodeFrame(id, MsgClose, nil), &sendGeneration)
}

func (t *VP8DataTunnel) pcOnNack(p []byte) {
	if len(p) < 3 {
		return
	}
	n := int(binary.BigEndian.Uint16(p[1:3]))
	if n > arqMaxNack || len(p) != 3+n*8 {
		return
	}
	t.pcSendMu.Lock()
	defer t.pcSendMu.Unlock()
	for i := 0; i < n; i++ {
		b := p[3+i*8:]
		k := pcKey(binary.BigEndian.Uint32(b), binary.BigEndian.Uint32(b[4:]))
		if _, ok := t.pcSendBuf[k]; !ok {
			continue
		}
		if !t.pcNackWin[k] {
			t.pcNackWin[k] = true
			t.arqNacked.Add(1)
		}
		t.pcFlow.retry[k] = true // one pending retransmission, not 16KB copies every 60ms
	}
}

// pcNextData shares one byte budget between new bytes and retries. The caller
// carries a small amount of credit across ticks for an indivisible relay frame.
func (t *VP8DataTunnel) pcNextData(budget int, now time.Time) []byte {
	t.pcSendMu.Lock()
	defer t.pcSendMu.Unlock()
	f := t.pcFlow
	defer func() { close(f.space); f.space = make(chan struct{}) }()
	// The normal PCARQ producer uses pcEnqueue. This compatibility channel
	// has no peek operation: reserve a full maximum frame before reading it
	// rather than retaining an unaccounted overflow slot or dropping data.
	for f.pendingCount < sendQueueDepth && f.pendingBytes <= pcPendingByteLimit-pcMaxQueuedFrame {
		select {
		case fr := <-t.sendQueue:
			if len(fr) < 9 {
				continue
			}
			id := binary.BigEndian.Uint32(fr[4:8])
			if len(fr) > pcMaxQueuedFrame {
				go t.pcAbort(id, "relay frame exceeds carrier sample limit")
				continue
			}
			if _, closed := f.sendClosed[id]; closed {
				continue
			}
			if _, ok := f.pending[id]; !ok {
				f.order = append(f.order, id)
			}
			f.pending[id] = append(f.pending[id], fr)
			f.pendingCount++
			f.pendingBytes += len(fr)
		default:
			goto drained
		}
	}
drained:
	out := []byte{pcData}
	retryBudget := budget
	if f.preferNew {
		// A large indivisible retry can use the whole saved credit. Reserve
		// one sendable new unit on the next eligible turn; otherwise the
		// nominal half-tick split can starve another connection indefinitely.
		// Never reserve for an oversized or receive-window-blocked unit.
		for offset := 0; offset < len(f.order); offset++ {
			id := f.order[(f.rr+offset)%len(f.order)]
			q := f.pending[id]
			if len(q) == 0 {
				continue
			}
			blocked := f.peerACK && (t.pcSendSeq[id]-f.acked[id] >= uint32(t.pcWindowLocked()) || len(t.pcSendBuf) >= arqSendBufMax-1 || f.sendBytes+4+len(q[0]) > pcSendByteLimit)
			unitBytes := 4 + len(q[0])
			if !blocked && 1+unitBytes <= budget {
				retryBudget -= unitBytes
				break
			}
		}
	}
	// ACK timeout also recovers a lost LAST packet (there is no later packet
	// to expose that gap). With an old peer, probe its newest unit: receiving it
	// exposes earlier holes to the legacy NACK implementation.
	probed := map[uint32]bool{}
	rto := t.pcRTOLocked()
	for _, k := range t.pcSendFIFO {
		u := t.pcSendBuf[k]
		if u == nil {
			continue
		}
		id := uint32(k >> 32)
		age := now.Sub(f.sentAt[k])
		retry := f.retry[k] && age >= 200*time.Millisecond
		tail := age >= rto && !probed[id] && (f.peerACK || (uint32(k) == t.pcSendSeq[id] && now.Before(f.probeUntil[k])))
		if !retry && !tail {
			continue
		}
		if len(out)+len(u) > retryBudget {
			continue
		}
		out = append(out, u...)
		f.sentAt[k] = now
		f.resent[k] = true
		delete(f.retry, k)
		probed[id] = true
		// Reserve roughly half the tick for healthy connections when possible.
		if len(out) >= budget/2 {
			break
		}
	}
	retried := len(out) > 1
	newSent, newDisplaced := false, false
	misses := 0
	for len(f.order) > 0 && misses < len(f.order) && len(out) < budget {
		f.rr %= len(f.order)
		id := f.order[f.rr]
		q := f.pending[id]
		if len(q) == 0 {
			delete(f.pending, id)
			f.order = append(f.order[:f.rr], f.order[f.rr+1:]...)
			continue
		}
		fr := q[0]
		blocked := f.peerACK && (t.pcSendSeq[id]-f.acked[id] >= uint32(t.pcWindowLocked()) || len(t.pcSendBuf) >= arqSendBufMax-1 || f.sendBytes+4+len(fr) > pcSendByteLimit)
		if blocked || len(out)+4+len(fr) > budget {
			if !blocked && retried && 1+4+len(fr) <= budget {
				newDisplaced = true
			}
			f.rr++
			misses++
			continue
		}
		u := t.pcWrapLocked([][]byte{fr}, now)
		out = append(out, u[1:]...)
		q[0] = nil
		f.pending[id] = q[1:]
		f.pendingCount--
		f.pendingBytes -= len(fr)
		newSent = true
		f.rr++
		misses = 0
		t.sentData.Add(uint64(len(fr)))
	}
	if newSent {
		f.preferNew = false
	}
	if newDisplaced {
		f.preferNew = true
	}
	if len(out) == 1 {
		return nil
	}
	return out
}

func (t *VP8DataTunnel) pcNackLoop() {
	tick := time.NewTicker(200 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-t.stopCh:
			return
		case now := <-tick.C:
			t.pcCheckGaps(now)
		}
	}
}

func (t *VP8DataTunnel) pcCheckGaps(now time.Time) {
	// Let RTP NACK/RTX and ordinary reordering repair the video frame first.
	// Reporting a gap immediately counts a recoverable late frame as congestion.
	// Snapshot separately: receive paths may acquire send while holding recv.
	t.pcSendMu.Lock()
	grace := t.pcRTOLocked()
	t.pcSendMu.Unlock()
	if grace > 750*time.Millisecond {
		grace = 750 * time.Millisecond
	}
	t.pcRecvMu.Lock()
	generation := t.pcFlow.recvGeneration
	var expired []uint32
	p := []byte{pcNack, 0, 0}
	n := 0
	for id, at := range t.pcGapAt {
		if now.Sub(at) > arqSkipAfter {
			expired = append(expired, id)
			continue
		}
		if now.Sub(at) < grace {
			continue
		}
		for seq := t.pcExpected[id]; seq <= t.pcMaxSeen[id] && n < arqMaxNack; seq++ {
			if _, ok := t.pcReorder[id][seq]; ok {
				continue
			}
			var b [8]byte
			binary.BigEndian.PutUint32(b[:4], id)
			binary.BigEndian.PutUint32(b[4:], seq)
			p = append(p, b[:]...)
			n++
		}
	}
	// Closed cursors only outlive the retransmission horizon. Bound tombstones
	// under heavy short-lived HTTP/UDP traffic too.
	for id, at := range t.pcFlow.closed {
		if now.Sub(at) > 2*arqSkipAfter {
			delete(t.pcFlow.closed, id)
			delete(t.pcFlow.consumed, id)
			delete(t.pcFlow.ackDirty, id)
		}
	}
	// Publish under the same lock as reset/drain. Otherwise a scan from the
	// retired peer could enqueue its repair request after ResetPeerRestart.
	if n > 0 {
		binary.BigEndian.PutUint16(p[1:3], uint16(n))
		select {
		case t.ctrlQueue <- p:
		default:
		}
	}
	t.pcRecvMu.Unlock()
	t.pcSendMu.Lock()
	for id, at := range t.pcFlow.sendClosed {
		if now.Sub(at) > 2*arqSkipAfter {
			delete(t.pcFlow.sendClosed, id)
		}
	}
	t.pcSendMu.Unlock()
	for _, id := range expired {
		t.pcAbortInGeneration(id, "retransmission timed out", &generation)
	}
}

func pcAdjustedRate(cur, max int64, loss float64) int64 {
	if loss > 0.08 {
		cur = cur * 3 / 4
	} else if loss < 0.02 {
		cur += 150
	}
	floor := int64(200)
	if max < floor {
		floor = max
	}
	if cur < floor {
		cur = floor
	}
	if cur > max {
		cur = max
	}
	return cur
}

func (t *VP8DataTunnel) pcResetReceiveLocked() {
	t.pcFlow.recvGeneration++
	// PC NACK producers publish while holding pcRecvMu too, making this drain
	// atomic with retiring all cursors of the old peer.
	for draining := true; draining; {
		select {
		case <-t.ctrlQueue:
		default:
			draining = false
		}
	}
	for _, c := range t.pcFlow.consumers {
		close(c.stop)
	}
	t.pcFlow.consumers = map[uint32]*pcConsumer{}
	t.pcFlow.consumed = map[uint32]uint32{}
	t.pcFlow.ackDirty = map[uint32]bool{}
	t.pcFlow.closed = map[uint32]time.Time{}
}

func (t *VP8DataTunnel) pcWriterLoop(maxKbps int) {
	start := maxKbps
	if start > 1000 {
		start = 1000
	}
	t.dynRateKbps.Store(int64(start))
	go t.pcNackLoop()
	go t.arqRateLoop(maxKbps)
	fps := pcCarrierFPS()
	interval := time.Second / time.Duration(fps)
	tick := time.NewTicker(interval)
	defer tick.Stop()
	credit := 0
	ticks := 0
	for {
		select {
		case <-t.stopCh:
			return
		case now := <-tick.C:
			address := t.obf.RecipientSnapshot()
			ticks++
			budget := int(t.dynRateKbps.Load()) * 1000 / 8 / fps
			credit += budget
			if credit > budget*2+4096 {
				credit = budget*2 + 4096
			}
			t.pcSendMu.Lock()
			bundle := t.pcFlow.peerBundle
			t.pcSendMu.Unlock()
			var packets [][]byte
			ack := t.pcAckPacket()
			if len(ack) > 3 || ticks%fps == 1 {
				packets = append(packets, ack)
			}
			if ticks%fps == 1 {
				packets = append(packets, pcHelloPacket())
			}
			select {
			case p := <-t.ctrlQueue:
				packets = append(packets, p)
			default:
			}
			overhead := 0
			for _, p := range packets {
				credit -= len(p)
			}
			if bundle {
				overhead = 1 + 4*(len(packets)+1)
			}
			if payload := t.pcNextData(credit-overhead, now); len(payload) > 0 {
				packets = append(packets, payload)
				credit -= len(payload)
			}
			if bundle && len(packets) > 1 {
				credit -= 1 + 4*len(packets)
				packets = [][]byte{pcPack(packets)}
			}
			if len(packets) == 0 {
				packets = [][]byte{nil}
			}
			discovery := ticks%fps == 1 && t.obf.NeedsRecipientDiscovery()
			var health *addressedMediaControl
			select {
			case p := <-t.mediaControl:
				health = &p
			default:
			}
			sampleCount := len(packets)
			if discovery {
				sampleCount++
			}
			if health != nil {
				sampleCount++
			}
			durations := pcSampleDurations(sampleCount, interval)
			for i, p := range packets {
				var sample []byte
				if len(p) > 0 {
					sample = t.obf.EncodeDataFor(p, address)
				} else {
					sample = t.obf.EncodeKeepalive()
				}
				if sample != nil {
					if err := t.tracks[0].WriteSample(media.Sample{Data: sample, Duration: durations[i]}); err == nil {
						t.sentFrames.Add(1)
					}
				}
			}
			// A restarted exit has a new address. Sending only to its old epoch
			// would prevent both peers from discovering each other while idle.
			// This authenticated hello carries no relay data or readiness ACK.
			if discovery {
				hello := pcHelloPacket()
				credit -= len(hello)
				if sample := t.obf.EncodeDataFor(hello, 1<<32); sample != nil {
					_ = t.tracks[0].WriteSample(media.Sample{Data: sample, Duration: durations[len(packets)]})
				}
			}
			if health != nil {
				credit -= len(health.payload)
				if sample := t.obf.EncodeDataFor(health.payload, health.address); sample != nil {
					_ = t.tracks[0].WriteSample(media.Sample{Data: sample, Duration: durations[sampleCount-1]})
				}
			}
			if ticks%(fps*5) == 0 {
				t.pcRecvMu.Lock()
				activeReceive := len(t.pcExpected)
				consumers := len(t.pcFlow.consumers)
				t.pcRecvMu.Unlock()
				t.pcSendMu.Lock()
				t.logFn("carrier: WB flow bundle=%v rtt=%s rto=%s window=%d pending=%d unacked=%d recv=%d consumers=%d send_ids=%d", t.pcFlow.peerBundle, t.pcFlow.srtt, t.pcRTOLocked(), t.pcWindowLocked(), t.pcFlow.pendingCount, len(t.pcSendBuf), activeReceive, consumers, len(t.pcSendSeq))
				t.pcSendMu.Unlock()
			}
		}
	}
}

func (t *VP8DataTunnel) pcResetSend() {
	t.pcSendMu.Lock()
	t.pcFlow.sendGeneration++
	t.pcFlow.sendClosed = map[uint32]time.Time{}
	t.pcFlow.peerACK = false
	t.pcFlow.peerWindow = pcWindow
	t.pcFlow.peerBundle = false
	t.pcFlow.srtt = 0
	t.pcFlow.rttvar = 0
	t.pcFlow.resent = map[uint64]bool{}
	t.pcFlow.acked = map[uint32]uint32{}
	t.pcFlow.sentAt = map[uint64]time.Time{}
	t.pcFlow.probeUntil = map[uint64]time.Time{}
	t.pcFlow.retry = map[uint64]bool{}
	close(t.pcFlow.space)
	t.pcFlow.space = make(chan struct{})
	t.pcFlow.pending = map[uint32][][]byte{}
	t.pcFlow.order = nil
	t.pcFlow.pendingCount = 0
	t.pcFlow.pendingBytes = 0
	t.pcFlow.sendBytes = 0
	t.pcFlow.rr = 0
	t.pcFlow.preferNew = false
	t.pcSendSeq = make(map[uint32]uint32)
	t.pcSendBuf = make(map[uint64][]byte)
	t.pcSendFIFO = nil
	t.pcNackWin = make(map[uint64]bool)
	t.pcSendMu.Unlock()
}
