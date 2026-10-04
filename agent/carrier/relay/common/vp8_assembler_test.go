package common

import (
	"bytes"
	"testing"

	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
)

func TestVP8ReorderedFileFrame(t *testing.T) {
	want := bytes.Repeat([]byte{19, 43, 71}, 6000)
	pz := rtp.NewPacketizer(1200, 96, 1, &codecs.VP8Payloader{}, rtp.NewFixedSequencer(65530), 90000)
	packets := pz.Packetize(want, 3750)
	// The SFU may reorder or retransmit RTP. No bytes were lost here.
	packets[1], packets[2] = packets[2], packets[1]
	var a VP8Assembler
	var got []byte
	for _, p := range packets {
		if b := a.Push(p); b != nil {
			got = b
		}
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("reordered complete file frame discarded: got %d, want %d", len(got), len(want))
	}
}

func TestVP8MissingFragmentDoesNotPoisonNextFrame(t *testing.T) {
	pz := rtp.NewPacketizer(1200, 96, 1, &codecs.VP8Payloader{}, rtp.NewFixedSequencer(10), 90000)
	want := bytes.Repeat([]byte{7}, 18000)
	old, next := pz.Packetize(want, 3750), pz.Packetize(want, 3750)
	var a VP8Assembler
	for i, p := range old {
		if i != 2 && a.Push(p) != nil {
			t.Fatal("emitted incomplete sample")
		}
	}
	// A late old fragment arrives during the next sample, then a duplicate.
	a.Push(next[0])
	if !bytes.Equal(a.Push(old[2]), want) {
		t.Fatal("late recovery failed")
	}
	if a.Push(old[2]) != nil {
		t.Fatal("duplicate sample emitted")
	}
	var got []byte
	for _, p := range next[1:] {
		if b := a.Push(p); b != nil {
			got = b
		}
	}
	if !bytes.Equal(got, want) {
		t.Fatal("late RTP destroyed next sample")
	}
}

func TestVP8AssemblyBounded(t *testing.T) {
	var a VP8Assembler
	for ts := uint32(0); ts < 600; ts++ {
		for seq := uint16(0); seq < 200; seq++ {
			p := &rtp.Packet{Header: rtp.Header{Timestamp: ts, SequenceNumber: seq}, Payload: append([]byte{0x10}, make([]byte, 1190)...)}
			if a.Push(p) != nil {
				t.Fatal("no marker but sample emitted")
			}
		}
	}
	if len(a.frames) > vp8AssemblySlots || a.bufferedBytes > vp8AssemblyBytes || a.bufferedBytes < 0 {
		t.Fatal("unbounded timestamps")
	}
	for _, f := range a.frames {
		if f.size > 128*1024 || len(f.parts) > 128 {
			t.Fatal("unbounded sample")
		}
	}
}

// At 100fps these 80 intervening samples represent 800ms of delayed repair.
// A 32-timestamp cache lost the original frame even though RTX delivered every
// byte; faster sample cadence amplified this into application-level ARQ loss.
func TestVP8LateRepairSurvivesHighCadenceCompletedSamples(t *testing.T) {
	pz := rtp.NewPacketizer(1200, 96, 1, &codecs.VP8Payloader{}, rtp.NewFixedSequencer(65000), 90000)
	want := bytes.Repeat([]byte{71, 19, 11}, 6000)
	packets := pz.Packetize(want, 900)
	var a VP8Assembler
	for i, p := range packets {
		if i != 2 && a.Push(p) != nil {
			t.Fatal("incomplete original emitted")
		}
	}
	for n := 0; n < 80; n++ {
		for _, p := range pz.Packetize([]byte{1, 2, 3}, 900) {
			if !bytes.Equal(a.Push(p), []byte{1, 2, 3}) {
				t.Fatal("new frame blocked behind repair")
			}
		}
	}
	if got := a.Push(packets[2]); !bytes.Equal(got, want) {
		t.Fatalf("late complete frame lost: got %d bytes", len(got))
	}
	if a.Push(packets[2]) != nil {
		t.Fatal("duplicate original emitted")
	}
	if a.bufferedBytes != 0 {
		t.Fatalf("completed payload retained: %d", a.bufferedBytes)
	}
}

func TestVP8AggregateMemoryBoundUnderIncompleteFrameFlood(t *testing.T) {
	var a VP8Assembler
	for ts := uint32(0); ts < 600; ts++ {
		for seq := uint16(0); seq < 96; seq++ {
			p := &rtp.Packet{Header: rtp.Header{Timestamp: ts, SequenceNumber: seq}, Payload: append([]byte{0x10}, make([]byte, 1100)...)}
			a.Push(p)
			if a.bufferedBytes > vp8AssemblyBytes || a.bufferedBytes < 0 {
				t.Fatalf("payload bound: %d", a.bufferedBytes)
			}
		}
		actual := 0
		for _, f := range a.frames {
			for _, part := range f.parts {
				actual += len(part)
			}
		}
		if actual != a.bufferedBytes {
			t.Fatalf("accounting: retained=%d tracked=%d", actual, a.bufferedBytes)
		}
	}
	if len(a.frames) > vp8AssemblySlots {
		t.Fatal("timestamp bound")
	}
}
