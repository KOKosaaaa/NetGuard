package stripe

import (
	"bytes"
	"io"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	cases := []Frame{
		{Type: FrameHello, FlowID: 0, Seq: 0, Payload: append(bytes.Repeat([]byte{0xAB}, 16), 3)},
		{Type: FrameOpen, FlowID: 42, Seq: 0, Payload: []byte("example.com:443")},
		{Type: FrameData, FlowID: 42, Seq: 1 << 40, Payload: bytes.Repeat([]byte("x"), 16*1024)},
		{Type: FrameFin, FlowID: 42, Seq: 99999, Payload: nil},
		{Type: FrameRst, FlowID: 7, Seq: 0, Payload: nil},
		{Type: FrameAck, FlowID: 42, Seq: 12345, Payload: nil},
	}

	var buf bytes.Buffer
	for _, f := range cases {
		buf.Write(f.Encode(nil))
	}

	for i, want := range cases {
		got, err := ReadFrame(&buf)
		if err != nil {
			t.Fatalf("case %d: ReadFrame: %v", i, err)
		}
		if got.Type != want.Type || got.FlowID != want.FlowID || got.Seq != want.Seq {
			t.Fatalf("case %d: header mismatch: got %+v want type=%d flow=%d seq=%d",
				i, got, want.Type, want.FlowID, want.Seq)
		}
		if !bytes.Equal(got.Payload, want.Payload) {
			t.Fatalf("case %d: payload mismatch: got %d bytes want %d bytes",
				i, len(got.Payload), len(want.Payload))
		}
	}

	if _, err := ReadFrame(&buf); err != io.EOF {
		t.Fatalf("expected io.EOF at end of stream, got %v", err)
	}
}

func TestReadFrameTruncatedHeader(t *testing.T) {
	// One byte short of a full header -> ErrUnexpectedEOF, not EOF.
	r := bytes.NewReader(make([]byte, HeaderSize-1))
	if _, err := ReadFrame(r); err != io.ErrUnexpectedEOF {
		t.Fatalf("expected ErrUnexpectedEOF, got %v", err)
	}
}

func TestReadFrameTruncatedPayload(t *testing.T) {
	// A header promising 100 bytes but only 10 present.
	f := Frame{Type: FrameData, FlowID: 1, Seq: 0, Payload: bytes.Repeat([]byte("z"), 100)}
	wire := f.Encode(nil)
	r := bytes.NewReader(wire[:HeaderSize+10])
	if _, err := ReadFrame(r); err != io.ErrUnexpectedEOF {
		t.Fatalf("expected ErrUnexpectedEOF, got %v", err)
	}
}

func TestReadFramePayloadTooLarge(t *testing.T) {
	var hdr [HeaderSize]byte
	hdr[0] = byte(FrameData)
	// Set len field to MaxPayload+1.
	binPut32(hdr[13:17], MaxPayload+1)
	r := bytes.NewReader(hdr[:])
	if _, err := ReadFrame(r); err != ErrPayloadTooLarge {
		t.Fatalf("expected ErrPayloadTooLarge, got %v", err)
	}
}

// binPut32 is a tiny local helper so the test doesn't import encoding/binary
// just for one call.
func binPut32(b []byte, v uint32) {
	b[0] = byte(v >> 24)
	b[1] = byte(v >> 16)
	b[2] = byte(v >> 8)
	b[3] = byte(v)
}
