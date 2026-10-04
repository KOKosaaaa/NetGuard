package tunnel

import (
	"testing"
	"time"
)

// stubTunnel is a no-op DataTunnel for bridge-level tests (no real carrier).
type stubTunnel struct{}

func (stubTunnel) SendData(_ []byte)             {}
func (stubTunnel) SendDataTo(_ uint32, _ []byte) {}
func (stubTunnel) SetOnData(_ func([]byte))      {}
func (stubTunnel) SetOnClose(_ func())           {}
func (stubTunnel) Reconfigure(_ int, _ int)      {}

func isReady(rb *RelayBridge) bool {
	select {
	case <-rb.ready:
		return true
	default:
		return false
	}
}

// TestWarmupGateOpensOnConfigAck: the cold-start gate must hold SOCKS readiness
// until a MsgConfigAck arrives, then open immediately — and still run the
// previously-registered onConfigAck callback (so session ack handling survives).
func TestWarmupGateOpensOnConfigAck(t *testing.T) {
	rb := NewRelayBridge(stubTunnel{}, "joiner", 4096, func(string, ...any) {})
	prevRan := false
	rb.SetOnConfigAck(func() { prevRan = true })
	rb.MarkReadyOnConfigAck(5 * time.Second)

	if isReady(rb) {
		t.Fatal("SOCKS should NOT be ready before config ack")
	}

	// Simulate the peer's MsgConfigAck arriving on the control conn.
	rb.handleTunnelData(EncodeFrame(ControlConnID, MsgConfigAck, nil))

	if !isReady(rb) {
		t.Fatal("SOCKS should be ready after config ack")
	}
	if !prevRan {
		t.Fatal("previously-registered onConfigAck callback must still run")
	}
}

// TestWarmupGateTimeoutFallback: if the ack never comes, the gate must still open
// SOCKS after the timeout so the proxy never wedges permanently.
func TestWarmupGateTimeoutFallback(t *testing.T) {
	rb := NewRelayBridge(stubTunnel{}, "joiner", 4096, func(string, ...any) {})
	rb.MarkReadyOnConfigAck(80 * time.Millisecond)

	if isReady(rb) {
		t.Fatal("SOCKS should NOT be ready immediately")
	}
	time.Sleep(200 * time.Millisecond)
	if !isReady(rb) {
		t.Fatal("SOCKS should be ready after the timeout fallback")
	}
}
