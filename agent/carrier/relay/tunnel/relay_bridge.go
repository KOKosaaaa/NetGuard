package tunnel

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"whitelist-bypass/relay/common"
)

type udpClient struct {
	udpConn    *net.UDPConn
	clientAddr *net.UDPAddr
	socksHdr   []byte
}

type RelayBridge struct {
	lifecycleMu      sync.Mutex // serializes carrier replacement, close callbacks and TCP admission
	tunnelGeneration uint64
	tunnelMu         sync.RWMutex
	tunnel           DataTunnel
	conns            sync.Map
	udpClients       sync.Map
	nextID           atomic.Uint32
	logFn            func(string, ...any)
	mode             string
	readBuf          int
	ready            chan struct{}
	once             sync.Once
	socksUser        string
	socksPass        string

	persistentListener atomic.Bool
	listenerMu         sync.Mutex
	listener           net.Listener
	closed             atomic.Bool

	// dropCount throttles the "unknown conn" drop logs. These fire whenever the
	// peer keeps pumping MsgData/MsgClose for a conn the local side already tore
	// down (normal end-of-conn lifecycle race) — at high throughput that's
	// thousands of identical lines (see project_wbstream_carrier: 90% of joiner
	// log volume). Unbounded it pollutes throughput measurements and burns IO.
	// We log the first few + every 1000th so the signal survives without spam.
	dropCount atomic.Uint64

	onPeerConfigMu sync.Mutex
	onPeerConfig   func(fps, batch, trackCount int)

	onConfigAckMu sync.Mutex
	onConfigAck   func()

	// muxEpoch != 0 marks this as a per-peer bridge (multi-client): all
	// outbound frames are addressed to this peer via tunnel.SendDataTo so only
	// that peer's joiner receives them.
	muxEpoch uint32

	// Register creator connections before starting the dial goroutine, so a
	// following MsgClose cannot miss a connection which has not dialed yet.
	creatorMu  sync.Mutex
	creatorTCP map[uint32]*creatorTCPConnection
	dialTCP    func(context.Context, string, string) (net.Conn, error)
}

type creatorTCPConnection struct {
	ctx    context.Context
	cancel context.CancelFunc
}

// SetMuxEpoch tags this bridge as serving one peer (multi-client). Outbound
// frames then go to tunnel.SendDataTo(epoch, ...) instead of broadcast SendData.
func (rb *RelayBridge) SetMuxEpoch(epoch uint32) { rb.muxEpoch = epoch }

func (rb *RelayBridge) SetOnPeerConfig(fn func(fps, batch, trackCount int)) {
	rb.onPeerConfigMu.Lock()
	rb.onPeerConfig = fn
	rb.onPeerConfigMu.Unlock()
}

func (rb *RelayBridge) SetOnConfigAck(fn func()) {
	rb.onConfigAckMu.Lock()
	rb.onConfigAck = fn
	rb.onConfigAckMu.Unlock()
}

func NewRelayBridgeWithAuth(tunnel DataTunnel, mode string, readBuf int, logFn func(string, ...any), socksUser, socksPass string) *RelayBridge {
	rb := NewRelayBridge(tunnel, mode, readBuf, logFn)
	rb.socksUser = socksUser
	rb.socksPass = socksPass
	return rb
}

func NewRelayBridge(tunnel DataTunnel, mode string, readBuf int, logFn func(string, ...any)) *RelayBridge {
	rb := &RelayBridge{
		tunnel:  tunnel,
		logFn:   logFn,
		mode:    mode,
		readBuf: readBuf,
		ready:   make(chan struct{}),
	}
	tunnel.SetOnData(func(data []byte) { rb.handleTunnelDataGeneration(0, data) })
	tunnel.SetOnClose(func() { rb.handleTunnelCloseGeneration(0) })
	return rb
}

// NewRelayBridgeMux makes a per-peer bridge for multi-client mode. Unlike
// NewRelayBridge it does NOT hijack tunnel.SetOnData/SetOnClose (the shared
// tunnel routes incoming frames to the right peer bridge via OnDataMux, which
// calls Feed). Outbound frames are addressed to this peer's epoch.
func NewRelayBridgeMux(tunnel DataTunnel, mode string, readBuf int, logFn func(string, ...any), epoch uint32) *RelayBridge {
	return &RelayBridge{
		tunnel:   tunnel,
		logFn:    logFn,
		mode:     mode,
		readBuf:  readBuf,
		ready:    make(chan struct{}),
		muxEpoch: epoch,
	}
}

