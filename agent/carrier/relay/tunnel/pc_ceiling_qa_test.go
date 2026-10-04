package tunnel

import (
	"bytes"
	"encoding/binary"
	"testing"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"whitelist-bypass/relay/common"
)

type qaCeilingWriter struct{ packets []*rtp.Packet }

func (w *qaCeilingWriter) Write(b []byte) (int, error) {
	p := &rtp.Packet{}
	if err := p.Unmarshal(b); err != nil {
		return 0, err
	}
	w.packets = append(w.packets, p)
	return len(b), nil
}
func (w *qaCeilingWriter) WriteRTP(h *rtp.Header, b []byte) (int, error) {
	w.packets = append(w.packets, (&rtp.Packet{Header: *h, Payload: b}).Clone())
	return len(b), nil
}

type qaCeilingContext struct {
	pcTrackContext
	writer *qaCeilingWriter
}

func (c qaCeilingContext) WriteStream() webrtc.TrackLocalWriter { return c.writer }

// Deliberately tests the largest saved-credit burst, not the smaller ordinary
// 62500-byte tick. Uses the real production sample packetizer and assembler.
func TestPCQA12MbpsWorstBurstFitsEncryptedVP8AndRTPAssembler(t *testing.T) {
	if !carrierMode {
		t.Skip("requires WLB_VALID_VP8_TUNNEL=1")
	}
	const credit = (12000*1000/8/24)*2 + 4096
	for _, keyframe := range []bool{true, false} {
		enc, _ := NewTunnelObfuscator([]byte("qa-ceiling"))
		dec, _ := NewTunnelObfuscator([]byte("qa-ceiling"))
		if !keyframe {
			enc.EncodeKeepalive()
		}
		payload := make([]byte, credit)
		for i := range payload {
			payload[i] = byte(i*31 + i/1126)
		}
		sample := enc.EncodeData(payload)
		if len(sample) > 128*1024 {
			t.Fatalf("encrypted sample exceeds assembler: %d", len(sample))
		}
		if (sample[0]&1 == 0) != keyframe {
			t.Fatal("fixture tested wrong VP8 prefix")
		}
		track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "qa-ceiling", "qa-stream")
		if err != nil {
			t.Fatal(err)
		}
		writer := &qaCeilingWriter{}
		if _, err = track.Bind(qaCeilingContext{writer: writer}); err != nil {
			t.Fatal(err)
		}
		if err = track.WriteSample(media.Sample{Data: sample, Duration: time.Second / 24}); err != nil {
			t.Fatal(err)
		}
		if len(writer.packets) == 0 || len(writer.packets) > 128 {
			t.Fatalf("RTP parts exceed assembler: %d", len(writer.packets))
		}
		var assembler common.VP8Assembler
		var complete []byte
		// Out-of-order arrival and duplicate fragments must still preserve the
		// full encrypted maximum-size sample, including first/last markers.
		for i := len(writer.packets) - 1; i >= 0; i-- {
			if frame := assembler.Push(writer.packets[i]); frame != nil {
				if complete != nil {
					t.Fatal("duplicate completion")
				}
				complete = frame
			}
			if frame := assembler.Push(writer.packets[i]); frame != nil {
				t.Fatal("duplicate RTP emitted sample twice")
			}
		}
		if !bytes.Equal(complete, sample) {
			t.Fatalf("maximum burst failed reassembly: sample=%d assembled=%d parts=%d", len(sample), len(complete), len(writer.packets))
		}
		if result := dec.Decode(complete); !result.HasFrame || !bytes.Equal(result.Payload, payload) {
			t.Fatal("maximum-size sample failed AEAD/payload integrity")
		}
		t.Logf("keyframe=%v relay=%d encoded=%d RTP-parts=%d assembler-headroom=%d", keyframe, len(payload), len(sample), len(writer.packets), 128*1024-len(sample))
	}
}

func TestPCQA12MbpsFourPacketBundleStaysWithinCreditAndWireLimits(t *testing.T) {
	s := pcTestTunnel(t)
	s.pcHandle(pcHelloPacket())
	s.dynRateKbps.Store(12000)
	s.pcSendMu.Lock()
	s.pcFlow.srtt = time.Second
	s.pcSendMu.Unlock()
	for i := 0; i < 256; i++ {
		s.pcEnqueue(EncodeFrame(7, MsgData, make([]byte, common.VP8BufSize)))
	}
	ack, nack := []byte{pcAck, 0, 64}, []byte{pcNack, 0, 64}
	for i := uint32(1); i <= 64; i++ {
		var entry [8]byte
		binary.BigEndian.PutUint32(entry[:4], i)
		binary.BigEndian.PutUint32(entry[4:], 1)
		ack = append(ack, entry[:]...)
		nack = append(nack, entry[:]...)
	}
	packets := [][]byte{ack, pcHelloPacket(), nack}
	const credit = (12000*1000/8/24)*2 + 4096
	available := credit - (1 + 4*4)
	for _, p := range packets {
		available -= len(p)
	}
	data := s.pcNextData(available, time.Now())
	packets = append(packets, data)
	bundle := pcPack(packets)
	if len(bundle) > credit || len(data) == 0 {
		t.Fatalf("bundle overflow: %d credit %d", len(bundle), credit)
	}
	unpacked := pcUnpack(bundle)
	if len(unpacked) != 4 {
		t.Fatal("old bundle bounds reject new ceiling")
	}
	for i := range packets {
		if !bytes.Equal(unpacked[i], packets[i]) {
			t.Fatal("control/data bundle changed wire payload")
		}
	}
	if len(bundle) < credit-1200 {
		t.Fatal("fixture did not approach maximum credit")
	}
}

func TestPCQA12MbpsRespectsLegacy128AndNegotiated512Windows(t *testing.T) {
	for _, tc := range []struct {
		name   string
		hello  bool
		window int
	}{{"legacy", false, 128}, {"negotiated", true, 512}} {
		t.Run(tc.name, func(t *testing.T) {
			s := pcTestTunnel(t)
			s.pcOnAck([]byte{pcAck, 0, 0})
			if tc.hello {
				s.pcHandle(pcHelloPacket())
			}
			s.dynRateKbps.Store(12000)
			s.pcSendMu.Lock()
			s.pcFlow.srtt = time.Second
			s.pcSendMu.Unlock()
			now := time.Now()
			// Keep queue bounded while offering more than both advertised windows.
			for offered := 0; offered < tc.window+100; {
				for i := 0; i < 100 && offered < tc.window+100; i++ {
					s.pcEnqueue(EncodeFrame(7, MsgData, []byte("payload")))
					offered++
				}
				s.pcNextData(129096, now)
			}
			if int(s.pcSendSeq[7]) != tc.window {
				t.Fatalf("peer window exceeded/not reached: got %d want %d", s.pcSendSeq[7], tc.window)
			}
			s.pcEnqueue(EncodeFrame(99, MsgData, []byte("independent")))
			s.pcNextData(129096, now)
			if s.pcSendSeq[99] != 1 {
				t.Fatal("full old-peer window blocked independent connection")
			}
			s.pcOnAck(qaPCSignal(pcAck, 7, uint32(tc.window)))
			s.pcNextData(129096, now)
			if int(s.pcSendSeq[7]) <= tc.window {
				t.Fatal("ACK did not release bounded old-peer window")
			}
		})
	}
}
