package tunnel

import (
	"encoding/binary"
	"math"
	"os"
	"strconv"
	"time"

	"whitelist-bypass/relay/common"
)

// A loss report may arrive several feedback intervals after the original send.
// Give the previous rate reduction time to reach the receiver before reducing
// again. This bounds reaction frequency, not retransmission or reliability.
type pcRecoveryRate struct {
	ewma                            float64
	start, deadline                 time.Time
	sent, acked, nacked             uint64
	reason                          string
	lastSent, lastAcked, lastNacked uint64
	lastSeconds                     float64
}

func (c *pcRecoveryRate) step(now time.Time, cur, ceiling int64, sent, nacked, acked uint64, rto time.Duration) int64 {
	if cur <= 0 {
		cur = 1000
	}
	cur = min(ceiling, max(min(int64(200), ceiling), cur))
	if c.start.IsZero() {
		period := min(5*time.Second, max(2*time.Second, 2*rto))
		c.start = now.Add(-time.Second) // caller supplies one second of observations
		c.deadline = c.start.Add(period)
	}
	c.sent += sent
	c.acked += acked
	c.nacked += nacked
	c.reason = "collect"
	if now.Before(c.deadline) {
		return cur
	}
	volume := max(c.sent, c.acked, c.nacked)
	loss := 0.0
	if volume > 0 {
		loss = float64(c.nacked) / float64(volume)
	}
	if volume >= 8 {
		c.ewma = .5*c.ewma + .5*loss
	}
	c.reason = "hold"
	if c.sent >= 8 {
		delivery := float64(c.acked) / float64(c.sent)
		switch {
		case loss > .08 && c.ewma > .08 && delivery < .90:
			cur = cur * 3 / 4
			c.reason = "delivery-cut"
		case c.acked >= 8 && delivery >= .95 && loss < .25:
			// Confirmed repair permits a bounded probe. ACKs without fresh
			// sends never grow the target, including late ACKs after an idle.
			if os.Getenv("WLB_PC_RATE_MODE") == "probe" && c.sent >= 32 && c.acked >= 32 && loss < .02 {
				cur += max(int64(300), min(cur/2, int64(2000)))
			} else {
				cur += int64(min(5.0, now.Sub(c.start).Seconds()) * 150)
			}
			c.reason = "delivery-grow"
		}
	}
	c.lastSent, c.lastAcked, c.lastNacked = c.sent, c.acked, c.nacked
	c.lastSeconds = now.Sub(c.start).Seconds()
	c.start = time.Time{}
	c.sent = 0
	c.acked = 0
	c.nacked = 0
	return min(ceiling, max(min(int64(200), ceiling), cur))
}

// Larger socket reads reduce per-unit ACK/window overhead. Enable only with
// the cohort controller: the legacy per-second ACK threshold cannot recover
// from its 200 kbps floor with 4 KiB units. The wire format is unchanged.
func PCRelayReadBuffer() int {
	if os.Getenv("WLB_PC_CHUNK") == "4096" && os.Getenv("WLB_PC_RATE_MODE") == "probe" {
		return 4096
	}
	return common.VP8BufSize
}

// Bounded experimental cadence; payload format and total bitrate stay fixed.
func pcCarrierFPS() int {
	fps, err := strconv.Atoi(os.Getenv("WLB_PC_FPS"))
	if err != nil || fps < 24 || fps > 120 {
		return 24
	}
	return fps
}

// A delayed NACK can describe an older interval. Bound its ratio, and only
// react to fresh losses with enough samples; repeated requests for the same
// gap must neither collapse the rate nor prevent ACK-confirmed recovery.
func pcRateStep(cur, max int64, sent, nacked, acked uint64, previousEWMA float64) (int64, float64) {
	if math.IsNaN(previousEWMA) || previousEWMA < 0 {
		previousEWMA = 0
	}
	if previousEWMA > 1 {
		previousEWMA = 1
	}
	volume := sent
	if nacked > volume {
		volume = nacked
	}
	ewma := previousEWMA
	if volume >= 8 || acked >= 8 {
		loss := float64(0)
		if volume > 0 {
			loss = float64(nacked) / float64(volume)
		}
		ewma = .5*previousEWMA + .5*loss
	}
	if cur <= 0 {
		cur = 1000
	}
	switch {
	case nacked > 0 && volume >= 8 && ewma > .08:
		cur = cur * 3 / 4
	case nacked == 0 && acked >= 8:
		cur += 150
	}
	floor := int64(200)
	if max < floor {
		floor = max
	}
	if cur < floor {
		cur = floor
	}
	if cur > max {
		cur = max
	}
	return cur, ewma
}