// Feed delivers inbound tunnel bytes to this bridge (multi-client router entry).
func (rb *RelayBridge) Feed(data []byte) { rb.handleTunnelData(data) }

func (rb *RelayBridge) SetPersistentListener(persistent bool) {
	rb.persistentListener.Store(persistent)
}

func (rb *RelayBridge) SwapTunnel(newTunnel DataTunnel) {
	rb.lifecycleMu.Lock()
	defer rb.lifecycleMu.Unlock()
	rb.closeAll()
	rb.tunnelGeneration++
	generation := rb.tunnelGeneration
	rb.tunnelMu.Lock()
	rb.tunnel = newTunnel
	rb.tunnelMu.Unlock()
	newTunnel.SetOnData(func(data []byte) { rb.handleTunnelDataGeneration(generation, data) })
	newTunnel.SetOnClose(func() { rb.handleTunnelCloseGeneration(generation) })
}

func (rb *RelayBridge) handleTunnelCloseGeneration(generation uint64) {
	rb.lifecycleMu.Lock()
	defer rb.lifecycleMu.Unlock()
	if generation != rb.tunnelGeneration {
		return
	}
	rb.handleTunnelClose()
}

func (rb *RelayBridge) currentTunnel() DataTunnel {
	rb.tunnelMu.RLock()
	defer rb.tunnelMu.RUnlock()
	return rb.tunnel
}

func (rb *RelayBridge) handleTunnelClose() {
	if rb.persistentListener.Load() {
		rb.closeAll()
		return
	}
	rb.Close()
}

func (rb *RelayBridge) closeAll() {
	rb.creatorMu.Lock()
	for _, pending := range rb.creatorTCP {
		pending.cancel()
	}
	rb.creatorTCP = nil
	var ids []uint32
	rb.conns.Range(func(key, value any) bool {
		if id, ok := key.(uint32); ok {
			ids = append(ids, id)
		}
		switch v := value.(type) {
		case net.Conn:
			v.Close()
		case *socksConn:
			// Closing the socket cannot wake a CONNECT handler which is
			// waiting on rdy rather than reading the socket. Release it now,
			// before its old timeout can send a Close on a replacement tunnel.
			select {
			case v.rdy <- fmt.Errorf("tunnel connection closed"):
			default:
			}
			v.conn.Close()
		}
		rb.conns.Delete(key)
		return true
	})
	rb.creatorMu.Unlock()
	udpCount := 0
	rb.udpClients.Range(func(key, _ any) bool {
		udpCount++
		rb.udpClients.Delete(key)
		return true
	})
	rb.logFn("relay: closeAll mode=%s tcp=%d udp=%d ids=%v nextID=%d", rb.mode, len(ids), udpCount, ids, rb.nextID.Load())
}

func (rb *RelayBridge) Reset() {
	rb.closeAll()
}

func (rb *RelayBridge) Close() {
	if !rb.closed.CompareAndSwap(false, true) {
		return
	}
	rb.listenerMu.Lock()
	ln := rb.listener
	rb.listener = nil
	rb.listenerMu.Unlock()
	if ln != nil {
		rb.logFn("relay: bridge Close closing socks listener")
		ln.Close()
	}
	rb.closeAll()
}

func (rb *RelayBridge) Stats() (tcpConns, udpConns int, nextID uint32) {
	rb.conns.Range(func(_, _ any) bool { tcpConns++; return true })
	rb.udpClients.Range(func(_, _ any) bool { udpConns++; return true })
	return tcpConns, udpConns, rb.nextID.Load()
}

func (rb *RelayBridge) MarkReady() {
	rb.once.Do(func() { close(rb.ready) })
}

