// Package stripe implements the server side of NetGuard's Telemost
// "striping" multiplexer. It sits on the exit server and is reachable from
// the phone only through the Telemost rooms: each room is a transparent
// SOCKS5->WebRTC->SOCKS5 pipe whose far end (the headless-telemost-creator)
// dials 127.0.0.1:STRIPE_PORT, landing here.
//
// The phone opens N such pipes (one per room) and runs the matching client
// (Kotlin StripeMux). On top of those N reliable, ordered byte-pipes we run
// a small multiplexing protocol that lets a SINGLE application flow be split
// ("striped") across ALL rooms at once, then reassembled in order before we
// dial the real destination once. This is what turns "6 separate 1.25 Mbps
// lanes" into "one ~7.5 Mbps pipe" for a single heavy transfer.
//
// We deliberately do NOT touch librelay.so or the creator: the rooms stay
// dumb pipes, and this protocol is a pure additive layer with its own two
// endpoints (this server and the Kotlin client).
package stripe

import (
	"encoding/binary"
	"errors"
	"io"
)

// FrameType identifies the kind of a wire frame. Values are explicit so the
// Kotlin client can hard-code the same constants.
type FrameType uint8

const (
	// FrameHello is the first frame a pipe sends. It binds this physical
	// pipe (TCP connection) to a logical client session so the server can
	// group the N room-pipes that belong to the same phone. Payload =
	// 16-byte sessionID followed by 1-byte pipe index. flowID/seq unused.
	FrameHello FrameType = 1

	// FrameOpen is sent client->server only (this is a forward proxy, the
	// client always initiates). Payload = destination "host:port" (UTF-8).
	// seq unused. The server lazily creates flow state on the first frame
	// it sees for a flowID, so an Open arriving AFTER some Data (different
	// pipes, different latency) is fine — the buffered Data just waits for
	// the dial to complete.
	FrameOpen FrameType = 2

	// FrameData carries flow bytes. seq is the byte OFFSET of the first
	// payload byte within the sender's stream for this flow+direction
	// (TCP-style, not a chunk index) so variable-size chunks reassemble
	// cleanly via a reorder buffer keyed by offset. Direction is implicit:
	// whoever writes the frame is the sender of that direction's stream.
	FrameData FrameType = 3

	// FrameFin is a half-close. seq = finalSeq = the sender's total byte
	// count for this flow+direction. The receiver applies it (shutdown its
	// write half toward the real socket) only once it has delivered exactly
	// finalSeq bytes, so a Fin that races ahead of trailing Data is safe.
	FrameFin FrameType = 4

	// FrameRst is a hard close in either direction: tear the flow down now,
	// drop buffers, close both sockets. seq unused.
	FrameRst FrameType = 5

	// FrameAck advances the peer's send window. seq = cumDelivered = how
	// many bytes the ACK sender has handed to the real socket for this
	// flow+direction. ACKs are cumulative, so a lost or reordered ACK is
	// harmless: the next one supersedes it (receiver takes the max).
	FrameAck FrameType = 6
)

// HeaderSize is the fixed wire header: type(1) + flowID(4) + seq(8) + len(4).
const HeaderSize = 1 + 4 + 8 + 4

// MaxPayload bounds a single frame's payload so a corrupt/hostile length
// can't make us allocate unbounded memory. 1 MiB is far above the 16 KiB
// data chunk size; Open/Ack/Fin payloads are tiny.
const MaxPayload = 1 << 20

// ErrPayloadTooLarge is returned by ReadFrame when the length prefix exceeds
// MaxPayload, indicating a desync or a hostile peer.
var ErrPayloadTooLarge = errors.New("stripe: frame payload exceeds maximum")

// Frame is one decoded protocol unit. payload aliases a per-read buffer in
// the streaming reader path, so callers that retain it past the next read
// must copy (the data path copies into the reorder buffer).
type Frame struct {
	Type    FrameType
	FlowID  uint32
	Seq     uint64
	Payload []byte
}

// Encode appends the wire form of f to dst and returns the extended slice.
// The pipe underneath is reliable and ordered, so a bare length-prefixed
// header is sufficient framing — no magic/checksum needed.
func (f Frame) Encode(dst []byte) []byte {
	var hdr [HeaderSize]byte
	hdr[0] = byte(f.Type)
	binary.BigEndian.PutUint32(hdr[1:5], f.FlowID)
	binary.BigEndian.PutUint64(hdr[5:13], f.Seq)
	binary.BigEndian.PutUint32(hdr[13:17], uint32(len(f.Payload)))
	dst = append(dst, hdr[:]...)
	dst = append(dst, f.Payload...)
	return dst
}

// ReadFrame reads exactly one frame from r. The returned Frame.Payload is a
// freshly allocated slice owned by the caller (safe to retain). On a clean
// pipe EOF before any header byte it returns io.EOF.
func ReadFrame(r io.Reader) (Frame, error) {
	var hdr [HeaderSize]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		// io.ReadFull maps a 0-byte read to io.EOF and a short read to
		// io.ErrUnexpectedEOF; pass both through unchanged so the caller
		// can distinguish a clean pipe close from a truncated frame.
		return Frame{}, err
	}
	plen := binary.BigEndian.Uint32(hdr[13:17])
	if plen > MaxPayload {
		return Frame{}, ErrPayloadTooLarge
	}
	f := Frame{
		Type:   FrameType(hdr[0]),
		FlowID: binary.BigEndian.Uint32(hdr[1:5]),
		Seq:    binary.BigEndian.Uint64(hdr[5:13]),
	}
	if plen > 0 {
		buf := make([]byte, plen)
		if _, err := io.ReadFull(r, buf); err != nil {
			return Frame{}, err
		}
		f.Payload = buf
	}
	return f, nil
}