func (t *VP8DataTunnel) pcObserveRTTLocked(sample time.Duration) {
	if sample < time.Millisecond || sample > 30*time.Second {
		return
	}
	f := t.pcFlow
	if f.srtt == 0 {
		f.srtt = sample
		f.rttvar = sample / 2
		return
	}
	d := f.srtt - sample
	if d < 0 {
		d = -d
	}
	f.rttvar = (3*f.rttvar + d) / 4
	f.srtt = (7*f.srtt + sample) / 8
}

func (t *VP8DataTunnel) pcRTOLocked() time.Duration {
	f := t.pcFlow
	if f.srtt == 0 {
		return pcRetryAfter
	}
	rto := f.srtt + 4*f.rttvar
	if rto < 250*time.Millisecond {
		rto = 250 * time.Millisecond
	}
	if rto > 5*time.Second {
		rto = 5 * time.Second
	}
	return rto
}

func (t *VP8DataTunnel) pcWindowLocked() int {
	f := t.pcFlow
	limit := f.peerWindow
	if limit < pcWindow {
		limit = pcWindow
	}
	if limit > pcMaxWindow {
		limit = pcMaxWindow
	}
	rate := t.dynRateKbps.Load()
	if rate <= 0 {
		rate = 1000
	}
	// 1.5 bandwidth-delay products plus one small ACK burst. Large enough to
	// keep transmitting while ACKs travel, bounded by the peer's advertised
	// receive capacity. Old 2.0.3 peers MUST stay at 128 to avoid overflowing.
	window := int(float64(rate)*1000/8*f.srtt.Seconds()*1.5/float64(PCRelayReadBuffer()+13)) + 32
	if window < pcWindow {
		window = pcWindow
	}
	if window > limit {
		window = limit
	}
	return window
}

func pcHelloPacket() []byte {
	p := []byte{pcHello, 1, 0, 0}
	binary.BigEndian.PutUint16(p[2:], pcMaxWindow)
	return p
}

func pcPack(packets [][]byte) []byte {
	if len(packets) == 0 {
		return nil
	}
	if len(packets) == 1 {
		return packets[0]
	}
	p := []byte{pcBundle}
	for _, b := range packets {
		p = binary.BigEndian.AppendUint32(p, uint32(len(b)))
		p = append(p, b...)
	}
	return p
}

func pcUnpack(p []byte) [][]byte {
	if len(p) == 0 || p[0] != pcBundle {
		return nil
	}
	p = p[1:]
	var packets [][]byte
	for len(p) > 0 {
		if len(p) < 5 || len(packets) >= 4 {
			return nil
		}
		n := int(binary.BigEndian.Uint32(p))
		p = p[4:]
		if n < 1 || n > len(p) || n > 128*1024 {
			return nil
		}
		// Validate the whole envelope first; nesting is forbidden.
		switch p[0] {
		case pcData, pcNack, pcAck, pcHello:
		default:
			return nil
		}
		packets = append(packets, p[:n])
		p = p[n:]
	}
	return packets
}

// Sample durations must sum to ONE wall-clock tick even with legacy peers.
// 2.0.3 gave ACK and data a full tick each, doubling the RTP media clock during
// uploads. Updated peers bundle them into one real VP8 sample at the same 24fps.
func pcSampleDurations(count int, interval time.Duration) []time.Duration {
	if count == 0 {
		return nil
	}
	d := make([]time.Duration, count)
	for i := range d {
		d[i] = interval / time.Duration(count)
	}
	d[count-1] += interval - d[0]*time.Duration(count)
	return d
}