// MarkReadyOnConfigAck is the cold-start hardening gate (joiner side). Instead of
// opening SOCKS immediately, it holds readiness until the peer ACKs our vp8 config
// (MsgConfigAck) — proof the carrier decodes in BOTH directions — or until
// `timeout` elapses as a safety fallback.
//
// Why: handleSOCKS sends a MsgConnect into the carrier the moment a client
// connects. During the vp8 keyframe-warmup window (~2s) the carrier's first
// frames fail to decode, so a MsgConnect landing there is lost. Unlike the config
// ping-pong (which resends every 3s until acked), MsgConnect has no app-level
// resend — only per-conn ARQ NACK recovery — and N clients racing the warmup at
// once turn that into a NACK storm that can blow the 20s SOCKS timeout, producing
// intermittent total stalls. The config ack is the cheapest existing proof that
// the warmup window has passed (our MsgConfig reached the creator AND its ack
// reached us), so we reuse it as the warmup barrier. The timeout fallback still
// opens SOCKS if no ack ever arrives, degrading to the old behaviour instead of
// wedging the proxy forever. Chains onto any existing onConfigAck callback so the
// session's own ack handling is preserved. Idempotent (MarkReady is once-guarded).
func (rb *RelayBridge) MarkReadyOnConfigAck(timeout time.Duration) {
	rb.onConfigAckMu.Lock()
	prev := rb.onConfigAck
	rb.onConfigAck = func() {
		if prev != nil {
			prev()
		}
		rb.MarkReady()
	}
	rb.onConfigAckMu.Unlock()
	go func() {
		t := time.NewTimer(timeout)
		defer t.Stop()
		select {
		case <-rb.ready:
			// Config acked (or readiness forced elsewhere) — gate satisfied.
		case <-t.C:
			rb.logFn("relay[%s]: warmup gate timeout (%s), opening SOCKS without config ack", rb.mode, timeout)
			rb.MarkReady()
		}
	}()
}

func (rb *RelayBridge) send(connID uint32, msgType byte, payload []byte) {
	rb.sendOn(rb.currentTunnel(), connID, msgType, payload)
}

func (rb *RelayBridge) sendOn(tun DataTunnel, connID uint32, msgType byte, payload []byte) {
	frame := EncodeFrame(connID, msgType, payload)
	if rb.muxEpoch != 0 {
		tun.SendDataTo(rb.muxEpoch, frame)
		return
	}
	tun.SendData(frame)
}

func (rb *RelayBridge) handleTunnelData(data []byte) {
	DecodeFrames(data, rb.handleTunnelFrame)
}

func (rb *RelayBridge) handleTunnelDataGeneration(generation uint64, data []byte) {
	DecodeFrames(data, func(connID uint32, msgType byte, payload []byte) {
		rb.lifecycleMu.Lock()
		if generation != rb.tunnelGeneration {
			rb.lifecycleMu.Unlock()
			return
		}
		if connID == ControlConnID && msgType == MsgConfigAck {
			// Readiness belongs to one carrier. Serialize its bounded callback
			// with SwapTunnel; never hold this lock across application writes.
			rb.handleTunnelFrame(connID, msgType, payload)
			rb.lifecycleMu.Unlock()
			return
		}
		rb.lifecycleMu.Unlock()
		rb.handleTunnelFrame(connID, msgType, payload)
	})
}

func (rb *RelayBridge) handleTunnelFrame(connID uint32, msgType byte, payload []byte) {
	if connID == ControlConnID && msgType == MsgConfig {
		fps, batch, trackCount, ok := DecodeVP8Config(payload)
		if !ok {
			return
		}
		if rb.mode == "creator" {
			rb.logFn("relay: peer requested vp8 pacing fps=%d batch=%d trackCount=%d", fps, batch, trackCount)
			rb.currentTunnel().Reconfigure(fps, batch)
			rb.send(ControlConnID, MsgConfigAck, nil)
			rb.onPeerConfigMu.Lock()
			cb := rb.onPeerConfig
			rb.onPeerConfigMu.Unlock()
			if cb != nil {
				cb(fps, batch, trackCount)
			}
		}
		return
	}
	if connID == ControlConnID && msgType == MsgConfigAck {
		if rb.mode == "joiner" {
			rb.onConfigAckMu.Lock()
			cb := rb.onConfigAck
			rb.onConfigAckMu.Unlock()
			if cb != nil {
				cb()
			}
		}
		return
	}
	if connID == ControlConnID && msgType == MsgRateReport {
		// Peer's receive-rate report -> feed our send-side AIMD.
		if bps, ok := DecodeRateReport(payload); ok {
			if rt, ok := rb.currentTunnel().(interface{ OnRateReport(uint32) }); ok {
				rt.OnRateReport(bps)
			}
		}
		return
	}
	switch rb.mode {
	case "joiner":
		rb.handleJoinerMessage(connID, msgType, payload)
	case "creator":
		rb.handleCreatorMessage(connID, msgType, payload)
	}
}

