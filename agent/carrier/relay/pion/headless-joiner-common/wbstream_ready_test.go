package joiner

import (
	"testing"
	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/tunnel"
	"whitelist-bypass/relay/wbstream"
)

type wbTestStatus struct{ statuses []string }

func (s *wbTestStatus) EmitStatus(v string)      { s.statuses = append(s.statuses, v) }
func (s *wbTestStatus) EmitStatusError(v string) { s.statuses = append(s.statuses, "error:"+v) }

func TestWBVideoReadyRequiresExitACK(t *testing.T) {
	status := &wbTestStatus{}
	j := NewWBStreamHeadlessJoiner(t.Logf, nil, status, nil)
	j.session = wbstream.NewSession(wbstream.SessionConfig{LogFn: t.Logf})
	wired := false
	j.OnConnected = func(tunnel.DataTunnel) { wired = true }
	j.onCarrierConnected(&tunnel.MultiTrackTunnel{})
	if !wired {
		t.Fatal("bridge must receive ACKs before readiness")
	}
	if len(status.statuses) != 0 {
		t.Fatal("publisher alone was reported connected")
	}
	j.MarkConfigAcked()
	j.MarkConfigAcked()
	if len(status.statuses) != 1 || status.statuses[0] != common.StatusTunnelConnected {
		t.Fatalf("expected exactly one confirmed readiness, got %v", status.statuses)
	}
	// A fresh session must earn readiness again; the previous ACK cannot do it.
	j.session = wbstream.NewSession(wbstream.SessionConfig{LogFn: t.Logf})
	j.onCarrierConnected(&tunnel.MultiTrackTunnel{})
	if len(status.statuses) != 1 {
		t.Fatal("reconnect reused stale readiness")
	}
	j.MarkConfigAcked()
	if len(status.statuses) != 2 {
		t.Fatal("new session did not become ready")
	}
}
