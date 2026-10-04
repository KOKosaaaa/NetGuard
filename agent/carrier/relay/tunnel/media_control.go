package tunnel

// These packets bypass socket delivery/ARQ queues but use the same encrypted
// video track. A slow application socket must not prevent a liveness reply.
const (
	MediaPing      byte = 0x15
	MediaPong      byte = 0x16
	MediaNonceSize      = 16
)

type addressedMediaControl struct {
	payload []byte
	address uint64
}

func (m *MultiTrackTunnel) MediaHealthSupported() bool { return carrierPCARQ }

// Nonblocking, bounded, and addressed at enqueue time. A queued response from
// an old peer must never be sent to its replacement.
func (m *MultiTrackTunnel) SendMediaControl(payload []byte) bool {
	if !carrierPCARQ || len(payload) != 1+MediaNonceSize || (payload[0] != MediaPing && payload[0] != MediaPong) {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.isClosed || len(m.tunnels) == 0 {
		return false
	}
	t := m.tunnels[0]
	address := t.obf.RecipientSnapshot()
	if address>>32 == 0 || uint32(address) == 0 {
		return false
	}
	select {
	case t.mediaControl <- addressedMediaControl{append([]byte(nil), payload...), address}:
		return true
	default:
		return false
	}
}
