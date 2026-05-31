package stripe

import (
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// flow is one application connection multiplexed over the session's pipes.
//
// Two directions run as two goroutines:
//   - rxLoop  (c2s): frames arrive from the client, get reassembled in
//     offset order, and the contiguous bytes are written to the real dest.
//     We Ack our cumulative delivered offset back so the client's send
//     window can advance.
//   - txLoop  (s2c): we read the dest, chunk it, stripe chunks across pipes,
//     and gate ourselves on the client's Acks so we never run more than
//     Window bytes ahead (which also bounds the client's reorder buffer).
//
// The window is what stops a slow flow from blocking a shared pipe: the
// client is paced by how fast we drain to dest, so the per-flow inbound
// channel never backs up past the window and the pipe reader never stalls.
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
	txMu    sync.Mutex
	txCond  *sync.Cond
	txSeq   uint64 // next offset to assign / total sent
	txAcked uint64 // cumulative delivered reported by client Acks

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

func (fl *flow) touch()             { fl.lastProg.Store(time.Now().UnixNano()) }
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
// Ack and Rst act inline (Ack just bumps the tx window, Rst tears down).
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
		fl.txMu.Lock()
		if f.Seq > fl.txAcked {
			fl.txAcked = f.Seq
			fl.txCond.Broadcast()
		}
		fl.txMu.Unlock()
		fl.touch()
	case FrameRst:
		fl.close()
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
			go fl.txLoop()
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
		// Dial failed: drain and discard until close so deliver() never blocks.
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

// maybeAck emits a cumulative Ack when delivery has advanced enough. Only
// rxLoop touches lastAckSent, so no lock is needed.
func (fl *flow) maybeAck() {
	d := fl.rx.Delivered()
	if d-fl.lastAckSent >= AckThreshold {
		fl.sess.send(Frame{Type: FrameAck, FlowID: fl.id, Seq: d})
		fl.lastAckSent = d
	}
}

// checkRxFin returns true (and finishes the c2s half) once every client byte
// has been delivered to dest. It half-closes our write side toward dest so
// the destination sees EOF, and sends a final Ack.
func (fl *flow) checkRxFin() bool {
	if !fl.rxFinSet || fl.rx.Delivered() != fl.rxFinal {
		return false
	}
	if cw, ok := fl.dest.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
	fl.sess.send(Frame{Type: FrameAck, FlowID: fl.id, Seq: fl.rx.Delivered()})
	fl.markRxDone()
	return true
}

// txLoop reads dest, stripes chunks across the pipes, and never runs more
// than Window bytes ahead of the client's Acks.
func (fl *flow) txLoop() {
	buf := make([]byte, ChunkSize)
	for {
		fl.txMu.Lock()
		for fl.txSeq-fl.txAcked >= Window {
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
			payload := append([]byte(nil), buf[:n]...)
			fl.txMu.Lock()
			off := fl.txSeq
			fl.txSeq += uint64(n)
			fl.txMu.Unlock()
			if !fl.sess.send(Frame{Type: FrameData, FlowID: fl.id, Seq: off, Payload: payload}) {
				fl.abort()
				return
			}
			fl.touch()
		}
		if err != nil {
			if fl.isClosed() {
				return
			}
			fl.txMu.Lock()
			final := fl.txSeq
			fl.txMu.Unlock()
			fl.sess.send(Frame{Type: FrameFin, FlowID: fl.id, Seq: final})
			fl.markTxDone()
			return
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

// maybeFinish closes the flow cleanly once both directions have finished.
func (fl *flow) maybeFinish() {
	if fl.rxDone.Load() && fl.txDone.Load() {
		fl.close()
	}
}

// reaper closes a flow that has made no progress for flowIdle — the backstop
// for a room that died mid-transfer and left a permanent gap (no retransmit).
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

// abort tells the client to drop the flow, then closes it. Used for errors
// (dial fail, reorder overflow, dest write fail, no usable pipe, idle).
func (fl *flow) abort() {
	fl.sess.send(Frame{Type: FrameRst, FlowID: fl.id})
	fl.close()
}

// close is idempotent: unblock txLoop, close dest, drop from the session.
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
