package stripe

import (
	"context"
	"fmt"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Tuning. These are the v1 values; the live tuning pass on real Telemost
// latency may move them. They are intentionally in one place.
const (
	// ChunkSize is how many bytes of a flow we put in one Data frame. A
	// chunk is the striping unit: consecutive chunks go to different pipes.
	ChunkSize = 16 * 1024

	// NO window / per-byte Ack. The room pipes are already reliable +
	// flow-controlled (WebRTC SCTP), so we don't reimplement TCP on top — that
	// added a heavy upstream Ack stream that collapsed the thin Telemost
	// uplink. Reliability + flow control ride the pipes (write backpressure);
	// we only reorder across pipes and keep a light position report for
	// dead-room recovery.

	// MaxReorder bounds the receiver's reassembly buffer (cross-pipe latency
	// skew). Big enough to absorb a slow-but-alive room; if exceeded the flow
	// resets. Also the retain cap on the sender.
	MaxReorder = 1024 * 1024

	// PromoteThreshold: a flow's first PromoteThreshold bytes ride ONE pinned
	// pipe (like round-robin — reliable, no cross-pipe reorder fragility), so
	// small/interactive flows (Telegram's many tiny bidirectional conns) just
	// work. Only once a flow proves bulk does it switch to striping across all
	// pipes for aggregate speed. This is what lets TG and big downloads
	// coexist.
	PromoteThreshold uint64 = 512 * 1024

	// posIntervalMs is how often a receiver reports its cumulative delivered
	// offset (one tiny frame, NOT per-chunk). Only used so a sender can resend
	// a dead pipe's still-unconfirmed chunks — not for flow control.
	posIntervalMs int64 = 700

	// dialTimeout caps how long we wait to connect to the real destination.
	dialTimeout = 15 * time.Second

	// flowIdle reaps a flow that has made no progress for this long.
	flowIdle = 90 * time.Second
)

// pipeRateBytesPerSec paces Data per pipe DOWNSTREAM (server→client). Measured
// 2026-06-04: Telemost's downstream forward cap is a hard ~1.25 Mbps/room for a
// single striped stream - pushing the pace to 3 or 6 Mbps/pipe made the server
// emit faster but the phone's goodput COLLAPSED (bufferbloat→loss→RTO: 0.8 Mbps
// actual vs 6.3 at 1.25). So 1.25 is right for download; aggregate scales only
// with pipe count. (UPSTREAM/upload is NOT paced here and rides ~2.5 Mbps/pipe,
// ~11 Mbps over 5 pipes - that's the TG-video-upload path and it's not capped by
// this value.) Tunable live via WLB_STRIPE_BPS for future substrate changes.
var pipeRateBytesPerSec = 156000.0 // ~1.25 Mbps/pipe (downstream Telemost cap)

func init() {
	if v := os.Getenv("WLB_STRIPE_BPS"); v != "" {
		if n, err := strconv.ParseFloat(v, 64); err == nil && n > 0 {
			pipeRateBytesPerSec = n
		}
	}
}

// Server is the exit-side striping multiplexer. It listens on a loopback
// port that the Telemost creator dials into (one TCP connection per room
// pipe), groups pipes into sessions by the HELLO sessionID, and multiplexes
// application flows across each session's pipes.
type Server struct {
	logf func(string, ...any)

	mu       sync.Mutex
	sessions map[[16]byte]*session
}

// NewServer returns a Server. logf may be nil (defaults to the std logger).
func NewServer(logf func(string, ...any)) *Server {
	if logf == nil {
		logf = log.Printf
	}
	return &Server{logf: logf, sessions: make(map[[16]byte]*session)}
}

// Serve accepts pipe connections on l until the context is cancelled or l
// fails. It returns the error that stopped the accept loop.
func (s *Server) Serve(ctx context.Context, l net.Listener) error {
	go func() {
		<-ctx.Done()
		_ = l.Close()
	}()
	for {
		c, err := l.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return err
		}
		if tc, ok := c.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
		}
		go s.handlePipe(c)
	}
}

