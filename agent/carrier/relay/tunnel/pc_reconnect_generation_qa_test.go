package tunnel

import (
	"bytes"
	"runtime"
	"testing"
	"time"
)

func qaReconnectPeer(t *testing.T, obf *TunnelObfuscator) (*VP8DataTunnel, <-chan []byte) {
	t.Helper()
	peer := NewVP8DataTunnelMulti(nil, obf, func(string, ...any) {})
	peer.running.Store(true)
	peer.pcHandle(pcHelloPacket())
	frames := make(chan []byte, 16)
	peer.OnData = func(frame []byte) { frames <- append([]byte(nil), frame...) }
	t.Cleanup(peer.Stop)
	return peer, frames
}

func qaReconnectDeliver(t *testing.T, src, dst *VP8DataTunnel, got <-chan []byte, frame []byte) []byte {
	t.Helper()
	wire := src.obf.EncodeData(src.pcWrapFrames([][]byte{frame}))
	dst.HandleFrame(wire)
	select {
	case actual := <-got:
		if !bytes.Equal(actual, frame) {
			t.Fatal("unexpected relay frame")
		}
	case <-time.After(time.Second):
		t.Fatal("encrypted carrier frame was not delivered")
	}
	deadline := time.Now().Add(time.Second)
	for {
		src.HandleFrame(dst.obf.EncodeData(dst.pcAckPacket()))
		src.pcSendMu.Lock()
		pending := len(src.pcSendBuf)
		src.pcSendMu.Unlock()
		if pending == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("consumption ACK missing")
		}
		runtime.Gosched()
	}
	return wire
}

// Diagnostic negative control: a new carrier with the old identity is NOT a
// reconnect. This deliberately reproduces the observed failure rather than
// accepting live per-socket ACKs as proof that the control stream is healthy.
func TestPCQAReusedEpochNewCarrierReproducesLiveDataThenControlDeath(t *testing.T) {
	if !carrierPCARQ {
		t.Skip("requires PCARQ")
	}
	cObf, _ := NewTunnelObfuscator([]byte("reconnect-qa"))
	sObf, _ := NewTunnelObfuscator([]byte("reconnect-qa"))
	client, cGot := qaReconnectPeer(t, cObf)
	server, sGot := qaReconnectPeer(t, sObf)
	config, ack := EncodeVP8Config(24, 1, 1), EncodeFrame(ControlConnID, MsgConfigAck, nil)
	for i := 0; i < 3; i++ {
		qaReconnectDeliver(t, client, server, sGot, config)
		qaReconnectDeliver(t, server, client, cGot, ack)
	}
	client.Stop()
	client, cGot = qaReconnectPeer(t, cObf) // exactly the pre-fix runOnce reuse
	for i := 0; i < 3; i++ {
		server.HandleFrame(cObf.EncodeData(client.pcWrapFrames([][]byte{config})))
	}
	select {
	case <-sGot:
		t.Fatal("old control cursor unexpectedly accepted reset sequence")
	default:
	}
	// A fresh socket id still works and confirms ACK traffic in both directions.
	qaReconnectDeliver(t, client, server, sGot, EncodeFrame(5001, MsgConnect, []byte("fixture.invalid:443")))
	qaReconnectDeliver(t, server, client, cGot, EncodeFrame(5001, MsgConnectOK, nil))
	// Fourth retry finally reaches old expected control seq4. Its reply also
	// has seq4, while the new receiver needs seq1..3 already freed by old ACKs.
	server.HandleFrame(cObf.EncodeData(client.pcWrapFrames([][]byte{config})))
	select {
	case <-sGot:
	case <-time.After(time.Second):
		t.Fatal("fourth config did not reach old cursor")
	}
	client.HandleFrame(sObf.EncodeData(server.pcWrapFrames([][]byte{ack})))
	client.pcRecvMu.Lock()
	gap := client.pcGapAt[ControlConnID]
	expected := client.pcExpected[ControlConnID]
	max := client.pcMaxSeen[ControlConnID]
	client.pcRecvMu.Unlock()
	if gap.IsZero() || expected != 1 || max != 4 {
		t.Fatalf("expected irrecoverable control gap1..3, got expected=%d max=%d gap=%v", expected, max, gap)
	}
	select {
	case <-cGot:
		t.Fatal("gap skipped and stale readiness delivered")
	default:
	}
	client.pcCheckGaps(gap.Add(arqSkipAfter + time.Second))
	if client.running.Load() {
		t.Fatal("control gap did not trigger the observed fatal carrier stop")
	}
}

func TestPCQAFreshReconnectEpochAndPeerResetRestoreBidirectionalControl(t *testing.T) {
	if !carrierPCARQ {
		t.Skip("requires PCARQ")
	}
	cObf, _ := NewTunnelObfuscator([]byte("reconnect-qa"))
	sObf, _ := NewTunnelObfuscator([]byte("reconnect-qa"))
	client, cGot := qaReconnectPeer(t, cObf)
	server, sGot := qaReconnectPeer(t, sObf)
	config, ack := EncodeVP8Config(24, 1, 1), EncodeFrame(ControlConnID, MsgConfigAck, nil)
	for i := 0; i < 3; i++ {
		qaReconnectDeliver(t, client, server, sGot, config)
		qaReconnectDeliver(t, server, client, cGot, ack)
	}
	for generation := 0; generation < 5; generation++ {
		previous := client.obf.LocalEpoch()
		client.Stop()
		fresh, err := NewTunnelObfuscator([]byte("reconnect-qa"))
		if err != nil {
			t.Fatal(err)
		}
		if fresh.LocalEpoch() == previous {
			t.Fatal("fresh generation reused epoch")
		}
		client, cGot = qaReconnectPeer(t, fresh)
		// Session.handleCarrierFrame calls this upon an authenticated new peer.
		server.ResetPeerRestart()
		qaReconnectDeliver(t, client, server, sGot, config)
		qaReconnectDeliver(t, server, client, cGot, ack)
		qaReconnectDeliver(t, client, server, sGot, EncodeFrame(uint32(6000+generation), MsgData, []byte("after reconnect")))
		client.pcCheckGaps(time.Now().Add(arqSkipAfter + time.Second))
		if !client.running.Load() {
			t.Fatal("fresh control generation retained an old gap")
		}
	}
}
