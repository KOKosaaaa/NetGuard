package tunnel

import (
	"bytes"
	"testing"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/common"
)

func TestPCQAMediaControlIsBoundedCopiedAndPinsDestination(t *testing.T) {
	if !carrierPCARQ {
		t.Skip("requires PCARQ")
	}
	obf, old, fresh := qaBoundObf(t, false), qaBoundObf(t, true), qaBoundObf(t, true)
	child, _ := qaReconnectPeer(t, obf)
	m := NewMultiTrackTunnel([]*VP8DataTunnel{child})
	t.Cleanup(m.Stop)
	p := append([]byte{MediaPong}, bytes.Repeat([]byte{42}, 16)...)
	if m.SendMediaControl(p) {
		t.Fatal("unaddressed control accepted")
	}
	obf.SetRecipientEpoch(old.LocalEpoch(), true)
	if !m.SendMediaControl(p) || !m.SendMediaControl(p) || m.SendMediaControl(p) {
		t.Fatal("control queue must contain at most two packets without blocking")
	}
	p[1] = 99
	obf.SetRecipientEpoch(fresh.LocalEpoch(), true)
	queued := <-child.mediaControl
	if queued.payload[1] != 42 || uint32(queued.address) != old.LocalEpoch() {
		t.Fatal("enqueue did not copy payload and pin old recipient")
	}
	wire := obf.EncodeDataFor(queued.payload, queued.address)
	if fresh.InspectCarrierFrame(wire).HasFrame || !old.InspectCarrierFrame(wire).HasFrame {
		t.Fatal("queued old-peer response was retargeted to replacement")
	}
	m.Stop()
	if m.SendMediaControl(p) {
		t.Fatal("closed carrier accepted a heartbeat")
	}
}

type qaMediaRTPWriter struct {
	assembler common.VP8Assembler
	frames    chan []byte
}

func (w *qaMediaRTPWriter) Write(p []byte) (int, error) {
	var packet rtp.Packet
	if err := packet.Unmarshal(p); err != nil {
		return 0, err
	}
	return w.WriteRTP(&packet.Header, packet.Payload)
}

func (w *qaMediaRTPWriter) WriteRTP(h *rtp.Header, p []byte) (int, error) {
	if frame := w.assembler.Push((&rtp.Packet{Header: *h, Payload: p}).Clone()); frame != nil {
		select {
		case w.frames <- frame:
		default:
		}
	}
	return len(p), nil
}

type qaMediaTrackContext struct {
	pcTrackContext
	w *qaMediaRTPWriter
}

func (c qaMediaTrackContext) WriteStream() webrtc.TrackLocalWriter { return c.w }

func TestPCQAMediaControlActuallyLeavesRTPWriterWithBlockedConsumerAndSendDebt(t *testing.T) {
	if !carrierPCARQ || !carrierMode {
		t.Skip("requires WB carrier")
	}
	t.Setenv("WLB_PC_FPS", "96")
	t.Setenv("WLB_CARRIER_KBPS", "200")
	local, remote := qaBoundObf(t, false), qaBoundObf(t, true)
	local.SetRecipientEpoch(remote.LocalEpoch(), true)
	remote.SetRecipientEpoch(local.LocalEpoch(), true)
	track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "media-qa", "qa")
	if err != nil {
		t.Fatal(err)
	}
	w := &qaMediaRTPWriter{frames: make(chan []byte, 128)}
	if _, err = track.Bind(qaMediaTrackContext{w: w}); err != nil {
		t.Fatal(err)
	}
	child := NewVP8DataTunnel(track, local, func(string, ...any) {})
	m := NewMultiTrackTunnel([]*VP8DataTunnel{child})
	t.Cleanup(m.Stop)
	entered, release := make(chan struct{}), make(chan struct{})
	defer close(release)
	m.SetOnData(func([]byte) { close(entered); <-release })
	m.Start(96, 1)
	child.pcHandle(pcHelloPacket())
	source, _ := qaReconnectPeer(t, remote)
	child.HandleFrame(remote.EncodeData(source.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("blocked consumer"))})))
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("consumer not blocked")
	}
	// Outstanding reliable work and no outbound ACKs must not starve the
	// separate health packet even at the 200kbps floor / 96fps cadence.
	for i := 0; i < 128; i++ {
		child.pcEnqueue(EncodeFrame(8, MsgData, make([]byte, 4096)))
	}
	want := append([]byte{MediaPong}, bytes.Repeat([]byte{73}, 16)...)
	if !m.SendMediaControl(want) {
		t.Fatal("health response rejected")
	}
	deadline := time.After(time.Second)
	for {
		select {
		case frame := <-w.frames:
			r := remote.InspectCarrierFrame(frame)
			if r.HasFrame && bytes.Equal(r.Payload, want) {
				return
			}
		case <-deadline:
			t.Fatal("health was queued but never left real encrypted VP8/RTP writer under backpressure")
		}
	}
}

func TestPCQABlockedApplicationDoesNotBlockMediaControlEnqueue(t *testing.T) {
	if !carrierPCARQ {
		t.Skip("requires PCARQ")
	}
	sender, receiver := qaBoundObf(t, true), qaBoundObf(t, false)
	child, _ := qaReconnectPeer(t, receiver)
	receiver.SetRecipientEpoch(sender.LocalEpoch(), true)
	source, _ := qaReconnectPeer(t, sender)
	sender.SetRecipientEpoch(receiver.LocalEpoch(), true)
	m := NewMultiTrackTunnel([]*VP8DataTunnel{child})
	t.Cleanup(m.Stop)
	entered, release := make(chan struct{}), make(chan struct{})
	defer close(release)
	child.OnData = func([]byte) { close(entered); <-release }
	child.HandleFrame(sender.EncodeData(source.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("blocked consumer"))})))
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("real application delivery did not block")
	}
	done := make(chan bool, 1)
	go func() { done <- m.SendMediaControl(append([]byte{MediaPong}, make([]byte, 16)...)) }()
	select {
	case ok := <-done:
		if !ok {
			t.Fatal("application backpressure prevented media response enqueue")
		}
	case <-time.After(time.Second):
		t.Fatal("media control shares a blocking application-delivery lock")
	}
}
