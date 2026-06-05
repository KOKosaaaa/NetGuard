package stripe

import (
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// txChunk is one sent-but-not-yet-acked s2c chunk, retained so it can be
// resent if the pipe it went on dies (the substrate has no retransmit of its
// own and Telemost rooms drop often). pid is the id of the pipe it was last
// sent on (-1 = not yet sent), so a pipe death resends only its own chunks.
type txChunk struct {
	off  uint64
	data []byte
	pid  int // pipe id this chunk was last sent on (-1 = unsent), for dead-pipe resend
}

// flow is one application connection multiplexed over the session's pipes.
//
//   - rxLoop  (c2s): client frames are reassembled in offset order and written
//     to the real dest; we Ack our cumulative delivered offset back.
//   - txReader (s2c): we read the dest, chunk it, stripe chunks across pipes,
//     window-gated on the client's Acks.
//   - txRetransmit (s2c): a backstop that resends the unacked window when Acks
//     stop advancing (a room died mid-transfer and took in-flight chunks with
//     it). Go-Back-N style — resend everything still unacked; the receiver
//     dedups by offset. This is what keeps one dead room from stalling the
//     whole striped flow to zero.
type flow struct {
	id   uint32
	sess *session

	dest     net.Conn
	dialOnce sync.Once
	dialErr  error
	dialed   chan struct{}

	inbound chan Frame    // c2s Data/Fin frames, processed by rxLoop in arrival order
	closed  chan struct{} // closed exactly once by close()

	// rx (c2s) state — touched only by rxLoop.
	rx          *Reorder
	rxFinal     uint64
	rxFinSet    bool
	lastAckSent uint64

	// tx (s2c) state — guarded by txMu/txCond.
	txMu       sync.Mutex
	txCond     *sync.Cond
	txBase     uint64 // cumulative bytes acked by the client (window left edge)
	txNext     uint64 // next offset to assign
	unacked    []*txChunk
	finReached bool
	finSeq     uint64
	finPid     int   // pipe the Fin was last sent on (-1 = unsent)
	homePid    int   // pinned home pipe id while the flow is small (-1 = unassigned)
	lastPosMs  int64 // unix ms of the last position report we sent (c2s)

	rxDone atomic.Bool
	txDone atomic.Bool

	lastProg  atomic.Int64
	closeOnce sync.Once
}

func newFlow(id uint32, sess *session) *flow {
	fl := &flow{
		id:      id,
		sess:    sess,
		dialed:  make(chan struct{}),
		inbound: make(chan Frame, 256),
		closed:  make(chan struct{}),
		rx:      NewReorder(MaxReorder),
		finPid:  -1,
		homePid: -1,
	}
	fl.txCond = sync.NewCond(&fl.txMu)
	return fl
}

func (fl *flow) start() {
	fl.touch()
	go fl.rxLoop()
	go fl.reaper()
	go fl.txRetransmit()
}

// txRetransmit is the timer backstop the design always called for but that was
// missing: onPipeDead only fires when a pipe's CONNECTION closes, but a
// Telemost room often goes ZOMBIE — the carrier still accepts our writes
// locally (so pipe.write succeeds, p.dead stays false, no close event) while
// the SFU silently drops them. send() keeps round-robining ~1/N of the stream
// onto that black hole, the receiver's reorder stalls at the first missing
// offset, and goodput collapses to ~0 with no death event to trigger a resend.
// So we watch the cumulative Ack (txBase): if it stops advancing while chunks
// are still unacked, we Go-Back-N resend the whole unacked window across the
// live pipes (the receiver dedups by offset). Each round reshuffles which pipe
// carries which chunk, so the healthy pipes drain the window even if one stays
// a zombie. Bounded: we only resend after sustained no-progress, then wait a
// full window again before the next round, so a merely-slow Ack never storms.
func (fl *flow) txRetransmit() {
	const tick = 200 * time.Millisecond
	const stallTicks = 3 // ~600ms of zero Ack progress with data outstanding
	t := time.NewTicker(tick)
	defer t.Stop()
	var lastBase uint64
	stalls := 0
	for {
		select {
		case <-fl.closed:
			return
		case <-t.C:
			fl.txMu.Lock()
			base := fl.txBase
			resend := append([]*txChunk(nil), fl.unacked...)
			resendFin := fl.finReached
			finSeq := fl.finSeq
			fl.txMu.Unlock()

			if len(resend) == 0 {
				lastBase = base
				stalls = 0
				continue
			}
			if base != lastBase {
				// Progress is happening — not stalled.
				lastBase = base
				stalls = 0
				continue
			}
			stalls++
			if stalls < stallTicks {
				continue
			}
			stalls = 0 // resend once, then require another stall window
			for _, ch := range resend {
				pid := fl.sess.send(Frame{Type: FrameData, FlowID: fl.id, Seq: ch.off, Payload: ch.data})
				fl.txMu.Lock()
				ch.pid = pid
				fl.txMu.Unlock()
			}
			if resendFin {
				fl.sess.send(Frame{Type: FrameFin, FlowID: fl.id, Seq: finSeq})
			}
			fl.touch()
		}
	}
}

func (fl *flow) touch()              { fl.lastProg.Store(time.Now().UnixNano()) }
func (fl *flow) lastProgress() int64 { return fl.lastProg.Load() }

func nowMs() int64 { return time.Now().UnixMilli() }

func (fl *flow) isClosed() bool {
	select {
	case <-fl.closed:
		return true
	default:
		return false
	}
}

// deliver routes a frame the session handed us. Data/Fin queue for rxLoop;
// Ack advances the tx window (and frees acked chunks); Rst tears down.
func (fl *flow) deliver(f Frame) {
	switch f.Type {
	case FrameOpen:
		fl.setDest(string(f.Payload))
	case FrameData, FrameFin:
		select {
		case fl.inbound <- f:
		case <-fl.closed:
		}
	case FrameAck:
		fl.onAck(f.Seq)
	case FrameRst:
		fl.close()
	}
}

// onAck advances the window left edge and drops fully-acked chunks from the
// retransmit buffer. Acks are cumulative, so we take the max.
func (fl *flow) onAck(cum uint64) {
	fl.txMu.Lock()
	if cum > fl.txBase {
		fl.txBase = cum
		i := 0
		for i < len(fl.unacked) && fl.unacked[i].off+uint64(len(fl.unacked[i].data)) <= fl.txBase {
			i++
		}
		if i > 0 {
			fl.unacked = append(fl.unacked[:0:0], fl.unacked[i:]...)
		}
		fl.txCond.Broadcast()
	}
	done := fl.finReached && fl.txBase >= fl.txNext
	fl.txMu.Unlock()
	fl.touch()
	if done {
		fl.markTxDone()
	}
}

// setDest dials the real destination once (on the first Open). c2s Data that
// arrived before the Open simply waits in the reorder buffer; rxLoop blocks
// on dialed before it writes anything out.
func (fl *flow) setDest(addr string) {
	fl.dialOnce.Do(func() {
		go func() {
			d := net.Dialer{Timeout: dialTimeout}
			conn, err := d.Dial("tcp", addr)
			if err != nil {
				fl.dialErr = err
				close(fl.dialed)
				fl.sess.logf("stripe: flow %d dial %s failed: %v", fl.id, addr, err)
				fl.abort()
				return
			}
			if tc, ok := conn.(*net.TCPConn); ok {
				_ = tc.SetNoDelay(true)
			}
			fl.dest = conn
			close(fl.dialed)
			go fl.txReader()
		}()
	})
}

// rxLoop reassembles client->server bytes and writes them to dest.
func (fl *flow) rxLoop() {
	select {
	case <-fl.dialed:
	case <-fl.closed:
		return
	}
	if fl.dialErr != nil {
		for {
			select {
			case <-fl.inbound:
			case <-fl.closed:
				return
			}
		}
	}
	for {
		select {
		case f := <-fl.inbound:
			switch f.Type {
			case FrameData:
				out, err := fl.rx.Insert(f.Seq, f.Payload)
				if err != nil {
					fl.abort()
					return
				}
				if len(out) > 0 {
					if _, werr := fl.dest.Write(out); werr != nil {
						fl.abort()
						return
					}
					fl.touch()
				}
				fl.maybePos()
				if fl.checkRxFin() {
					return
				}
			case FrameFin:
				fl.rxFinal = f.Seq
				fl.rxFinSet = true
				if fl.checkRxFin() {
					return
				}
			}
		case <-fl.closed:
			return
		}
	}
}

// maybePos sends a cumulative position report at most every posIntervalMs —
// a single tiny frame, NOT per-chunk. It is only so the peer can resend a
// dead pipe's still-unconfirmed chunks; it does NOT drive flow control (the
// pipes' own backpressure does that). Broadcast so a dead pipe can't swallow
// it; at <2/sec the cost is negligible.
func (fl *flow) maybePos() {
	now := nowMs()
	if now-fl.lastPosMs < posIntervalMs {
		return
	}
	fl.lastPosMs = now
	fl.sess.broadcast(Frame{Type: FrameAck, FlowID: fl.id, Seq: fl.rx.Delivered()})
}

func (fl *flow) checkRxFin() bool {
	if !fl.rxFinSet || fl.rx.Delivered() != fl.rxFinal {
		return false
	}
	if cw, ok := fl.dest.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
	fl.sess.broadcast(Frame{Type: FrameAck, FlowID: fl.id, Seq: fl.rx.Delivered()})
	fl.markRxDone()
	return true
}

// txReader reads dest, retains each chunk (for dead-pipe resend), and stripes
// it across the pipes. No window gate: pacing + the pipes' own write
// backpressure throttle us. The only gate is a generous retain cap so memory
// stays bounded if position reports stop arriving (e.g. uplink dead).
func (fl *flow) txReader() {
	buf := make([]byte, ChunkSize)
	for {
		fl.txMu.Lock()
		for fl.txNext-fl.txBase >= MaxReorder {
			if fl.isClosed() {
				fl.txMu.Unlock()
				return
			}
			fl.txCond.Wait()
		}
		fl.txMu.Unlock()
		if fl.isClosed() {
			return
		}

		n, err := fl.dest.Read(buf)
		if n > 0 {
			data := append([]byte(nil), buf[:n]...)
			fl.txMu.Lock()
			off := fl.txNext
			fl.txNext += uint64(n)
			ch := &txChunk{off: off, data: data, pid: -1}
			fl.unacked = append(fl.unacked, ch)
			fl.txMu.Unlock()
			// Small flows ride one pinned pipe (reliable, no cross-pipe gap);
			// only once a flow proves bulk do we stripe it across all pipes.
			// Blocks on a full/slow pipe (pacing + SCTP backpressure) = flow
			// control.
			f := Frame{Type: FrameData, FlowID: fl.id, Seq: off, Payload: data}
			var pid int
			if off < PromoteThreshold {
				pid = fl.sess.sendPinned(&fl.homePid, f)
			} else {
				pid = fl.sess.send(f)
			}
			fl.txMu.Lock()
			ch.pid = pid
			fl.txMu.Unlock()
			fl.touch()
		}
		if err != nil {
			fl.txMu.Lock()
			fl.finReached = true
			fl.finSeq = fl.txNext
			done := fl.txBase >= fl.txNext
			fl.txMu.Unlock()
			pid := fl.sess.send(Frame{Type: FrameFin, FlowID: fl.id, Seq: fl.finSeq})
			fl.txMu.Lock()
			fl.finPid = pid
			fl.txMu.Unlock()
			if done {
				fl.markTxDone()
			}
			return
		}
	}
}

// onPipeDead resends only the chunks that were riding the dead pipe (plus any
// never-sent chunk) onto a live pipe — the chunks the substrate just lost. No
// blind timer, so a merely-slow Ack never triggers a resend storm; the
// receiver dedups resends by offset.
func (fl *flow) onPipeDead(deadID int) {
	fl.txMu.Lock()
	var resend []*txChunk
	for _, ch := range fl.unacked {
		if ch.pid == deadID || ch.pid == -1 {
			resend = append(resend, ch)
		}
	}
	resendFin := fl.finReached && (fl.finPid == deadID || fl.finPid == -1)
	finSeq := fl.finSeq
	fl.txMu.Unlock()

	for _, ch := range resend {
		pid := fl.sess.send(Frame{Type: FrameData, FlowID: fl.id, Seq: ch.off, Payload: ch.data})
		fl.txMu.Lock()
		ch.pid = pid
		fl.txMu.Unlock()
	}
	if resendFin {
		pid := fl.sess.send(Frame{Type: FrameFin, FlowID: fl.id, Seq: finSeq})
		fl.txMu.Lock()
		fl.finPid = pid
		fl.txMu.Unlock()
	}
	if len(resend) > 0 || resendFin {
		fl.touch()
	}
}

func (fl *flow) markRxDone() {
	fl.rxDone.Store(true)
	fl.maybeFinish()
}

func (fl *flow) markTxDone() {
	fl.txDone.Store(true)
	fl.maybeFinish()
}

func (fl *flow) maybeFinish() {
	if fl.rxDone.Load() && fl.txDone.Load() {
		fl.close()
	}
}

// reaper closes a flow that has made no progress for flowIdle — the final
// backstop if even retransmission can't recover (e.g. every pipe gone).
func (fl *flow) reaper() {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-fl.closed:
			return
		case <-t.C:
			if time.Since(time.Unix(0, fl.lastProgress())) > flowIdle {
				fl.sess.logf("stripe: flow %d idle-reaped", fl.id)
				fl.abort()
				return
			}
		}
	}
}

func (fl *flow) abort() {
	fl.sess.send(Frame{Type: FrameRst, FlowID: fl.id})
	fl.close()
}

func (fl *flow) close() {
	fl.closeOnce.Do(func() {
		close(fl.closed)
		fl.txMu.Lock()
		fl.txCond.Broadcast()
		fl.txMu.Unlock()
		if fl.dest != nil {
			_ = fl.dest.Close()
		}
		fl.sess.dropFlow(fl.id)
	})
}
