package stripe

import (
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// txChunk is one sent-but-not-yet-acked s2c chunk, retained so it can be
// resent if the pipe it went on dies (the substrate has no retransmit of its
// own and Telemost rooms drop often).
type txChunk struct {
	off  uint64
	data []byte
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
	unacked    []txChunk
	finReached bool
	finSeq     uint64

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
		inbound: make(chan Frame, Window/ChunkSize+8),
		closed:  make(chan struct{}),
		rx:      NewReorder(Window),
	}
	fl.txCond = sync.NewCond(&fl.txMu)
	return fl
}

func (fl *flow) start() {
	fl.touch()
	go fl.rxLoop()
	go fl.reaper()
}

func (fl *flow) touch()              { fl.lastProg.Store(time.Now().UnixNano()) }
func (fl *flow) lastProgress() int64 { return fl.lastProg.Load() }

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
			go fl.txRetransmit()
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
				fl.maybeAck()
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

// maybeAck emits a cumulative Ack when delivery has advanced enough. The Ack
// is broadcast on every live pipe (it is tiny) so the fastest surviving pipe
// carries it — a single congested/dead pipe can't delay the window update.
func (fl *flow) maybeAck() {
	d := fl.rx.Delivered()
	if d-fl.lastAckSent >= AckThreshold {
		fl.sess.broadcast(Frame{Type: FrameAck, FlowID: fl.id, Seq: d})
		fl.lastAckSent = d
	}
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

// txReader reads dest, buffers each chunk for possible retransmit, stripes it
// across the pipes, and window-gates on the client's Acks.
func (fl *flow) txReader() {
	buf := make([]byte, ChunkSize)
	for {
		fl.txMu.Lock()
		for fl.txNext-fl.txBase >= Window {
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
			fl.unacked = append(fl.unacked, txChunk{off: off, data: data})
			fl.txMu.Unlock()
			// Best-effort first send; if no pipe is usable right now the
			// retransmit loop will carry it once a pipe returns.
			fl.sess.send(Frame{Type: FrameData, FlowID: fl.id, Seq: off, Payload: data})
			fl.touch()
		}
		if err != nil {
			fl.txMu.Lock()
			fl.finReached = true
			fl.finSeq = fl.txNext
			done := fl.txBase >= fl.txNext
			fl.txMu.Unlock()
			fl.sess.send(Frame{Type: FrameFin, FlowID: fl.id, Seq: fl.finSeq})
			if done {
				fl.markTxDone()
			}
			return
		}
	}
}

// txRetransmit resends the unacked window whenever the client's Acks stop
// advancing for one RTO — the recovery path for a room that died holding
// in-flight chunks. Resent chunks the client already has are dropped by its
// reorder buffer (offset dedup), so over-resending is safe, just wasteful.
func (fl *flow) txRetransmit() {
	t := time.NewTicker(retransmitRTO)
	defer t.Stop()
	var lastBase uint64
	for {
		select {
		case <-fl.closed:
			return
		case <-t.C:
			fl.txMu.Lock()
			base, next := fl.txBase, fl.txNext
			if base >= next {
				fin := fl.finReached
				lastBase = base
				fl.txMu.Unlock()
				if fin {
					fl.markTxDone()
					return
				}
				continue
			}
			progressed := base != lastBase
			lastBase = base
			var resend []txChunk
			var fr bool
			var fs uint64
			if !progressed {
				resend = append([]txChunk(nil), fl.unacked...)
				fr, fs = fl.finReached, fl.finSeq
			}
			fl.txMu.Unlock()

			if len(resend) == 0 {
				continue
			}
			for _, ch := range resend {
				fl.sess.send(Frame{Type: FrameData, FlowID: fl.id, Seq: ch.off, Payload: ch.data})
			}
			if fr {
				fl.sess.send(Frame{Type: FrameFin, FlowID: fl.id, Seq: fs})
			}
		}
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
