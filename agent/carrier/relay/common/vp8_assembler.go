package common

import (
	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
)

// VP8Assembler reconstructs samples from a single remote RTP track. RTP order
// is not arrival order: late/RTX packets must not invalidate an unrelated
// frame. Only complete samples are emitted; the carrier ARQ recovers losses.
// Keep enough timestamps for delayed RTP repair at 120fps, including completed
// timestamps as duplicate tombstones. Payload storage stays bounded at 4MiB,
// independently of cadence; each sample is still limited to 128KiB/128 parts.
// One instance belongs to one reader.
const vp8AssemblySlots = 256
const vp8AssemblyBytes = 4 * 1024 * 1024

type VP8Assembler struct {
	frames        map[uint32]*vp8Frame
	order         []uint32
	bufferedBytes int
}

type vp8Frame struct {
	parts                     map[uint16][]byte
	first, last               uint16
	haveFirst, haveLast, done bool
	size                      int
}

func (a *VP8Assembler) release(f *vp8Frame) {
	a.bufferedBytes -= f.size
	f.size, f.parts = 0, nil
}

func (a *VP8Assembler) evictOldest() {
	old := a.order[0]
	a.order = a.order[1:]
	a.release(a.frames[old])
	delete(a.frames, old)
}

func (a *VP8Assembler) Push(p *rtp.Packet) []byte {
	var v codecs.VP8Packet
	body, err := v.Unmarshal(p.Payload)
	if err != nil {
		return nil
	}
	if a.frames == nil {
		a.frames = make(map[uint32]*vp8Frame)
	}
	f := a.frames[p.Timestamp]
	if f == nil {
		if len(a.order) == vp8AssemblySlots {
			a.evictOldest()
		}
		f = &vp8Frame{parts: make(map[uint16][]byte)}
		a.frames[p.Timestamp] = f
		a.order = append(a.order, p.Timestamp)
	}
	if f.done {
		return nil
	}
	if _, duplicate := f.parts[p.SequenceNumber]; duplicate {
		return nil
	}
	if len(f.parts) >= 128 || f.size+len(body) > 128*1024 {
		a.release(f)
		f.done = true
		return nil
	}
	for a.bufferedBytes+len(body) > vp8AssemblyBytes && len(a.order) > 0 {
		a.evictOldest()
	}
	if a.frames[p.Timestamp] != f {
		return nil // pressure evicted this old sample; ARQ will repair its data
	}
	f.parts[p.SequenceNumber] = append([]byte(nil), body...)
	f.size += len(body)
	a.bufferedBytes += len(body)
	if v.S == 1 && v.PID == 0 {
		f.first, f.haveFirst = p.SequenceNumber, true
	}
	if p.Marker {
		f.last, f.haveLast = p.SequenceNumber, true
	}
	if !f.haveFirst || !f.haveLast {
		return nil
	}
	count := int(uint16(f.last-f.first)) + 1 // sequence wrap is normal
	if count > 128 || len(f.parts) < count {
		return nil
	}
	for i := 0; i < count; i++ {
		if _, ok := f.parts[f.first+uint16(i)]; !ok {
			return nil
		}
	}
	out := make([]byte, 0, f.size)
	for i := 0; i < count; i++ {
		out = append(out, f.parts[f.first+uint16(i)]...)
	}
	a.release(f)
	f.done = true
	return out
}
