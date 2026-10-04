package tunnel

import (
	"bytes"
	"encoding/binary"
	"testing"
	"time"
)

func qaBoundObf(t *testing.T, strict bool) *TunnelObfuscator {
	t.Helper()
	o, e := NewTunnelObfuscator([]byte("recipient-qa"))
	if e != nil {
		t.Fatal(e)
	}
	o.EnableRecipientBinding(strict)
	return o
}

func TestPCQARecipientBindingDropsPreviousReverseControlBeforeState(t *testing.T) {
	if !carrierPCARQ {
		t.Skip("requires PCARQ")
	}
	oldObf, serverObf := qaBoundObf(t, true), qaBoundObf(t, false)
	old, oldGot := qaReconnectPeer(t, oldObf)
	server, serverGot := qaReconnectPeer(t, serverObf)
	serverObf.SetRecipientEpoch(oldObf.LocalEpoch(), true)
	config, ack := EncodeVP8Config(24, 1, 1), EncodeFrame(ControlConnID, MsgConfigAck, nil)
	for i := 0; i < 3; i++ {
		qaReconnectDeliver(t, old, server, serverGot, config)
		qaReconnectDeliver(t, server, old, oldGot, ack)
	}
	// Save both encrypted old-generation bytes and an already extracted plain
	// payload with its captured destination. Both may leave the writer late.
	oldAddress := serverObf.RecipientSnapshot()
	extracted := server.pcWrapFrames([][]byte{ack})
	delayed := serverObf.EncodeDataFor(extracted, oldAddress)
	old.Stop()
	freshObf := qaBoundObf(t, true)
	fresh, freshGot := qaReconnectPeer(t, freshObf)
	server.ResetPeerRestart()
	serverObf.SetRecipientEpoch(freshObf.LocalEpoch(), true)
	for _, packet := range [][]byte{delayed, serverObf.EncodeDataFor(extracted, oldAddress)} {
		fresh.HandleFrame(packet)
		fresh.pcRecvMu.Lock()
		states := len(fresh.pcExpected) + len(fresh.pcReorder) + len(fresh.pcFlow.consumers)
		fresh.pcRecvMu.Unlock()
		if states != 0 || freshObf.PeerEpoch() != 0 {
			t.Fatal("previous-recipient frame poisoned fresh control cursor or peer identity")
		}
	}
	qaReconnectDeliver(t, fresh, server, serverGot, config)
	qaReconnectDeliver(t, server, fresh, freshGot, ack)
	fresh.HandleFrame(delayed)
	fresh.pcCheckGaps(time.Now().Add(arqSkipAfter + time.Second))
	if !fresh.running.Load() {
		t.Fatal("old reverse frame created phantom gap after successful reconnect")
	}
	select {
	case <-freshGot:
		t.Fatal("old control ACK delivered as fresh readiness")
	default:
	}
}

func TestPCQARecipientBindingAuthenticatedAddressAndStrictDowngrade(t *testing.T) {
	if !carrierMode {
		t.Skip("requires carrier mode")
	}
	receiver, sender := qaBoundObf(t, true), qaBoundObf(t, false)
	payload := []byte("bound application bytes")
	sender.SetRecipientEpoch(receiver.LocalEpoch(), true)
	valid := sender.EncodeData(payload)
	parsed := receiver.InspectCarrierFrame(valid)
	if !parsed.HasFrame || !parsed.RecipientBound || !bytes.Equal(parsed.Payload, payload) {
		t.Fatal("valid addressed frame rejected")
	}
	for name, packet := range map[string][]byte{
		"unbound legacy":    sender.EncodeDataFor(payload, 0),
		"unaddressed type3": sender.EncodeDataFor(payload, 1<<32),
		"other recipient":   sender.EncodeDataFor(payload, (1<<32)|uint64(qaBoundObf(t, true).LocalEpoch())),
	} {
		if receiver.InspectCarrierFrame(packet).HasFrame {
			t.Fatalf("strict receiver accepted %s", name)
		}
	}
	// Changing cleartext sender identity cannot retarget an authenticated frame.
	tag := uint32(valid[0]) | uint32(valid[1])<<8 | uint32(valid[2])<<16
	prefix := 3 + int((tag>>5)&0x7ffff)
	if tag&1 == 0 {
		prefix += 7
	}
	forged := append([]byte(nil), valid...)
	fakeEpoch := sender.LocalEpoch() ^ 0x80000000
	if fakeEpoch == receiver.LocalEpoch() {
		fakeEpoch ^= 1
	}
	binary.BigEndian.PutUint32(forged[prefix+1:prefix+5], fakeEpoch)
	if receiver.InspectCarrierFrame(forged).HasFrame {
		t.Fatal("unauthenticated outer sender changed authenticated identity")
	}
	forged = append([]byte(nil), valid...)
	forged[len(forged)-1] ^= 0x40
	if receiver.InspectCarrierFrame(forged).HasFrame {
		t.Fatal("tampered encrypted destination/payload/tag accepted")
	}
	for i := 0; i < len(valid); i++ {
		if receiver.InspectCarrierFrame(valid[:i]).HasFrame {
			t.Fatalf("truncated addressed data accepted at%d/%d", i, len(valid))
		}
	}
}

func TestPCQARecipientBindingPreservesLegacyServerReplyMode(t *testing.T) {
	if !carrierMode {
		t.Skip("requires carrier mode")
	}
	legacy, server := qaBoundObf(t, false), qaBoundObf(t, false)
	payload := []byte("legacy compatibility")
	in := server.InspectCarrierFrame(legacy.EncodeData(payload))
	if !in.HasFrame || in.RecipientBound {
		t.Fatal("legacy request not recognized")
	}
	server.SetRecipientEpoch(in.PeerEpoch, in.RecipientBound)
	out := legacy.InspectCarrierFrame(server.EncodeData(payload))
	if !out.HasFrame || out.RecipientBound || !bytes.Equal(out.Payload, payload) {
		t.Fatal("new server forced bound replies on a legacy client")
	}
}