// dropLog emits a throttled "unknown conn" drop message. The first 12 drops
// log in full; after that only every 1000th, with a running total, so a steady
// end-of-conn race doesn't drown the log (or skew measurements) while a genuine
// pathological storm still shows up.
func (rb *RelayBridge) dropLog(format string, args ...any) {
	n := rb.dropCount.Add(1)
	if n <= 12 {
		rb.logFn(format, args...)
	} else if n%1000 == 0 {
		rb.logFn("relay[%s]: %d total unknown-conn drops so far", rb.mode, n)
	}
}

func (rb *RelayBridge) handleJoinerMessage(connID uint32, msgType byte, payload []byte) {
	if msgType == MsgUDPReply {
		uval, ok := rb.udpClients.Load(connID)
		if !ok {
			rb.dropLog("relay[joiner]: drop MsgUDPReply for unknown conn %d", connID)
			return
		}
		uc := uval.(*udpClient)
		reply := make([]byte, len(uc.socksHdr)+len(payload))
		copy(reply, uc.socksHdr)
		copy(reply[len(uc.socksHdr):], payload)
		uc.udpConn.WriteToUDP(reply, uc.clientAddr)
		rb.udpClients.Delete(connID)
		return
	}
	val, ok := rb.conns.Load(connID)
	if !ok {
		rb.dropLog("relay[joiner]: drop msgType=%d for unknown conn %d (payload=%dB)", msgType, connID, len(payload))
		return
	}
	sc := val.(*socksConn)
	switch msgType {
	case MsgConnectOK:
		select {
		case sc.rdy <- nil:
		default:
			rb.logFn("relay[joiner]: MsgConnectOK %d: rdy already signalled (duplicate)", connID)
		}
	case MsgConnectErr:
		select {
		case sc.rdy <- fmt.Errorf("%s", payload):
		default:
			rb.logFn("relay[joiner]: MsgConnectErr %d: rdy already signalled (duplicate)", connID)
		}
	case MsgData:
		if carrierPCARQ {
			_ = sc.conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
		}
		if _, err := sc.conn.Write(payload); err != nil {
			if carrierPCARQ {
				sc.conn.Close()
			}
			rb.logFn("relay[joiner]: write to socks %d failed: %s", connID, common.MaskError(err))
		}
	case MsgClose:
		select {
		case sc.rdy <- fmt.Errorf("remote connection closed"):
		default:
		}
		sc.conn.Close()
		rb.conns.Delete(connID)
	}
}

func (rb *RelayBridge) handleCreatorMessage(connID uint32, msgType byte, payload []byte) {
	switch msgType {
	case MsgConnect:
		rb.startCreatorTCP(connID, string(payload))
	case MsgUDP:
		go rb.handleUDP(connID, payload)
	case MsgData:
		val, ok := rb.conns.Load(connID)
		if !ok {
			rb.dropLog("relay[creator]: drop MsgData for unknown conn %d (payload=%dB)", connID, len(payload))
			return
		}
		if c, ok := val.(net.Conn); ok {
			if carrierPCARQ {
				_ = c.SetWriteDeadline(time.Now().Add(30 * time.Second))
			}
			if _, err := c.Write(payload); err != nil {
				if carrierPCARQ {
					c.Close()
				}
				rb.logFn("relay[creator]: write to target %d failed: %s", connID, common.MaskError(err))
			}
		}
	case MsgClose:
		rb.creatorMu.Lock()
		pending := rb.creatorTCP[connID]
		if pending != nil {
			delete(rb.creatorTCP, connID)
			pending.cancel()
		}
		val, ok := rb.conns.LoadAndDelete(connID)
		rb.creatorMu.Unlock()
		if ok {
			if c, ok := val.(net.Conn); ok {
				c.Close()
			}
		} else if pending == nil {
			rb.dropLog("relay[creator]: drop MsgClose for unknown conn %d", connID)
		}
	}
}

