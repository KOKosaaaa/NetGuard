package stripe

import "errors"

// ErrReorderOverflow means a peer sent data far enough ahead of the
// contiguous delivery point that the held bytes would exceed the buffer cap.
// With correct send-window accounting this never fires; it is a hard backstop
// against a buggy or hostile peer pinning our memory. The caller resets the
// flow.
var ErrReorderOverflow = errors.New("stripe: reorder buffer overflow")

// Reorder reassembles one direction of one flow. The sender labels every
// Data frame with the byte OFFSET of its first byte in the stream, sends each
// byte exactly once over exactly one (reliable, ordered) pipe, and never
// retransmits. So the only disorder we ever see is whole, DISJOINT segments
// arriving across pipes out of order — never partial overlaps. That lets the
// reassembly stay a simple "do I have the segment that starts at the next
// undelivered offset?" loop instead of a TCP-style overlap tree.
//
// Reorder is NOT safe for concurrent use; the owning flow serializes access.
type Reorder struct {
	delivered uint64            // next contiguous offset not yet handed out
	segs      map[uint64][]byte // start offset -> bytes, only future gaps
	buffered  int               // sum of len(segs values), the held bytes
	cap       int               // max held bytes before overflow
}

// NewReorder makes a buffer that holds at most cap out-of-order bytes. cap
// should match the send window so a well-behaved peer never overflows.
func NewReorder(cap int) *Reorder {
	return &Reorder{segs: make(map[uint64][]byte), cap: cap}
}

// Delivered returns the cumulative number of in-order bytes handed out so
// far, which is also the offset of the next byte we still need. This is the
// value to put in an Ack frame.
func (r *Reorder) Delivered() uint64 { return r.delivered }

// Insert takes a Data frame's offset and payload and returns the bytes that
// are now contiguously deliverable, in order (possibly empty if this filled a
// future gap, possibly spanning several previously-buffered segments if this
// was the missing piece). The returned slice is freshly allocated and owned
// by the caller. data may be retained by the buffer, so the caller must not
// mutate it after the call (the frame reader hands us a fresh copy already).
func (r *Reorder) Insert(offset uint64, data []byte) ([]byte, error) {
	if len(data) == 0 {
		return nil, nil
	}
	end := offset + uint64(len(data))

	// Entirely in the past (duplicate/stale) — drop. Shouldn't happen on a
	// no-retransmit substrate, but stay defensive.
	if end <= r.delivered {
		return nil, nil
	}
	// Straddles the delivery point: trim the already-delivered prefix.
	if offset < r.delivered {
		cut := r.delivered - offset
		data = data[cut:]
		offset = r.delivered
	}

	if offset == r.delivered {
		// Deliverable now, and may chain into buffered future segments.
		out := append([]byte(nil), data...)
		r.delivered = end
		for {
			seg, ok := r.segs[r.delivered]
			if !ok {
				break
			}
			delete(r.segs, r.delivered)
			r.buffered -= len(seg)
			out = append(out, seg...)
			r.delivered += uint64(len(seg))
		}
		return out, nil
	}

	// A future segment: buffer it behind a gap. Ignore an exact duplicate
	// start offset (no-retransmit substrate means same bytes; keep the first).
	if _, exists := r.segs[offset]; exists {
		return nil, nil
	}
	if r.buffered+len(data) > r.cap {
		return nil, ErrReorderOverflow
	}
	seg := append([]byte(nil), data...)
	r.segs[offset] = seg
	r.buffered += len(seg)
	return nil, nil
}
