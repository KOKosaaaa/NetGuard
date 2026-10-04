package tunnel

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/common"
)

// Bind the REAL production writer/VP8 packetizer to a deterministic RTP link.
// This exercises socket -> relay -> pacing/ACK/AEAD -> RTP fragmentation ->
// WB reassembly -> AEAD/ARQ -> destination socket, in both directions.
type pcRTPLink struct {
	peer                       *VP8DataTunnel
	assembler                  common.VP8Assembler
	packets                    []*rtp.Packet
	frames, reordered, dropped atomic.Int64
}

func (l *pcRTPLink) Write(b []byte) (int, error) {
	p := &rtp.Packet{}
	if err := p.Unmarshal(b); err != nil {
		return 0, err
	}
	return l.WriteRTP(&p.Header, p.Payload)
}
func (l *pcRTPLink) WriteRTP(h *rtp.Header, b []byte) (int, error) {
	p := (&rtp.Packet{Header: *h, Payload: b}).Clone()
	l.packets = append(l.packets, p)
	if !p.Marker {
		return len(b), nil
	}
	l.frames.Add(1)
	ps := l.packets
	l.packets = nil
	if len(ps) > 3 {
		ps[1], ps[2] = ps[2], ps[1]
		l.reordered.Add(1)
	}
	for i, p := range ps {
		if len(ps) > 3 && l.reordered.Load()%37 == 0 && i == 1 {
			l.dropped.Add(1)
			continue
		}
		if frame := l.assembler.Push(p); frame != nil {
			l.peer.HandleFrame(frame)
		}
		if i == 1 { // duplicate middle fragment
			if frame := l.assembler.Push(p); frame != nil {
				l.peer.HandleFrame(frame)
			}
		}
	}
	return len(b), nil
}

type pcTrackContext struct{ link *pcRTPLink }

func (c pcTrackContext) CodecParameters() []webrtc.RTPCodecParameters {
	return []webrtc.RTPCodecParameters{{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, PayloadType: 96}}
}
func (c pcTrackContext) HeaderExtensions() []webrtc.RTPHeaderExtensionParameter { return nil }
func (c pcTrackContext) SSRC() webrtc.SSRC                                      { return 1 }
func (c pcTrackContext) SSRCRetransmission() webrtc.SSRC                        { return 0 }
func (c pcTrackContext) SSRCForwardErrorCorrection() webrtc.SSRC                { return 0 }
func (c pcTrackContext) WriteStream() webrtc.TrackLocalWriter                   { return c.link }
func (c pcTrackContext) ID() string                                             { return "file-test" }
func (c pcTrackContext) RTCPReader() interceptor.RTCPReader                     { return nil }

func TestPCFileThroughSocketsAndRTP(t *testing.T) {
	if !carrierPCARQ || !carrierMode {
		t.Skip("requires WB environment")
	}
	t.Setenv("WLB_CARRIER_KBPS", "2800")
	var peers [2]*VP8DataTunnel
	var links [2]*pcRTPLink
	for i := range peers {
		track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "video", "stream")
		if err != nil {
			t.Fatal(err)
		}
		links[i] = &pcRTPLink{}
		if _, err = track.Bind(pcTrackContext{links[i]}); err != nil {
			t.Fatal(err)
		}
		obf, err := NewTunnelObfuscator([]byte("large-file-test"))
		if err != nil {
			t.Fatal(err)
		}
		peers[i] = NewVP8DataTunnel(track, obf, t.Logf)
	}
	links[0].peer, links[1].peer = peers[1], peers[0]
	joiner := NewRelayBridge(peers[0], "joiner", common.VP8BufSize, t.Logf)
	creator := NewRelayBridge(peers[1], "creator", common.VP8BufSize, t.Logf)
	joiner.MarkReady()
	for _, p := range peers {
		p.Start(24, 1)
		t.Cleanup(p.Stop)
	}
	t.Cleanup(joiner.Close)
	t.Cleanup(creator.Close)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		io.Copy(c, c)
	}()
	client, local := net.Pipe()
	t.Cleanup(func() { client.Close(); local.Close() })
	client.SetDeadline(time.Now().Add(120 * time.Second))
	go joiner.handleSOCKS(local)
	if _, err := client.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	var auth [2]byte
	if _, err := io.ReadFull(client, auth[:]); err != nil || auth != [2]byte{5, 0} {
		t.Fatalf("SOCKS auth %v %v", auth, err)
	}
	req := []byte{5, 1, 0, 1, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(req[8:], uint16(ln.Addr().(*net.TCPAddr).Port))
	if _, err := client.Write(req); err != nil {
		t.Fatal(err)
	}
	var reply [10]byte
	if _, err := io.ReadFull(client, reply[:]); err != nil || reply[1] != 0 {
		t.Fatalf("SOCKS connect %v %v", reply, err)
	}
	want := make([]byte, 3*1024*1024)
	for i := range want {
		want[i] = byte(i*37 + i/1126)
	}
	written := make(chan error, 1)
	go func() { _, err := client.Write(want); written <- err }()
	got := make([]byte, len(want))
	if _, err := io.ReadFull(client, got); err != nil {
		t.Fatal(err)
	}
	if err := <-written; err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("file bytes corrupted")
	}
	for _, link := range links {
		if link.reordered.Load() == 0 || link.dropped.Load() == 0 {
			t.Fatalf("RTP faults not exercised: frames=%d reordered=%d dropped=%d", link.frames.Load(), link.reordered.Load(), link.dropped.Load())
		}
	}
	t.Logf("3 MiB upload + 3 MiB download verified SHA256=%x, reordered=%d/%d dropped=%d/%d", sha256.Sum256(got), links[0].reordered.Load(), links[1].reordered.Load(), links[0].dropped.Load(), links[1].dropped.Load())
}