func (rb *RelayBridge) handleUDP(connID uint32, payload []byte) {
	if len(payload) < 2 {
		return
	}
	addrLen := int(payload[0])
	if addrLen == 0 || len(payload) < 1+addrLen {
		return
	}
	if bytes.IndexByte(payload[1:1+addrLen], 0) != -1 {
		return
	}
	addr := string(payload[1 : 1+addrLen])
	data := payload[1+addrLen:]
	udpAddr, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return
	}
	conn, err := net.DialUDP("udp", nil, udpAddr)
	if err != nil {
		return
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	conn.Write(data)
	buf := make([]byte, common.UDPBufSize)
	n, err := conn.Read(buf)
	if err != nil {
		return
	}
	rb.send(connID, MsgUDPReply, buf[:n])
}

func (rb *RelayBridge) startCreatorTCP(connID uint32, addr string) {
	rb.creatorMu.Lock()
	if rb.closed.Load() || rb.creatorTCP[connID] != nil {
		rb.creatorMu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	state := &creatorTCPConnection{ctx: ctx, cancel: cancel}
	if rb.creatorTCP == nil {
		rb.creatorTCP = make(map[uint32]*creatorTCPConnection)
	}
	rb.creatorTCP[connID] = state
	rb.creatorMu.Unlock()
	go rb.connectTCP(connID, addr, state)
}

// Never hold creatorMu while sending: carrier backpressure can block SendData,
// and Reset/MsgClose must remain able to cancel a dial and close its socket.
// A send already admitted before a concurrent Reset can be in flight; an old
// dial completion or read loop must not begin a new send for a reused ID.
func (rb *RelayBridge) sendCreatorTCP(connID uint32, state *creatorTCPConnection, kind byte, payload []byte) {
	rb.creatorMu.Lock()
	current := rb.creatorTCP[connID] == state && state.ctx.Err() == nil && !rb.closed.Load()
	tunnel := rb.currentTunnel()
	rb.creatorMu.Unlock()
	if !current || state.ctx.Err() != nil {
		return
	}
	frame := EncodeFrame(connID, kind, payload)
	if rb.muxEpoch != 0 {
		tunnel.SendDataTo(rb.muxEpoch, frame)
	} else {
		tunnel.SendData(frame)
	}
}

func (rb *RelayBridge) connectTCP(connID uint32, addr string, state *creatorTCPConnection) {
	defer func() {
		state.cancel()
		rb.creatorMu.Lock()
		if rb.creatorTCP[connID] == state {
			delete(rb.creatorTCP, connID)
			rb.conns.Delete(connID)
		}
		rb.creatorMu.Unlock()
	}()
	rb.logFn("relay: CONNECT %d -> %s", connID, common.MaskAddr(addr))
	dial := rb.dialTCP
	if dial == nil {
		dial = (&net.Dialer{Timeout: 10 * time.Second}).DialContext
	}
	conn, err := dial(state.ctx, "tcp", addr)
	if err != nil {
		if conn != nil {
			conn.Close()
		}
		if state.ctx.Err() != nil {
			return
		}
		rb.logFn("relay: CONNECT %d failed: %s", connID, common.MaskError(err))
		rb.sendCreatorTCP(connID, state, MsgConnectErr, []byte(common.MaskError(err)))
		return
	}
	defer conn.Close()
	rb.creatorMu.Lock()
	if rb.creatorTCP[connID] != state || state.ctx.Err() != nil || rb.closed.Load() {
		rb.creatorMu.Unlock()
		return
	}
	rb.conns.Store(connID, conn)
	rb.creatorMu.Unlock()
	rb.sendCreatorTCP(connID, state, MsgConnectOK, nil)
	rb.logFn("relay: CONNECTED %d -> %s", connID, common.MaskAddr(addr))

	buf := make([]byte, rb.readBuf)
	var totalRead int64
	var reads int
	for {
		n, err := conn.Read(buf)
		if n > 0 {
			rb.sendCreatorTCP(connID, state, MsgData, buf[:n])
			totalRead += int64(n)
			reads++
			if reads == 1 {
				rb.logFn("relay: conn %d first read %dB", connID, n)
			}
		}
		if err != nil {
			if err != io.EOF {
				rb.logFn("relay: conn %d read error: %s (read %d times, %dB)", connID, common.MaskError(err), reads, totalRead)
			} else if reads == 0 {
				rb.logFn("relay: conn %d EOF with no data read", connID)
			}
			break
		}
	}
	rb.sendCreatorTCP(connID, state, MsgClose, nil)
}

type socksConn struct {
	id   uint32
	conn net.Conn
	rb   *RelayBridge
	rdy  chan error
}

func (rb *RelayBridge) ListenSOCKS(addr string) error {
	if rb.closed.Load() {
		return fmt.Errorf("relay: bridge already closed")
	}
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	rb.listenerMu.Lock()
	if rb.closed.Load() {
		rb.listenerMu.Unlock()
		ln.Close()
		return fmt.Errorf("relay: bridge already closed")
	}
	rb.listener = ln
	rb.listenerMu.Unlock()
	rb.logFn("relay: SOCKS5 on %s", addr)
	for {
		conn, err := ln.Accept()
		if err != nil {
			if rb.closed.Load() {
				rb.logFn("relay: SOCKS listener stopped (bridge closed)")
				return nil
			}
			rb.logFn("relay: accept error: %v", err)
			continue
		}
		go rb.handleSOCKS(conn)
	}
}

func (rb *RelayBridge) handleSOCKS(conn net.Conn) {
	<-rb.ready
	if rb.closed.Load() {
		conn.Close()
		return
	}
	buf := make([]byte, common.HandshakeBuf)
	n, err := conn.Read(buf)
	if err != nil || n < 2 || buf[0] != common.Ver {
		conn.Close()
		return
	}
	if !common.NegotiateAuth(conn, buf, n, rb.socksUser, rb.socksPass) {
		conn.Close()
		return
	}
	n, err = conn.Read(buf)
	if err != nil || n < 7 || buf[0] != common.Ver {
		conn.Close()
		return
	}
	cmd := buf[1]
	if cmd == common.CmdUDP {
		rb.handleUDPAssociate(conn)
		return
	}
	if cmd != common.CmdTCP {
		conn.Write(common.CmdErr)
		conn.Close()
		return
	}
	host, _, err := common.ParseAddress(buf, n)
	if err != nil {
		conn.Write(common.AddrErr)
		conn.Close()
		return
	}

	hostOnly, _, _ := net.SplitHostPort(host)
	if ip := net.ParseIP(hostOnly); ip != nil && ip.IsUnspecified() {
		conn.Write(common.ConnFail)
		conn.Close()
		return
	}
	// Dial local/private addresses directly instead of tunneling to the creator,
	// which cannot reach the joiner's local network. Disabled for now until
	// there is a real use case for local network access through the proxy. So idk if
	// this is a bug or a feature
	// if ip := net.ParseIP(hostOnly); ip != nil && !ip.IsGlobalUnicast() {
	// 	rb.logFn("relay: SOCKS local dial %s", common.MaskAddr(host))
	// 	target, dialErr := net.DialTimeout("tcp", host, 10*time.Second)
	// 	if dialErr != nil {
	// 		rb.logFn("relay: SOCKS local dial failed: %s", common.MaskError(dialErr))
	// 		conn.Write(common.ConnFail)
	// 		conn.Close()
	// 		return
	// 	}
	// 	conn.Write(common.OK)
	// 	go func() {
	// 		defer target.Close()
	// 		defer conn.Close()
	// 		done := make(chan struct{})
	// 		go func() {
	// 			io.Copy(target, conn)
	// 			close(done)
	// 		}()
	// 		io.Copy(conn, target)
	// 		<-done
	// 	}()
	// 	return
	// }

	id := rb.nextID.Add(1)
	// Keep every write on the carrier which admitted this TCP connection.
	// In particular, a late EOF/timeout from an old socket cannot send control
	// messages into the replacement carrier while reconnect is in progress.
	rb.lifecycleMu.Lock()
	admitted := rb.currentTunnel()
	var carrierDone <-chan struct{}
	if lifetime, ok := admitted.(interface{ Done() <-chan struct{} }); ok {
		carrierDone = lifetime.Done()
	}
	send := func(kind byte, payload []byte) { rb.sendOn(admitted, id, kind, payload) }
	select {
	case <-carrierDone:
		rb.lifecycleMu.Unlock()
		conn.Write(common.ConnFail)
		conn.Close()
		return
	default:
	}
	sc := &socksConn{id: id, conn: conn, rb: rb, rdy: make(chan error, 1)}
	rb.conns.Store(id, sc)
	rb.lifecycleMu.Unlock()
	if rb.closed.Load() {
		conn.Close()
		rb.conns.Delete(id)
		return
	}
	rb.logFn("relay: SOCKS CONNECT %d -> %s", id, common.MaskAddr(host))
	send(MsgConnect, []byte(host))

	rdyStart := time.Now()
	select {
	case <-carrierDone:
		conn.Close()
		rb.conns.Delete(id)
		return
	case rdyErr := <-sc.rdy:
		if rdyErr != nil {
			rb.logFn("relay: SOCKS CONNECT %d failed after %s: %s", id, time.Since(rdyStart), common.MaskError(rdyErr))
			conn.Write(common.ConnFail)
			conn.Close()
			rb.conns.Delete(id)
			return
		}
	case <-time.After(20 * time.Second):
		rb.logFn("relay: SOCKS CONNECT %d TIMEOUT after %s waiting for MsgConnectOK", id, time.Since(rdyStart))
		conn.Write(common.ConnFail)
		conn.Close()
		rb.conns.Delete(id)
		send(MsgClose, nil)
		return
	}
	conn.Write(common.OK)
	rb.logFn("relay: SOCKS CONNECTED %d -> %s rdy_wait=%s", id, common.MaskAddr(host), time.Since(rdyStart))

	go func() {
		readBuf := make([]byte, rb.readBuf)
		var totalSent int64
		var sends int
		for {
			rn, rerr := conn.Read(readBuf)
			if rn > 0 {
				send(MsgData, readBuf[:rn])
				totalSent += int64(rn)
				sends++
				if sends == 1 {
					rb.logFn("relay: SOCKS %d first send %dB to tunnel", id, rn)
				}
			}
			if rerr != nil {
				send(MsgClose, nil)
				rb.conns.Delete(id)
				if rerr != io.EOF {
					rb.logFn("relay: SOCKS %d read error: %s (sent %d times, %dB)", id, common.MaskError(rerr), sends, totalSent)
				}
				return
			}
		}
	}()
}

func (rb *RelayBridge) handleUDPAssociate(tcpConn net.Conn) {
	udpAddr, err := net.ResolveUDPAddr("udp", "127.0.0.1:0")
	if err != nil {
		tcpConn.Write(common.GenFail)
		tcpConn.Close()
		return
	}
	udpConn, err := net.ListenUDP("udp", udpAddr)
	if err != nil {
		tcpConn.Write(common.GenFail)
		tcpConn.Close()
		return
	}
	localAddr := udpConn.LocalAddr().(*net.UDPAddr)
	reply := []byte{common.Ver, 0x00, 0x00, common.AtypIPv4, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(reply[8:10], uint16(localAddr.Port))
	tcpConn.Write(reply)

	go func() {
		buf := make([]byte, 1)
		tcpConn.Read(buf)
		udpConn.Close()
	}()

	go func() {
		defer udpConn.Close()
		defer tcpConn.Close()
		defer func() {
			// Replies can be lost or omitted. Retire only this association's
			// requests after its reader stops creating new entries.
			rb.udpClients.Range(func(key, value any) bool {
				if client, ok := value.(*udpClient); ok && client.udpConn == udpConn {
					rb.udpClients.Delete(key)
				}
				return true
			})
		}()
		buf := make([]byte, common.UDPBufSize)
		for {
			n, addr, err := udpConn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if n < 10 {
				continue
			}
			frag := buf[2]
			if frag != 0 {
				continue
			}
			dstAddr, headerLen, addrErr := common.ParseAddress(buf, n)
			if addrErr != nil {
				continue
			}
			id := rb.nextID.Add(1)
			payload := make([]byte, len(dstAddr)+1+n-headerLen)
			payload[0] = byte(len(dstAddr))
			copy(payload[1:], dstAddr)
			copy(payload[1+len(dstAddr):], buf[headerLen:n])
			// The next read reuses buf, while this request's reply can arrive later.
			header := append([]byte(nil), buf[:headerLen]...)
			rb.udpClients.Store(id, &udpClient{udpConn: udpConn, clientAddr: addr, socksHdr: header})
			rb.send(id, MsgUDP, payload)
		}
	}()
}
