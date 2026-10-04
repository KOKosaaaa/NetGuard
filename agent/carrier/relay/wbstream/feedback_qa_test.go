package wbstream

import (
	"bytes"
	"encoding/binary"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/tunnel"
)

type qaFeedbackObserver struct {
	interceptor.NoOp
	writes chan *rtp.Packet
}

func (o *qaFeedbackObserver) BindLocalStream(_ *interceptor.StreamInfo, w interceptor.RTPWriter) interceptor.RTPWriter {
	return interceptor.RTPWriterFunc(func(h *rtp.Header, p []byte, a interceptor.Attributes) (int, error) {
		copy := &rtp.Packet{Header: h.Clone(), Payload: append([]byte(nil), p...)}
		select {
		case o.writes <- copy:
		default:
		}
		return w.Write(h, p, a)
	})
}

type qaFeedbackFactory struct{ observer *qaFeedbackObserver }

func (f qaFeedbackFactory) NewInterceptor(string) (interceptor.Interceptor, error) {
	return f.observer, nil
}

// Local ICE/DTLS/SRTP peers, no WB room, server, credentials, or public network.
// Deliberately do not read publisher RTCP until after the baseline NACK.
func TestQARealPublisherFeedbackDrivesNackKeyframeAndStopsOnClose(t *testing.T) {
	var se webrtc.SettingEngine
	se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	se.SetIncludeLoopbackCandidate(true)
	se.SetIPFilter(func(ip net.IP) bool { return ip.IsLoopback() })
	observer := &qaFeedbackObserver{writes: make(chan *rtp.Packet, 32)}
	me := &webrtc.MediaEngine{}
	if err := me.RegisterDefaultCodecs(); err != nil {
		t.Fatal(err)
	}
	registry := &interceptor.Registry{}
	// Place observation inside NACK responder so retransmissions are measured,
	// including RTX which the receiving app may discard as an already-read seq.
	registry.Add(qaFeedbackFactory{observer})
	if err := webrtc.RegisterDefaultInterceptors(me, registry); err != nil {
		t.Fatal(err)
	}
	api := webrtc.NewAPI(webrtc.WithSettingEngine(se), webrtc.WithMediaEngine(me), webrtc.WithInterceptorRegistry(registry))
	pub, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer pub.Close()
	sub, err := webrtc.NewAPI(webrtc.WithSettingEngine(se)).NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer sub.Close()
	track, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "qa-video", "qa-stream")
	if err != nil {
		t.Fatal(err)
	}
	sender, err := pub.AddTrack(track)
	if err != nil {
		t.Fatal(err)
	}
	packets := make(chan *rtp.Packet, 32)
	sub.OnTrack(func(remote *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
		for {
			p, _, err := remote.ReadRTP()
			if err != nil {
				return
			}
			select {
			case packets <- p:
			default:
			}
		}
	})
	connected := make(chan struct{})
	var once sync.Once
	pub.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		if state == webrtc.PeerConnectionStateConnected {
			once.Do(func() { close(connected) })
		}
	})
	offer, err := pub.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gather := webrtc.GatheringCompletePromise(pub)
	if err = pub.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gather:
	case <-time.After(5 * time.Second):
		t.Fatal("publisher ICE gathering timeout")
	}
	if err = sub.SetRemoteDescription(*pub.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	answer, err := sub.CreateAnswer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gather = webrtc.GatheringCompletePromise(sub)
	if err = sub.SetLocalDescription(answer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gather:
	case <-time.After(5 * time.Second):
		t.Fatal("subscriber ICE gathering timeout")
	}
	if err = pub.SetRemoteDescription(*sub.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	select {
	case <-connected:
	case <-time.After(5 * time.Second):
		t.Fatal("local peer connection timeout")
	}
	payload := []byte{0x10, 0x01, 0x00, 0x00, 0x41, 0x42, 0x43}
	if err = track.WriteRTP(&rtp.Packet{Header: rtp.Header{Version: 2, SequenceNumber: 123, Timestamp: 90000, Marker: true}, Payload: payload}); err != nil {
		t.Fatal(err)
	}
	var original *rtp.Packet
	select {
	case original = <-packets:
	case <-time.After(3 * time.Second):
		t.Fatal("initial RTP missing")
	}
	if original.SequenceNumber != 123 || !bytes.Equal(original.Payload, payload) {
		t.Fatal("initial RTP changed")
	}
	select {
	case <-observer.writes:
	case <-time.After(time.Second):
		t.Fatal("initial outgoing RTP not observed")
	}
	nack := &rtcp.TransportLayerNack{SenderSSRC: 1, MediaSSRC: original.SSRC, Nacks: []rtcp.NackPair{{PacketID: 123}}}
	if err = sub.WriteRTCP([]rtcp.Packet{nack}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-observer.writes:
		t.Fatal("baseline unexpectedly retransmitted without RTCP reader")
	case <-time.After(150 * time.Millisecond):
	}
	obf, _ := tunnel.NewTunnelObfuscator([]byte("qa-feedback-key"))
	obf.EncodeKeepalive()
	if frame := obf.EncodeKeepalive(); frame[0]&1 == 0 {
		t.Skip("requires WLB_VALID_VP8_TUNNEL=1")
	}
	report := make(chan struct{}, 1)
	s := NewSession(SessionConfig{Obfuscator: obf, LogFn: func(string, ...any) {
		select {
		case report <- struct{}{}:
		default:
		}
	}})
	finished := make(chan struct{})
	go func() { defer close(finished); s.readPublisherFeedback(sender) }()
	select {
	case retransmitted := <-observer.writes:
		plain := retransmitted.Payload
		seq := retransmitted.SequenceNumber
		if retransmitted.SSRC != original.SSRC && len(plain) >= 2 {
			seq = binary.BigEndian.Uint16(plain[:2])
			plain = plain[2:]
		}
		if seq != original.SequenceNumber || !bytes.Equal(plain, original.Payload) {
			t.Fatal("NACK retransmission changed bytes")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("RTCP reader did not activate real Pion NACK retransmission")
	}
	// Drain the first log so a second unrelated log cannot stand in for PLI.
	select {
	case <-report:
	case <-time.After(time.Second):
		t.Fatal("feedback loop did not process NACK")
	}
	if err = sub.WriteRTCP([]rtcp.Packet{&rtcp.PictureLossIndication{SenderSSRC: 1, MediaSSRC: original.SSRC}}); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(time.Second)
	found := false
	for time.Now().Before(deadline) {
		frame := obf.EncodeData([]byte("after PLI"))
		if frame[0]&1 == 0 {
			found = true
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	if !found {
		t.Fatal("PLI failed to request the next keyframe")
	}
	if err = sender.Stop(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("sender close did not unblock RTCP reader")
	}
}
