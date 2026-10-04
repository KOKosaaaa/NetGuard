package tunnel

import (
	"encoding/binary"
	"testing"
	"time"
)

// Virtual-clock benchmark: a continuously backlogged upload, 900 ms ACK RTT,
// no packet loss and the real sender scheduler. This isolates the send-window
// ceiling from Telegram's coarse, part-by-part progress display.
func pcSpeedRun(t *testing.T, upgraded bool) float64 {
	s := pcTestTunnel(t)
	s.dynRateKbps.Store(2800)
	s.pcOnAck([]byte{pcAck, 0, 0})
	if upgraded {
		s.pcHandle(pcHelloPacket())
	}
	const units = 4000
	const interval = time.Second / 24
	const rtt = 900 * time.Millisecond
	type ack struct {
		at  time.Time
		seq uint32
	}
	var flight []ack
	start := time.Now()
	now := start
	queued := 0
	credit := 0
	delivered := uint32(0)
	idle, maxIdle := time.Duration(0), time.Duration(0)
	for ticks := 0; ticks < 24*90 && delivered < units; ticks++ {
		now = now.Add(interval)
		for len(flight) > 0 && !flight[0].at.After(now) {
			a := flight[0]
			flight = flight[1:]
			p := make([]byte, 11)
			p[0] = pcAck
			binary.BigEndian.PutUint16(p[1:], 1)
			binary.BigEndian.PutUint32(p[3:], 7)
			binary.BigEndian.PutUint32(p[7:], a.seq)
			s.pcOnAckAt(p, now)
			if a.seq > delivered {
				delivered = a.seq
			}
		}
		for queued < units {
			s.pcSendMu.Lock()
			full := len(s.pcFlow.pending[7]) >= 256
			s.pcSendMu.Unlock()
			if full {
				break
			}
			s.pcEnqueue(EncodeFrame(7, MsgData, make([]byte, 1126)))
			queued++
		}
		before := s.pcSendSeq[7]
		credit += 2800 * 1000 / 8 / 24
		if credit > 2*(2800*1000/8/24)+4096 {
			credit = 2*(2800*1000/8/24) + 4096
		}
		p := s.pcNextData(credit, now)
		credit -= len(p)
		if len(p) > 0 {
			flight = append(flight, ack{now.Add(rtt), s.pcSendSeq[7]})
		}
		if s.pcSendSeq[7] == before && s.pcSendSeq[7] < units && now.Sub(start) > 2*time.Second {
			idle += interval
			if idle > maxIdle {
				maxIdle = idle
			}
		} else {
			idle = 0
		}
	}
	if delivered != units {
		t.Fatalf("upload did not finish: %d", delivered)
	}
	elapsed := now.Sub(start).Seconds()
	mbps := float64(units*1126*8) / elapsed / 1e6
	t.Logf("upgraded=%v RTT=%v: %.3f Mbit/s, 1 MiB per %.2fs, longest new-data pause=%v", upgraded, rtt, mbps, 8.388608/mbps, maxIdle)
	return mbps
}

func TestPCUploadSpeedWithSlowACKs(t *testing.T) {
	old := pcSpeedRun(t, false)
	improved := pcSpeedRun(t, true)
	if improved < 2.4 || improved < old*1.8 {
		t.Fatalf("slow ACK regression: legacy %.3f new %.3f Mbit/s", old, improved)
	}
}

func TestPCRetransmitTimerTracksRTTAndIgnoresResentSamples(t *testing.T) {
	s := pcTestTunnel(t)
	now := time.Now()
	s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("data"))})
	k := pcKey(7, 1)
	s.pcFlow.sentAt[k] = now
	p := make([]byte, 11)
	p[0] = pcAck
	binary.BigEndian.PutUint16(p[1:], 1)
	binary.BigEndian.PutUint32(p[3:], 7)
	binary.BigEndian.PutUint32(p[7:], 1)
	s.pcOnAckAt(p, now.Add(1200*time.Millisecond))
	if s.pcRTOLocked() < 1200*time.Millisecond {
		t.Fatal("retry before measured round trip")
	}
	before := s.pcFlow.srtt
	s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("second"))})
	s.pcFlow.resent[pcKey(7, 2)] = true
	binary.BigEndian.PutUint32(p[7:], 2)
	s.pcOnAckAt(p, now.Add(10*time.Second))
	if s.pcFlow.srtt != before {
		t.Fatal("ambiguous retransmission changed RTT")
	}
}

func TestPCBundleAndLegacyWindowCompatibility(t *testing.T) {
	s := pcTestTunnel(t)
	s.dynRateKbps.Store(2800)
	s.pcFlow.srtt = time.Second
	if s.pcWindowLocked() != pcWindow {
		t.Fatal("old peer window exceeded")
	}
	s.pcHandle(pcHelloPacket())
	if s.pcWindowLocked() <= pcWindow || s.pcWindowLocked() > pcMaxWindow {
		t.Fatal("new peer did not negotiate window")
	}
	packets := [][]byte{pcHelloPacket(), {pcAck, 0, 0}, s.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, []byte("file"))})}
	packed := pcPack(packets)
	decoded := pcUnpack(packed)
	if len(decoded) != 3 {
		t.Fatal("bundle lost control or data")
	}
	r := pcTestTunnel(t)
	rec := pcCapture(r)
	r.pcHandle(packed)
	got := rec.wait(t, 1)
	if got[0].data != "file" || !r.pcFlow.peerBundle {
		t.Fatal("bundle did not dispatch all packets")
	}
	if pcUnpack(pcPack([][]byte{{pcBundle}, {pcAck, 0, 0}})) != nil {
		t.Fatal("accepted nested bundle")
	}
	if pcUnpack(packed[:len(packed)-1]) != nil {
		t.Fatal("accepted truncated bundle")
	}
	for _, n := range []int{1, 2, 3, 4} {
		d := pcSampleDurations(n, time.Second/24)
		var total time.Duration
		for _, v := range d {
			if v <= 0 {
				t.Fatal("duplicate RTP timestamp")
			}
			total += v
		}
		if total != time.Second/24 {
			t.Fatal("RTP clock advances faster than wall clock")
		}
	}
}
