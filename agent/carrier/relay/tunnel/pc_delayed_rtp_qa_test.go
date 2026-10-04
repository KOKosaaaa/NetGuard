package tunnel

import (
	"bytes"
	"container/heap"
	"crypto/sha256"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/common"
)

type qaDelayedPacket struct {
	at     time.Time
	packet *rtp.Packet
}
type qaPacketHeap []qaDelayedPacket

func (h qaPacketHeap) Len() int           { return len(h) }
func (h qaPacketHeap) Less(i, j int) bool { return h[i].at.Before(h[j].at) }
func (h qaPacketHeap) Swap(i, j int)      { h[i], h[j] = h[j], h[i] }
func (h *qaPacketHeap) Push(v any)        { *h = append(*h, v.(qaDelayedPacket)) }
func (h *qaPacketHeap) Pop() any          { old := *h; n := len(old); v := old[n-1]; *h = old[:n-1]; return v }

type qaDelayedRTP struct {
	peer                   *VP8DataTunnel
	input                  chan qaDelayedPacket
	stop                   chan struct{}
	done                   chan struct{}
	packets, late, dropped atomic.Int64
}

func (l *qaDelayedRTP) Write(b []byte) (int, error) {
	p := &rtp.Packet{}
	if err := p.Unmarshal(b); err != nil {
		return 0, err
	}
	return l.WriteRTP(&p.Header, p.Payload)
}
func (l *qaDelayedRTP) WriteRTP(h *rtp.Header, b []byte) (int, error) {
	n := l.packets.Add(1)
	// Fixed-rate independent faults: one-way propagation100ms, isolated extra
	// jitter300ms and sparse permanent RTP loss. No knowledge of app seq/rate.
	if n%509 == 0 {
		l.dropped.Add(1)
		return len(b), nil
	}
	delay := 100 * time.Millisecond
	if n%17 == 0 {
		delay += 300 * time.Millisecond
		l.late.Add(1)
	}
	p := (&rtp.Packet{Header: *h, Payload: b}).Clone()
	select {
	case l.input <- qaDelayedPacket{time.Now().Add(delay), p}:
	case <-l.stop:
	}
	return len(b), nil
}
func (l *qaDelayedRTP) run() {
	defer close(l.done)
	var queue qaPacketHeap
	var assembler common.VP8Assembler
	tick := time.NewTicker(time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-l.stop:
			return
		case p := <-l.input:
			heap.Push(&queue, p)
		case now := <-tick.C:
			for len(queue) > 0 && !queue[0].at.After(now) {
				p := heap.Pop(&queue).(qaDelayedPacket)
				if sample := assembler.Push(p.packet); sample != nil {
					l.peer.HandleFrame(sample)
				}
			}
		}
	}
}

type qaDelayedContext struct {
	pcTrackContext
	link *qaDelayedRTP
}

func (c qaDelayedContext) WriteStream() webrtc.TrackLocalWriter { return c.link }

func TestPCQAActualRTPDelayAndLossPreserveFileAt24And96FPS(t *testing.T) {
	if !carrierMode || !carrierPCARQ {
		t.Skip("requires WB PCARQ environment")
	}
	for _, candidate := range []struct {
		fps  int
		mode string
	}{{24, "legacy"}, {96, "legacy"}, {96, "recovery"}} {
		fps := candidate.fps
		t.Run(strconv.Itoa(fps)+"-"+candidate.mode, func(t *testing.T) {
			t.Setenv("WLB_PC_FPS", strconv.Itoa(fps))
			t.Setenv("WLB_PC_RATE_MODE", candidate.mode)
			t.Setenv("WLB_CARRIER_KBPS", "12000")
			var peers [2]*VP8DataTunnel
			var links [2]*qaDelayedRTP
			for i := range peers {
				track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "qa-delay", "qa-stream")
				if err != nil {
					t.Fatal(err)
				}
				links[i] = &qaDelayedRTP{input: make(chan qaDelayedPacket, 4096), stop: make(chan struct{}), done: make(chan struct{})}
				if _, err = track.Bind(qaDelayedContext{link: links[i]}); err != nil {
					t.Fatal(err)
				}
				obf, _ := NewTunnelObfuscator([]byte("qa-delay-file"))
				peers[i] = NewVP8DataTunnel(track, obf, pcNoop)
			}
			links[0].peer, links[1].peer = peers[1], peers[0]
			want := make([]byte, 1024*1024)
			for i := range want {
				want[i] = byte(i*37 + i/1126)
			}
			deliveries := make(chan []byte, 2048)
			peers[1].OnData = func(frame []byte) {
				DecodeFrames(frame, func(id uint32, kind byte, p []byte) {
					if id == 7 && kind == MsgData {
						deliveries <- append([]byte(nil), p...)
					}
				})
			}
			for i := range peers {
				go links[i].run()
				peers[i].Start(fps, 1)
			}
			defer func() {
				for i := range peers {
					peers[i].Stop()
					close(links[i].stop)
				}
				for i := range links {
					<-links[i].done
				}
			}()
			producerDone := make(chan struct{})
			go func() {
				defer close(producerDone)
				for off := 0; off < len(want); {
					end := off + common.VP8BufSize
					if end > len(want) {
						end = len(want)
					}
					peers[0].SendData(EncodeFrame(7, MsgData, want[off:end]))
					off = end
				}
			}()
			start := time.Now()
			deadline := time.NewTimer(45 * time.Second)
			defer deadline.Stop()
			var got bytes.Buffer
			for got.Len() < len(want) {
				select {
				case p := <-deliveries:
					got.Write(p)
				case <-deadline.C:
					t.Fatalf("file stalled: %d/%d after %s", got.Len(), len(want), time.Since(start))
				}
			}
			<-producerDone
			if !bytes.Equal(got.Bytes(), want) {
				t.Fatal("RTP delay/loss changed ordered application bytes")
			}
			if links[0].late.Load() == 0 || links[0].dropped.Load() == 0 {
				t.Fatal("fixture did not exercise both late and lost RTP")
			}
			peers[0].pcSendMu.Lock()
			debt := len(peers[0].pcSendBuf)
			window := peers[0].pcWindowLocked()
			peers[0].pcSendMu.Unlock()
			if debt > window {
				t.Fatal("delivery faults exceeded negotiated per-connection window")
			}
			t.Logf("requestedFPS=%d bytes=%d SHA=%x elapsed=%s late=%d lost=%d debt=%d window=%d finalRate=%d", fps, got.Len(), sha256.Sum256(got.Bytes()), time.Since(start), links[0].late.Load(), links[0].dropped.Load(), debt, window, peers[0].dynRateKbps.Load())
			// No performance assertion: this artificial link proves integrity under
			// actual wall-clock faults, not real WB throughput or identical traces.
		})
	}
}
