package stripe

import (
	"context"
	"log"
	"net"
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

	// Window bounds the bytes a sender may have outstanding (sent but not
	// yet ACKed-as-delivered) per flow+direction. It must cover the
	// bandwidth-delay product across all pipes or it throttles throughput;
	// it also bounds the receiver's reorder buffer. 512 KiB covers
	// ~7.5 Mbps aggregate at the high (~0.3s) RTT of a WebRTC relay.
	Window = 512 * 1024

	// AckThreshold is how far Delivered must advance before we emit a fresh
	// cumulative Ack. Quarter-window keeps the window full without ACK spam.
	AckThreshold = Window / 4

	// dialTimeout caps how long we wait to connect to the real destination.
	dialTimeout = 15 * time.Second

	// flowIdle reaps a flow that has made no progress for this long — the
	// backstop for a room that died mid-transfer and left a flow with a
	// permanent gap in its reorder buffer (we have no retransmit).
	flowIdle = 90 * time.Second
)

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
	hello, err := ReadFrame(conn)
	if err != nil || hello.Type != FrameHello || len(hello.Payload) < 17 {
		s.logf("stripe: bad hello: err=%v type=%d", err, hello.Type)
		_ = conn.Close()
		return
	}
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
		}
	}()

	for {
		f, err := ReadFrame(conn)
		if err != nil {
			return
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
	conn    net.Conn
	writeMu sync.Mutex
	dead    atomic.Bool
}

func (p *pipe) write(f Frame) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	_, err := p.conn.Write(f.Encode(nil))
	if err != nil {
		p.dead.Store(true)
	}
	return err
}

// session groups the pipes of one phone and owns its flow table. Outgoing
// frames are striped across the session's live pipes round-robin (v1); a
// throughput-weighted scheduler is a later tuning step.
type session struct {
	id   [16]byte
	logf func(string, ...any)

	pmu    sync.RWMutex
	pipes  []*pipe
	cursor atomic.Uint32

	fmu   sync.Mutex
	flows map[uint32]*flow
	down  bool
}

func newSession(id [16]byte, logf func(string, ...any)) *session {
	return &session{id: id, logf: logf, flows: make(map[uint32]*flow)}
}

func (s *session) addPipe(conn net.Conn) *pipe {
	p := &pipe{conn: conn}
	s.pmu.Lock()
	s.pipes = append(s.pipes, p)
	s.pmu.Unlock()
	return p
}

func (s *session) removePipe(p *pipe) (empty bool) {
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
// already marked dead, retrying the next on a write error. Returns false if
// no pipe could carry it (session has no usable pipe right now).
func (s *session) send(f Frame) bool {
	s.pmu.RLock()
	pipes := s.pipes
	n := len(pipes)
	s.pmu.RUnlock()
	if n == 0 {
		return false
	}
	start := int(s.cursor.Add(1)-1) % n
	for i := 0; i < n; i++ {
		p := pipes[(start+i)%n]
		if p.dead.Load() {
			continue
		}
		if err := p.write(f); err == nil {
			return true
		}
	}
	return false
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
