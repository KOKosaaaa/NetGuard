package tunnel

import (
	"bytes"
	"math/rand"
	"testing"
)

// TestBoolCoderRoundTrip verifies the boolEncoder/boolDecoder pair is an exact
// inverse for arbitrary (prob,bit) sequences — the arithmetic-coder foundation.
func TestBoolCoderRoundTrip(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	for iter := 0; iter < 50; iter++ {
		n := 1 + rng.Intn(4000)
		probs := make([]int, n)
		bits := make([]int, n)
		for i := 0; i < n; i++ {
			probs[i] = 1 + rng.Intn(255) // VP8 probs are 1..255
			bits[i] = rng.Intn(2)
		}
		e := newBoolEncoder()
		for i := 0; i < n; i++ {
			e.putBit(probs[i], bits[i])
		}
		buf := e.finish()
		d := newBoolDecoder(buf)
		for i := 0; i < n; i++ {
			if got := d.getBit(probs[i]); got != bits[i] {
				t.Fatalf("iter %d bit %d: got %d want %d", iter, i, got, bits[i])
			}
		}
	}
}

// TestReadWriteTreeRoundTrip checks tree coding through the coeff tree.
func TestReadWriteTreeRoundTrip(t *testing.T) {
	rng := rand.New(rand.NewSource(2))
	probs := defaultCoefProbs[0][1][2][:]
	e := newBoolEncoder()
	want := make([]int, 2000)
	for i := range want {
		want[i] = 1 + rng.Intn(4) // DCT_1..DCT_4
		e.writeTree(coeffTree, probs, want[i])
	}
	d := newBoolDecoder(e.finish())
	for i, w := range want {
		if got := d.readTree(coeffTree, probs); got != w {
			t.Fatalf("token %d: got %d want %d", i, got, w)
		}
	}
}

// TestKeyframeDataRoundTrip is the end-to-end goal: encode payload into a valid
// VP8 keyframe, decode it back, expect the exact bytes.
func TestKeyframeDataRoundTrip(t *testing.T) {
	rng := rand.New(rand.NewSource(3))
	sizes := []int{0, 1, 7, 100, 1000, 5000, 20000}
	for _, sz := range sizes {
		payload := make([]byte, sz)
		rng.Read(payload)
		frame, err := AssembleKeyframeData(320, 180, payload)
		if err != nil {
			t.Fatalf("size %d: assemble: %v", sz, err)
		}
		got, err := DecodeKeyframeData(frame)
		if err != nil {
			t.Fatalf("size %d: decode: %v", sz, err)
		}
		if !bytes.Equal(got, payload) {
			t.Fatalf("size %d: payload mismatch (got %d bytes)", sz, len(got))
		}
	}
}

// TestFrameCapacity sanity-checks the capacity math and the too-large guard.
func TestFrameCapacity(t *testing.T) {
	capBytes := (frameCapacityBits(320, 180) - 32) / 8
	if capBytes < 30000 {
		t.Fatalf("unexpected capacity %d", capBytes)
	}
	if _, err := AssembleKeyframeData(320, 180, make([]byte, capBytes+1000)); err == nil {
		t.Fatalf("expected too-large error")
	}
}