// handlePipe reads the HELLO, attaches the connection to its session as a
// pipe, then pumps frames off it for the session's lifetime.
func (s *Server) handlePipe(conn net.Conn) {
	_ = conn.SetReadDeadline(time.Now().Add(8 * time.Second))
	hello, err := ReadFrame(conn)
	if err != nil || hello.Type != FrameHello || len(hello.Payload) < 17 {
		s.logf("stripe: bad hello: err=%v type=%d", err, hello.Type)
		_ = conn.Close()
		return
	}
	_ = conn.SetReadDeadline(time.Time{})
	var sid [16]byte
	copy(sid[:], hello.Payload[:16])
	pipeIdx := hello.Payload[16]

	sess := s.attach(sid)
	p := sess.addPipe(conn)
	s.logf("stripe: session %x pipe %d up (pipes=%d)", sid[:4], pipeIdx, sess.pipeCount())

	defer func() {
		empty := sess.removePipe(p)
		if empty {
			s.detach(sid)
			s.logf("stripe: session %x closed (last pipe gone)", sid[:4])
		} else {
			// A room died but others survive: have every flow resend the
			// chunks that went on this pipe (they're lost — no substrate
			// retransmit) over a live pipe. Event-driven, so no spurious
			// resend storm from merely-slow Acks.
			sess.onPipeDead(p.id)
		}
	}()

	// Confirm end-to-end reachability: echo the HELLO back. A pipe's SOCKS
	// CONNECT is acked locally by librelay BEFORE the carrier actually delivers
	// it to us, so without this the client could add a black-hole pipe to its
	// striping set and lose every chunk striped onto it (nothing to reassemble
	// → the flow stalls and dies). The client only activates a pipe once it
	// sees this ack, and drops pipes that never confirm.
	if err := p.write(Frame{Type: FrameHello, FlowID: 0, Seq: 0, Payload: hello.Payload}); err != nil {
		s.logf("stripe: session %x pipe %d hello-ack failed: %v", sid[:4], pipeIdx, err)
		return
	}

	for {
		f, err := ReadFrame(conn)
		if err != nil {
			return
		}
		p.rd.Add(int64(HeaderSize + len(f.Payload)))
		if f.Type == FrameHello {
			// Health ping from the client: bounce it back on THIS pipe so the
			// client can tell a live pipe from a zombie room (one that accepts
			// the client's writes locally but whose SFU silently drops them).
			_ = p.write(f)
			continue
		}
		sess.route(f)
	}
}

func (s *Server) attach(sid [16]byte) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	if sess := s.sessions[sid]; sess != nil {
		return sess
	}
	sess := newSession(sid, s.logf)
	s.sessions[sid] = sess
	return sess
}

func (s *Server) detach(sid [16]byte) {
	s.mu.Lock()
	sess := s.sessions[sid]
	delete(s.sessions, sid)
	s.mu.Unlock()
	if sess != nil {
		sess.shutdown()
	}
}

// pipe is one physical room connection plus a write mutex; a frame must be
// written to the underlying conn atomically since many flow goroutines share
// the pipe.
type pipe struct {
	id            int
	conn          net.Conn
	dead          atomic.Bool
	wrote, rd     atomic.Int64
	data, control chan Frame
	done          chan struct{}
	closeOnce     sync.Once
}

// session groups the pipes of one phone and owns its flow table. Outgoing
// frames are striped across the session's live pipes round-robin (v1); a
// throughput-weighted scheduler is a later tuning step.
type session struct {
	id   [16]byte
	logf func(string, ...any)

	pmu     sync.RWMutex
	pipes   []*pipe
	cursor  atomic.Uint32
	pipeSeq atomic.Int32 // stable per-pipe ids for retransmit targeting

	fmu   sync.Mutex
	flows map[uint32]*flow
	down  bool

	done chan struct{} // closed by shutdown to stop statsLoop
}

func newSession(id [16]byte, logf func(string, ...any)) *session {
	s := &session{id: id, logf: logf, flows: make(map[uint32]*flow), done: make(chan struct{})}
	go s.statsLoop()
	return s
}

// statsLoop logs per-pipe throughput every 5s while the session is alive — the
// diagnostics that show whether a single flow's bytes spread across the pipes
// (striping works) or pile onto one (it doesn't). Drops to silence when idle.
func (s *session) statsLoop() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	type snap struct{ wrote, rd int64 }
	last := map[*pipe]snap{}
	for {
		select {
		case <-s.done:
			return
		case <-ticker.C:
			s.pmu.RLock()
			pipes := append([]*pipe(nil), s.pipes...)
			s.pmu.RUnlock()
			var totW, totR int64
			parts := make([]string, 0, len(pipes))
			for i, p := range pipes {
				w, r := p.wrote.Load(), p.rd.Load()
				dw := w - last[p].wrote
				dr := r - last[p].rd
				last[p] = snap{w, r}
				totW += dw
				totR += dr
				parts = append(parts, fmt.Sprintf("p%d=%dKB/%dKB", i, dw/1024, dr/1024))
			}
			if totW+totR > 0 {
				s.logf("stripe: session %x 5s out=%.2fMbps in=%.2fMbps per-pipe[out/in] %s",
					s.id[:4],
					float64(totW*8)/5e6, float64(totR*8)/5e6,
					strings.Join(parts, " "))
			}
		}
	}
}

func (s *session) addPipe(conn net.Conn) *pipe {
	p := &pipe{id: int(s.pipeSeq.Add(1)), conn: conn, data: make(chan Frame, 64), control: make(chan Frame, 64), done: make(chan struct{})}
	go p.writeLoop()
	s.pmu.Lock()
	s.pipes = append(s.pipes, p)
	s.pmu.Unlock()
	return p
}

