package stripe

import (
	"bytes"
	"testing"
)

func TestReorderInOrder(t *testing.T) {
	r := NewReorder(1 << 20)
	out, err := r.Insert(0, []byte("hello"))
	if err != nil || !bytes.Equal(out, []byte("hello")) {
		t.Fatalf("first: out=%q err=%v", out, err)
	}
	out, err = r.Insert(5, []byte("world"))
	if err != nil || !bytes.Equal(out, []byte("world")) {
		t.Fatalf("second: out=%q err=%v", out, err)
	}
	if r.Delivered() != 10 {
		t.Fatalf("delivered=%d want 10", r.Delivered())
	}
}

func TestReorderGapThenFill(t *testing.T) {
	r := NewReorder(1 << 20)
	// Out-of-order: bytes [5,10) arrive before [0,5). Nothing deliverable yet.
	if out, _ := r.Insert(5, []byte("world")); len(out) != 0 {
		t.Fatalf("future seg should not deliver, got %q", out)
	}
	if r.Delivered() != 0 {
		t.Fatalf("delivered should still be 0, got %d", r.Delivered())
	}
	// The gap arrives -> both pieces flush in order.
	out, _ := r.Insert(0, []byte("hello"))
	if !bytes.Equal(out, []byte("helloworld")) {
		t.Fatalf("expected coalesced 'helloworld', got %q", out)
	}
	if r.Delivered() != 10 {
		t.Fatalf("delivered=%d want 10", r.Delivered())
	}
}

func TestReorderMultipleGaps(t *testing.T) {
	r := NewReorder(1 << 20)
	// Segments arrive 3rd, 2nd, then 1st (which unlocks all three).
	r.Insert(8, []byte("CCCC"))           // [8,12)
	r.Insert(4, []byte("BBBB"))           // [4,8)
	out, _ := r.Insert(0, []byte("AAAA")) // [0,4) unlocks everything
	if !bytes.Equal(out, []byte("AAAABBBBCCCC")) {
		t.Fatalf("expected 'AAAABBBBCCCC', got %q", out)
	}
	if r.Delivered() != 12 {
		t.Fatalf("delivered=%d want 12", r.Delivered())
	}
}

func TestReorderDuplicateAndStale(t *testing.T) {
	r := NewReorder(1 << 20)
	r.Insert(0, []byte("hello")) // delivered now 5
	// Fully stale (entirely below delivered) -> ignored, nothing delivered.
	if out, _ := r.Insert(0, []byte("hello")); len(out) != 0 {
		t.Fatalf("stale dup should deliver nothing, got %q", out)
	}
	// Straddling: [3,8) -> first 2 bytes stale, "rld"... actually trim to [5,8).
	out, _ := r.Insert(3, []byte("XXwld"))
	if !bytes.Equal(out, []byte("wld")) {
		t.Fatalf("straddle should deliver trimmed 'wld', got %q", out)
	}
	if r.Delivered() != 8 {
		t.Fatalf("delivered=%d want 8", r.Delivered())
	}
}

func TestReorderDuplicateFutureSeg(t *testing.T) {
	r := NewReorder(1 << 20)
	r.Insert(10, []byte("zzzz")) // future gap
	// Same start offset again -> ignored, buffered unchanged.
	r.Insert(10, []byte("zzzz"))
	if r.buffered != 4 {
		t.Fatalf("buffered=%d want 4 (dup future seg must not double-count)", r.buffered)
	}
}

func TestReorderOverflow(t *testing.T) {
	r := NewReorder(8) // tiny cap
	// First future seg of 8 bytes fits exactly.
	if _, err := r.Insert(100, bytes.Repeat([]byte("a"), 8)); err != nil {
		t.Fatalf("8 bytes into cap 8 should fit, err=%v", err)
	}
	// One more future byte overflows.
	if _, err := r.Insert(200, []byte("b")); err != ErrReorderOverflow {
		t.Fatalf("expected ErrReorderOverflow, got %v", err)
	}
}
