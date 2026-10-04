package tunnel

import (
	"encoding/binary"
	"sync"
)

type MultiTrackTunnel struct {
	tunnels []*VP8DataTunnel

	mu       sync.Mutex
	onData   func([]byte)
	onClose  func()
	done     chan struct{}
	isClosed bool
	fps      int
	batch    int
}

func NewMultiTrackTunnel(tunnels []*VP8DataTunnel) *MultiTrackTunnel {
	m := &MultiTrackTunnel{tunnels: tunnels, done: make(chan struct{})}
	for i, tun := range tunnels {
		// Only the cam (index 0) carries the cascade-on-close semantics;
		// screenshare tracks close independently during partial shrink.
		m.wireSubTunnel(tun, i == 0)
	}
	return m
}

func (m *MultiTrackTunnel) wireSubTunnel(tun *VP8DataTunnel, isCamera bool) {
	tun.SetOnData(func(data []byte) {
		m.mu.Lock()
		handler := m.onData
		m.mu.Unlock()
		if handler != nil {
			handler(data)
		}
	})
	if !isCamera {
		// Screenshare close is a partial shrink; it must not cascade-Stop
		// the cam writer or notify the parent that the whole tunnel died.
		return
	}
	tun.SetOnClose(func() {
		m.mu.Lock()
		if m.isClosed {
			m.mu.Unlock()
			return
		}
		m.isClosed = true
		close(m.done)
		closeHandler := m.onClose
		subTunnels := m.tunnels
		m.mu.Unlock()

		for _, t := range subTunnels {
			t.Stop()
		}
		if closeHandler != nil {
			closeHandler()
		}
	})
}

func (m *MultiTrackTunnel) AddSubTunnel(tun *VP8DataTunnel) {
	m.mu.Lock()
	if m.isClosed {
		m.mu.Unlock()
		tun.Stop()
		return
	}
	m.tunnels = append(m.tunnels, tun)
	fps := m.fps
	batch := m.batch
	m.mu.Unlock()
	m.wireSubTunnel(tun, false)
	if fps > 0 && batch > 0 {
		tun.Start(fps, batch)
	}
}

func (m *MultiTrackTunnel) RemoveLastSubTunnel() *VP8DataTunnel {
	m.mu.Lock()
	if len(m.tunnels) <= 1 {
		m.mu.Unlock()
		return nil
	}
	last := m.tunnels[len(m.tunnels)-1]
	m.tunnels = m.tunnels[:len(m.tunnels)-1]
	m.mu.Unlock()
	last.Stop()
	return last
}

func (m *MultiTrackTunnel) SubTunnelCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.tunnels)
}

// SendDataTo: MultiTrackTunnel has no per-peer addressing (interface stub).
func (m *MultiTrackTunnel) SendDataTo(epoch uint32, data []byte) { m.SendData(data) }

func (m *MultiTrackTunnel) SendData(data []byte) {
	m.mu.Lock()
	tunnels := m.tunnels
	m.mu.Unlock()
	if len(tunnels) == 0 {
		return
	}
	// Under ARQ every sub-tunnel assigns its OWN per-frame seq (starting at 1),
	// but the RECEIVE side merges all tracks into a single cursor (HandleFrame
	// feeds tunnels[0]). Sharding data across tracks would create N independent
	// seq spaces that collide on that one cursor — the 2nd+ track's frames land
	// below the cursor and get dropped as duplicates, silently corrupting the
	// byte stream (verified: 2 tracks => only 1 delivered). So with ARQ we keep
	// ONE seq space by routing all data through tunnels[0]; the extra tracks
	// still emit keepalives (harmless, keeps the SFU warm). For more than one
	// track's worth of throughput, scale out with MULTIPLE ROOMS (independent
	// sessions/obfuscators — proven ~2.6x per room, no shared seq space), not
	// in-room multi-track. Non-ARQ carriers have no seq space, so the original
	// connID-sharding (TCP-order-preserving parallelism) is kept.
	if carrierARQ || carrierPCARQ {
		tunnels[0].SendData(data)
		return
	}
	var connID uint32
	if len(data) >= 8 {
		connID = binary.BigEndian.Uint32(data[4:8])
	}
	idx := connID % uint32(len(tunnels))
	tunnels[idx].SendData(data)
}

func (m *MultiTrackTunnel) SetOnData(fn func([]byte)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.onData = fn
}

// Done observes transport termination independently of the bridge callback.
func (m *MultiTrackTunnel) Done() <-chan struct{} { return m.done }

func (m *MultiTrackTunnel) SetOnClose(fn func()) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.onClose = fn
}

func (m *MultiTrackTunnel) Reconfigure(fps, batch int) {
	m.mu.Lock()
	m.fps = fps
	m.batch = batch
	tunnels := m.tunnels
	m.mu.Unlock()
	for _, tun := range tunnels {
		tun.Reconfigure(fps, batch)
	}
}

func (m *MultiTrackTunnel) Start(fps, batch int) {
	m.mu.Lock()
	m.fps = fps
	m.batch = batch
	tunnels := m.tunnels
	m.mu.Unlock()
	for _, tun := range tunnels {
		tun.Start(fps, batch)
	}
}

func (m *MultiTrackTunnel) Stop() {
	m.mu.Lock()
	if m.isClosed {
		m.mu.Unlock()
		return
	}
	m.isClosed = true
	close(m.done)
	tunnels := m.tunnels
	closeHandler := m.onClose
	m.mu.Unlock()
	for _, tun := range tunnels {
		tun.Stop()
	}
	// Setting isClosed above suppresses the camera's cascading callback.
	// Explicit parent shutdown must still notify the bridge exactly once.
	if closeHandler != nil {
		closeHandler()
	}
}

// ResetPeerRestart fans the peer-restart receive reset (see
// VP8DataTunnel.ResetPeerRestart) out to every sub-tunnel. The obfuscator is
// shared across sub-tunnels, so its peer-lock gets reset more than once — that
// is idempotent. Only sub-tunnel 0 actually carries the ARQ receive cursor
// (HandleFrame routes all inbound frames there), but resetting the rest is
// harmless and keeps the call total when tracks are added later.
func (m *MultiTrackTunnel) ResetPeerRestart() {
	m.mu.Lock()
	tunnels := m.tunnels
	m.mu.Unlock()
	for _, tun := range tunnels {
		tun.ResetPeerRestart()
	}
}

func (m *MultiTrackTunnel) HandleFrame(frame []byte) {
	m.mu.Lock()
	var first *VP8DataTunnel
	if len(m.tunnels) > 0 {
		first = m.tunnels[0]
	}
	m.mu.Unlock()
	if first != nil {
		first.HandleFrame(frame)
	}
}
