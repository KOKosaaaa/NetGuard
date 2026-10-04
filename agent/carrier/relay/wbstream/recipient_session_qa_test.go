package wbstream

import (
	"testing"
	"time"
	"whitelist-bypass/relay/tunnel"
)

func qaRecipientSession(t *testing.T, joiner bool) *Session {
	t.Helper()
	o, err := tunnel.NewTunnelObfuscator([]byte("recipient-session-qa"))
	if err != nil {
		t.Fatal(err)
	}
	s := NewSession(SessionConfig{IsJoiner: joiner, TunnelMode: TunnelModeVideo, Obfuscator: o, LogFn: func(string, ...any) {}})
	t.Cleanup(s.Close)
	return s
}

func TestWBQARejectedAuthenticatedPeerCannotRetargetReplies(t *testing.T) {
	server, first, other := qaRecipientSession(t, false), qaRecipientSession(t, true), qaRecipientSession(t, true)
	hello := []byte{0x13, 1, 0, 128}
	var firstTrack, otherTrack uint32
	if !server.handleCarrierFrame(first.cfg.Obfuscator.EncodeData(hello), &firstTrack) {
		t.Fatal("initial bound client rejected")
	}
	want := server.cfg.Obfuscator.RecipientSnapshot()
	if uint32(want) != first.cfg.Obfuscator.LocalEpoch() || want>>32 == 0 {
		t.Fatal("accepted client did not bind reverse destination")
	}
	if server.handleCarrierFrame(other.cfg.Obfuscator.EncodeData(hello), &otherTrack) {
		t.Fatal("another authenticated peer displaced active client")
	}
	if server.cfg.Obfuscator.RecipientSnapshot() != want {
		t.Fatal("rejected peer changed encrypted reverse recipient")
	}
	server.receiveMu.Lock()
	server.carrierPeer.closeTrack(firstTrack)
	server.receiveMu.Unlock()
	if !server.handleCarrierFrame(other.cfg.Obfuscator.EncodeData(hello), &otherTrack) {
		t.Fatal("replacement after departure not accepted")
	}
	if uint32(server.cfg.Obfuscator.RecipientSnapshot()) != other.cfg.Obfuscator.LocalEpoch() {
		t.Fatal("accepted replacement did not change reverse recipient")
	}
	if server.handleCarrierFrame(first.cfg.Obfuscator.EncodeData(hello), &firstTrack) {
		t.Fatal("late old track displaced fresh client")
	}
	if uint32(server.cfg.Obfuscator.RecipientSnapshot()) != other.cfg.Obfuscator.LocalEpoch() {
		t.Fatal("late old track retargeted reply")
	}
}

func TestWBQAAuthenticatedDiscoveryFindsRestartedServerWithoutFalseReadiness(t *testing.T) {
	oldServer, newServer, client := qaRecipientSession(t, false), qaRecipientSession(t, false), qaRecipientSession(t, true)
	hello := []byte{0x13, 1, 0, 128}
	client.cfg.Obfuscator.SetRecipientEpoch(oldServer.cfg.Obfuscator.LocalEpoch(), true)
	oldServer.cfg.Obfuscator.SetRecipientEpoch(client.cfg.Obfuscator.LocalEpoch(), true)
	var oldTrack, newTrack, clientTrack uint32
	if !client.handleCarrierFrame(oldServer.cfg.Obfuscator.EncodeData(hello), &oldTrack) {
		t.Fatal("old server did not establish generation")
	}
	// Ordinary addressed traffic cannot find a server with a different epoch.
	if newServer.handleCarrierFrame(client.cfg.Obfuscator.EncodeData(hello), &clientTrack) {
		t.Fatal("new server accepted traffic addressed to old epoch")
	}
	if !client.cfg.Obfuscator.NeedsRecipientDiscovery() || newServer.cfg.Obfuscator.NeedsRecipientDiscovery() {
		t.Fatal("discovery roles reversed")
	}
	if !newServer.handleCarrierFrame(client.cfg.Obfuscator.EncodeDataFor(hello, 1<<32), &clientTrack) {
		t.Fatal("authenticated destination-zero discovery was rejected")
	}
	if uint32(newServer.cfg.Obfuscator.RecipientSnapshot()) != client.cfg.Obfuscator.LocalEpoch() {
		t.Fatal("discovery did not establish reverse destination")
	}
	select {
	case <-newServer.configAcked:
		t.Fatal("discovery hello was mistaken for config ACK")
	default:
	}
	select {
	case <-client.configAcked:
		t.Fatal("discovery hello falsely announced exit readiness")
	default:
	}
	client.receiveMu.Lock()
	client.carrierPeer.closeTrack(oldTrack)
	client.receiveMu.Unlock()
	if client.handleCarrierFrame(newServer.cfg.Obfuscator.EncodeData(hello), &newTrack) {
		t.Fatal("new server epoch continued old TCP generation")
	}
	select {
	case <-client.Done():
	case <-time.After(time.Second):
		t.Fatal("restarted server was found but joiner did not rejoin")
	}
}