func (s *session) removePipe(p *pipe) (empty bool) {
	p.close()
	s.pmu.Lock()
	defer s.pmu.Unlock()
	for i, q := range s.pipes {
		if q == p {
			s.pipes = append(s.pipes[:i], s.pipes[i+1:]...)
			break
		}
	}
	return len(s.pipes) == 0
}

func (s *session) pipeCount() int {
	s.pmu.RLock()
	defer s.pmu.RUnlock()
	return len(s.pipes)
}

// send stripes one frame across the live pipes: round-robin, skipping pipes
// already marked dead, retrying the next on a write error. Returns the id of
// the pipe that carried it, or -1 if none could (no usable pipe right now).
func (s *session) send(f Frame) int {
	s.pmu.RLock()
	pipes := append([]*pipe(nil), s.pipes...)
	n := len(pipes)
	s.pmu.RUnlock()
	if n == 0 {
		return -1
	}
	start := int(s.cursor.Add(1)-1) % n
	for i := 0; i < n; i++ {
		p := pipes[(start+i)%n]
		if p.dead.Load() {
			continue
		}
		if err := p.write(f); err == nil {
			return p.id
		}
	}
	return -1
}

// sendPinned writes a frame to the flow's pinned home pipe so a small flow
// rides ONE reliable room (no cross-pipe reorder fragility). Reassigns the
// home pipe if it died. *homeID is the flow's stable home pipe id (-1 =
// unassigned); only the flow's tx goroutine calls this, so no lock on it.
func (s *session) sendPinned(homeID *int, f Frame) int {
	s.pmu.RLock()
	pipes := append([]*pipe(nil), s.pipes...)
	s.pmu.RUnlock()
	if len(pipes) == 0 {
		return -1
	}
	var home *pipe
	for _, p := range pipes {
		if p.id == *homeID && !p.dead.Load() {
			home = p
			break
		}
	}
	if home == nil {
		n := len(pipes)
		start := int(s.cursor.Add(1)-1) % n
		for i := 0; i < n; i++ {
			p := pipes[(start+i)%n]
			if !p.dead.Load() {
				home = p
				break
			}
		}
		if home == nil {
			return -1
		}
		*homeID = home.id
	}
	if err := home.write(f); err != nil {
		return s.send(f)
	}
	return home.id
}

// onPipeDead tells every flow to resend the chunks it had on the dead pipe.
func (s *session) onPipeDead(deadID int) {
	s.fmu.Lock()
	flows := make([]*flow, 0, len(s.flows))
	for _, fl := range s.flows {
		flows = append(flows, fl)
	}
	s.fmu.Unlock()
	for _, fl := range flows {
		fl.onPipeDead(deadID)
	}
}

// broadcast writes a frame to every live pipe. Used for tiny control frames
// (Acks) so the fastest surviving pipe delivers them — one slow/dead pipe
// can't delay a window update. Duplicates are harmless (cumulative Acks).
func (s *session) broadcast(f Frame) {
	s.pmu.RLock()
	pipes := append([]*pipe(nil), s.pipes...)
	s.pmu.RUnlock()
	for _, p := range pipes {
		if !p.dead.Load() {
			_ = p.write(f)
		}
	}
}

// route delivers an inbound frame to its flow, creating the flow lazily on
// the first frame that references a new flowID (Open or any Data — they can
// arrive in either order across pipes).
func (s *session) route(f Frame) {
	switch f.Type {
	case FrameOpen, FrameData, FrameFin, FrameRst, FrameAck:
	default:
		return // unknown / Hello on an established pipe: ignore
	}
	s.fmu.Lock()
	if s.down {
		s.fmu.Unlock()
		return
	}
	fl := s.flows[f.FlowID]
	if fl == nil {
		if f.Type == FrameRst || f.Type == FrameAck {
			// Nothing to do for a flow we don't have.
			s.fmu.Unlock()
			return
		}
		if len(s.flows) >= 64 {
			s.fmu.Unlock()
			s.send(Frame{Type: FrameRst, FlowID: f.FlowID})
			return
		}
		fl = newFlow(f.FlowID, s)
		s.flows[f.FlowID] = fl
		fl.start()
	}
	s.fmu.Unlock()
	fl.deliver(f)
}

func (s *session) dropFlow(id uint32) {
	s.fmu.Lock()
	delete(s.flows, id)
	s.fmu.Unlock()
}

func (s *session) shutdown() {
	select {
	case <-s.done:
	default:
		close(s.done)
	}
	s.fmu.Lock()
	s.down = true
	flows := make([]*flow, 0, len(s.flows))
	for _, fl := range s.flows {
		flows = append(flows, fl)
	}
	s.flows = map[uint32]*flow{}
	s.fmu.Unlock()
	for _, fl := range flows {
		fl.close()
	}
}
