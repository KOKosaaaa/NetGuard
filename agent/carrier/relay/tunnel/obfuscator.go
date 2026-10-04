package tunnel

import (
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	_ "embed"
	"encoding/binary"
	"errors"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
)

// Precomputed real VP8 [tag + first-partition] prefixes (libvpx-encoded). In
// carrier mode our frames are prefix + [type][epoch][payload]: a VP8 decoder
// accepts these without error (token partition = our bytes decode to garbage,
// not an error), so the Yandex SFU treats the stream as valid video and
// forwards at full bitrate (~3 Mbit) instead of clamping non-decodable streams
// to ~1 Mbit. The joiner parses first_partition_size from the tag to strip the
// prefix and recover the payload.
//
//go:embed vp8_kf_prefix.bin
var carrierKFPrefix []byte

//go:embed vp8_inter_prefix.bin
var carrierInterPrefix []byte

// carrierMode switches frame framing to the decodable-VP8 carrier. Gated by
// env so production (non-carrier) behaviour is untouched. Both creator and
// joiner must agree (same env).
var carrierMode = os.Getenv("WLB_VALID_VP8_TUNNEL") != ""

// carrierAdapt enables closed-loop AIMD rate control: each side reports its
// receive rate to the peer (MsgRateReport), the peer ramps its send rate up
// when delivery is clean and backs off on loss, settling just under the link.
// WLB_CARRIER_KBPS becomes the MAX bound. Off => fixed-rate (current behavior).
var carrierAdapt = os.Getenv("WLB_CARRIER_ADAPT") != ""

// carrierARQ enables a reliable, in-order retransmit layer over the carrier
// (per-frame seq, reorder buffer, NACK-driven retransmit, skip-timeout for the
// unrecoverable). Lets the tunnel survive packet loss (so proxied TLS doesn't
// break) and lets AIMD probe the link without fatal collapse. WLB_CARRIER_ARQ.
var carrierARQ = os.Getenv("WLB_CARRIER_ARQ") != ""

// carrierSeqProbe is OBSERVE-ONLY instrumentation (WLB_CARRIER_SEQPROBE): the
// writer tags each data frame with a u32 seq and the receiver counts
// gap(loss)/reorder/dup against the expected seq, WITHOUT changing delivery
// (payload still goes straight to OnData). It measures the real per-frame
// reliability of the carrier so the reliability layer can be sized from data,
// not guesses. No retransmit, no reorder buffer, no throughput cost.
var carrierSeqProbe = os.Getenv("WLB_CARRIER_SEQPROBE") != ""

// carrierPCARQ enables PER-CONN reliable delivery (WLB_CARRIER_PCARQ): instead of
// one global in-order seq over the whole carrier (which head-of-line-blocks every
// relay conn when a single frame is lost), each relay connID gets its OWN seq,
// reorder buffer, NACK and skip-timeout. A loss on conn A no longer stalls conn
// B. Wire format differs from global ARQ, so BOTH ends must set this flag; it is
// a SEPARATE mode from WLB_CARRIER_ARQ (left intact so deployed Telemost is
// untouched until a coordinated rollout). When set it supersedes carrierARQ.
var carrierPCARQ = os.Getenv("WLB_CARRIER_PCARQ") != ""

// carrierMux enables MULTI-CLIENT mode (WLB_CARRIER_MUX): multiple peers share
// one room. Each carrier data frame is addressed to a destination peer epoch
// (prepended to the relay payload before AEAD: [destEpoch:u32 || bytes]); the
// creator demuxes incoming frames by SENDER epoch into per-peer relay sessions
// and tags each outbound frame with the target peer's epoch; each joiner only
// processes frames whose dest-epoch matches its own localEpoch. v1 shares the
// room's single downstream stream across peers (round-robin). Off by default.
var carrierMux = os.Getenv("WLB_CARRIER_MUX") != ""

// carrierNoLock bypasses the phantom-lock (debug: isolate ARQ from ghost
// mislock on hammered test rooms). WLB_NO_LOCK.
var carrierNoLock = os.Getenv("WLB_NO_LOCK") != ""

// carrierVerboseFrames gates the PER-FRAME debug logs in the carrier writer/
// reader (one line per VP8 frame at fps*tracks lines/sec). Default OFF: at
// 24fps×N tracks this spam burns CPU and pollutes throughput measurements
// (see project_wbstream_carrier). Event-level logs (start, AIMD, ARQ, restart)
// stay unconditional. WLB_VERBOSE_FRAMES=1 to re-enable for debugging.
var carrierVerboseFrames = os.Getenv("WLB_VERBOSE_FRAMES") != ""

// testDropEvery > 0 drops 1 in N incoming frames (HandleFrame) to simulate RTP
// loss locally for validating ARQ recovery. Test only (WLB_TEST_DROP).
var testDropEvery = func() uint64 {
	if v := os.Getenv("WLB_TEST_DROP"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return uint64(n)
		}
	}
	return 0
}()

// carrierKeyEvery: emit a keyframe-prefixed frame every N frames so the SFU
// keeps a decode reference (no persistent PLI).
const carrierKeyEvery = 48

// carrierPadTo (WLB_CARRIER_PAD): pad EVERY carrier frame (keepalive + data) up
// to this many bytes with random filler, so the stream is a CONSTANT-size
// ~full-bitrate flow like a real encoder. Measured: a constant 15KB/frame valid
// VP8 stream forwards at full ~2.85 Mbit forever, while a 665B-keepalive carrier
// gets cut to 0 by the SFU after ~1 min (it looks like a non-video trickle).
// 0 = off (legacy variable-size framing). Both creator+joiner must match.
var carrierPadTo = func() int {
	if v := os.Getenv("WLB_CARRIER_PAD"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return 0
}()

// carrierPadData also pads DATA frames (type 2, length-delimited) up to
// carrierPadTo. Requires BOTH ends to run this build (joiner must understand
// type 2). Keepalive padding alone (carrierPadTo) is BACKWARD-COMPATIBLE - an
// old joiner sees type 0 and ignores the filler - so the download stream can be
// warmed without rebuilding the phone's librelay. Default off.
var carrierPadData = os.Getenv("WLB_CARRIER_PAD_DATA") != ""

// carrierPadKA pads KEEPALIVE frames (type 0) up to carrierPadTo, but ONLY while
// the peer is idle (no data frame decoded in the last padYieldMs). This keeps
// ONE direction warm during idle WITHOUT creating two competing constant ~2.85
// Mbit streams between the same peers (which makes the SFU cut one direction -
// measured). When the peer starts sending data (its direction goes heavy), this
// side YIELDS to light keepalives. The server creator sets this (download
// warmer); the phone does NOT (it pads only its DATA when actively uploading).
var carrierPadKA = os.Getenv("WLB_CARRIER_PAD_KA") != ""

const padYieldMs = 600

// padFrame appends random filler so len(frame) == carrierPadTo (no-op if already
// >= target or padding disabled). Filler lands in the VP8 token partition where
// a decoder reads it as (discarded) garbage coefficients.
func padFrame(frame []byte) []byte {
	if carrierPadTo <= 0 || len(frame) >= carrierPadTo {
		return frame
	}
	fill := make([]byte, carrierPadTo-len(frame))
	rand.Read(fill)
	return append(frame, fill...)
}

var vp8Keepalive = []byte{
	0x30, 0x01, 0x00, 0x9d, 0x01, 0x2a, 0x10, 0x00,
	0x10, 0x00, 0x00, 0x47, 0x08, 0x85, 0x85, 0x88,
	0x99, 0x84, 0x88, 0xfc,
}

var vp8Interframe = []byte{
	0xb1, 0x01, 0x00, 0x08, 0x11, 0x18, 0x00, 0x18,
	0x00, 0x18, 0x58, 0x2f, 0xf4, 0x00, 0x08, 0x00,
	0x00,
}

const (
	vp8KeepaliveLen  = 20
	vp8InterframeLen = 17
	epochFieldLen    = 4
	keepaliveHdrLen  = vp8KeepaliveLen + epochFieldLen
	interframeHdrLen = vp8InterframeLen + epochFieldLen
)

var ErrEmptySecret = errors.New("tunnel: obfuscator requires a non-empty secret")

type DecodeResult struct {
	HasFrame       bool
	Keepalive      bool
	SelfEcho       bool
	PeerRestart    bool
	Payload        []byte
	PeerEpoch      uint32
	RecipientBound bool
}

type TunnelObfuscator struct {
	aead             cipher.AEAD
	localEpoch       uint32
	requireRecipient bool          // fixed before the session starts
	carrierRecipient atomic.Uint64 // bit32=bound framing; low32=destination epoch

	mu        sync.Mutex
	peerEpoch uint32
	hasPeer   bool

	frameCtr          atomic.Uint64 // carrier-mode frame counter for keyframe cadence
	keyframeRequested atomic.Bool   // coalesce SFU PLI/FIR until the next sample

	// Phantom-tolerant peer lock (carrier mode). The room can contain ghost
	// publishers sharing our AEAD key (stale sessions); their frames decrypt
	// and ping-pong the peer epoch, corrupting the byte stream. Lock onto the
	// first epoch that sends real data; ignore data from other epochs until
	// the locked peer goes idle for lockIdleMs.
	lockedEpoch atomic.Uint32
	lockedAtMs  atomic.Int64

	// Monotonic ms of the last DATA frame decoded from the peer. Used by the
	// keepalive-padding "warmer" to yield (stop padding) while the peer's
	// direction is heavy, so the two never run constant-full simultaneously.
	lastPeerDataMs atomic.Int64
}

const lockIdleMs = 30000

// RequestKeyframe changes only the video prefix; encrypted payloads, epochs,
// sequence numbers and active sockets keep their existing state.
func (o *TunnelObfuscator) RequestKeyframe() { o.keyframeRequested.Store(true) }

// carrierPrefix returns the keyframe prefix every carrierKeyEvery-th frame,
// else the interframe prefix.
func (o *TunnelObfuscator) carrierPrefix() []byte {
	n := o.frameCtr.Add(1)
	requested := o.keyframeRequested.Swap(false)
	if requested || n%carrierKeyEvery == 1 {
		return carrierKFPrefix
	}
	return carrierInterPrefix
}

func DeriveSecretFromJoinLink(joinLink string) []byte {
	token := extractJoinToken(joinLink)
	if token == "" {
		return nil
	}
	return []byte(token)
}

func extractJoinToken(joinLink string) string {
	s := strings.TrimSpace(joinLink)
	s = strings.TrimRight(s, "/")
	if i := strings.IndexByte(s, '?'); i >= 0 {
		s = s[:i]
	}
	if i := strings.IndexByte(s, '#'); i >= 0 {
		s = s[:i]
	}
	if i := strings.LastIndexByte(s, '/'); i >= 0 {
		s = s[i+1:]
	}
	return s
}

func NewTunnelObfuscator(secret []byte) (*TunnelObfuscator, error) {
	if len(secret) == 0 {
		return nil, ErrEmptySecret
	}
	keyHash := sha256.Sum256(secret)
	aead, err := chacha20poly1305.NewX(keyHash[:])
	if err != nil {
		return nil, err
	}
	var epochBytes [4]byte
	if _, err := rand.Read(epochBytes[:]); err != nil {
		return nil, err
	}
	epoch := binary.BigEndian.Uint32(epochBytes[:])
	if epoch == 0 {
		epoch = 1
	}
	return &TunnelObfuscator{aead: aead, localEpoch: epoch}, nil
}

func (o *TunnelObfuscator) LocalEpoch() uint32 { return o.localEpoch }

// PeerEpoch returns the last-seen peer epoch (0 if none seen yet).
func (o *TunnelObfuscator) PeerEpoch() uint32 {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.peerEpoch
}

// ResetPeerLock clears all peer-identity tracking so the NEXT peer to send data
// is adopted immediately. Call this when a genuine peer restart is detected at
// the SFU/track layer (a new participant published a track): the old peer's
// epoch is dead, but the phantom-lock would otherwise keep dropping the new
// epoch's data for up to lockIdleMs (30s), and peerEpoch bookkeeping would stay
// stale. Do NOT drive this off the per-frame PeerRestart flag — that ping-pongs
// whenever a ghost publisher shares our AEAD key. Idempotent; safe to call
// repeatedly (every sub-tunnel shares one obfuscator).
func (o *TunnelObfuscator) ResetPeerLock() {
	o.mu.Lock()
	o.hasPeer = false
	o.peerEpoch = 0
	o.mu.Unlock()
	o.lockedEpoch.Store(0)
	o.lockedAtMs.Store(0)
	o.lastPeerDataMs.Store(0)
}

func (o *TunnelObfuscator) keepaliveHeader() []byte {
	hdr := make([]byte, keepaliveHdrLen)
	copy(hdr, vp8Keepalive)
	binary.BigEndian.PutUint32(hdr[vp8KeepaliveLen:], o.localEpoch)
	return hdr
}

func (o *TunnelObfuscator) dataHeader() []byte {
	hdr := make([]byte, interframeHdrLen)
	copy(hdr, vp8Interframe)
	binary.BigEndian.PutUint32(hdr[vp8InterframeLen:], o.localEpoch)
	return hdr
}

func (o *TunnelObfuscator) EncodeKeepalive() []byte {
	if carrierMode {
		pre := o.carrierPrefix()
		out := make([]byte, 0, len(pre)+5)
		out = append(out, pre...)
		out = append(out, 0) // type 0 = keepalive
		out = binary.BigEndian.AppendUint32(out, o.localEpoch)
		// Warm the stream with a full-size keepalive ONLY while the peer is idle.
		// If the peer sent data recently, yield to light keepalives so this side
		// doesn't run heavy at the same time as the peer's heavy direction (two
		// constant-full streams get one cut by the SFU).
		if carrierPadKA && carrierPadTo > 0 &&
			time.Now().UnixMilli()-o.lastPeerDataMs.Load() > padYieldMs {
			return padFrame(out)
		}
		return out
	}
	return o.keepaliveHeader()
}

// EnableRecipientBinding is configured before starting a WB session. A joiner
// rejects unaddressed reverse frames from its predecessor; a creator can still
// serve legacy joiners and switches replies only after authenticated acceptance.
func (o *TunnelObfuscator) EnableRecipientBinding(requireAddress bool) {
	o.requireRecipient = requireAddress
	if requireAddress {
		o.carrierRecipient.Store(1 << 32)
	}
}

func (o *TunnelObfuscator) SetRecipientEpoch(epoch uint32, bound bool) {
	address := uint64(epoch)
	if bound || o.requireRecipient {
		address |= 1 << 32
	}
	o.carrierRecipient.Store(address)
}

// Only the joiner broadcasts an authenticated discovery hello to find a restarted exit.
func (o *TunnelObfuscator) NeedsRecipientDiscovery() bool { return o.requireRecipient }

func (o *TunnelObfuscator) RecipientSnapshot() uint64 { return o.carrierRecipient.Load() }

// Snapshot the destination before taking data from reliable queues. A reset
// during assembly can then never address old extracted bytes to the new peer.
func (o *TunnelObfuscator) EncodeDataFor(payload []byte, address uint64) []byte {
	if carrierMode && address>>32 != 0 {
		plain := binary.BigEndian.AppendUint32(nil, o.localEpoch)
		plain = binary.BigEndian.AppendUint32(plain, uint32(address))
		plain = append(plain, payload...)
		sealed := o.EncryptPayload(plain)
		if sealed == nil {
			return nil
		}
		out := append(append([]byte(nil), o.carrierPrefix()...), 3)
		out = binary.BigEndian.AppendUint32(out, o.localEpoch)
		return append(out, sealed...)
	}
	return o.encodeLegacyData(payload)
}

func (o *TunnelObfuscator) EncodeData(payload []byte) []byte {
	return o.EncodeDataFor(payload, o.RecipientSnapshot())
}

func (o *TunnelObfuscator) encodeLegacyData(payload []byte) []byte {
	if carrierMode {
		pre := o.carrierPrefix()
		nonce := make([]byte, o.aead.NonceSize())
		if _, err := rand.Read(nonce); err != nil {
			return nil
		}
		if carrierPadData && carrierPadTo > 0 {
			// type 2 = padded data: prefix||2||epoch||msglen:u32||nonce||AEAD||filler.
			// msglen delimits the real bytes so the joiner strips the random
			// padding before AEAD-open. Keeps every frame constant full size.
			msg := make([]byte, 0, len(nonce)+len(payload)+o.aead.Overhead())
			msg = append(msg, nonce...)
			msg = o.aead.Seal(msg, nonce, payload, nil)
			out := make([]byte, 0, len(pre)+9+len(msg))
			out = append(out, pre...)
			out = append(out, 2)
			out = binary.BigEndian.AppendUint32(out, o.localEpoch)
			out = binary.BigEndian.AppendUint32(out, uint32(len(msg)))
			out = append(out, msg...)
			return padFrame(out)
		}
		out := make([]byte, 0, len(pre)+5+len(nonce)+len(payload)+o.aead.Overhead())
		out = append(out, pre...)
		out = append(out, 1) // type 1 = data
		out = binary.BigEndian.AppendUint32(out, o.localEpoch)
		out = append(out, nonce...)
		out = o.aead.Seal(out, nonce, payload, nil)
		return out
	}
	hdr := o.dataHeader()
	nonce := make([]byte, o.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil
	}
	out := make([]byte, 0, len(hdr)+len(nonce)+len(payload)+o.aead.Overhead())
	out = append(out, hdr...)
	out = append(out, nonce...)
	out = o.aead.Seal(out, nonce, payload, nil)
	return out
}

func (o *TunnelObfuscator) EncryptPayload(plaintext []byte) []byte {
	if o == nil {
		return plaintext
	}
	nonce := make([]byte, o.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil
	}
	out := make([]byte, 0, len(nonce)+len(plaintext)+o.aead.Overhead())
	out = append(out, nonce...)
	return o.aead.Seal(out, nonce, plaintext, nil)
}

func (o *TunnelObfuscator) DecryptPayload(data []byte) ([]byte, bool) {
	if o == nil {
		return data, true
	}
	nonceSize := o.aead.NonceSize()
	if len(data) < nonceSize+o.aead.Overhead() {
		return nil, false
	}
	nonce := data[:nonceSize]
	ciphertext := data[nonceSize:]
	plaintext, err := o.aead.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, false
	}
	return plaintext, true
}

// InspectCarrierFrame validates carrier data without changing peer identity or
// reliability state. WB rooms also contain ordinary, unauthenticated video.
func (o *TunnelObfuscator) InspectCarrierFrame(frame []byte) DecodeResult {
	if len(frame) < 3 {
		return DecodeResult{}
	}
	tag := uint32(frame[0]) | uint32(frame[1])<<8 | uint32(frame[2])<<16
	key := (tag & 1) == 0
	fps := (tag >> 5) & 0x7FFFF
	prefixLen := 3 + int(fps)
	if key {
		prefixLen += 7 // 3B start code + 2B width + 2B height
	}
	if len(frame) < prefixLen+5 {
		return DecodeResult{}
	}
	typ := frame[prefixLen]
	if typ > 3 {
		return DecodeResult{}
	}
	peerEpoch := binary.BigEndian.Uint32(frame[prefixLen+1 : prefixLen+5])
	if peerEpoch == o.localEpoch {
		return DecodeResult{HasFrame: true, SelfEcho: true, PeerEpoch: peerEpoch}
	}
	res := DecodeResult{HasFrame: true, PeerEpoch: peerEpoch}
	var body []byte
	if typ == 2 {
		// padded data: prefix||2||epoch||msglen:u32||nonce||AEAD||filler
		if len(frame) < prefixLen+9 {
			return DecodeResult{}
		}
		msglen := int(binary.BigEndian.Uint32(frame[prefixLen+5 : prefixLen+9]))
		if msglen < 0 || prefixLen+9+msglen > len(frame) {
			return DecodeResult{}
		}
		body = frame[prefixLen+9 : prefixLen+9+msglen]
	} else {
		body = frame[prefixLen+5:]
	}
	if typ == 0 {
		res.Keepalive = true
		return res
	}
	plaintext, ok := o.DecryptPayload(body)
	if !ok {
		return DecodeResult{}
	}
	if typ == 3 {
		if len(plaintext) < 8 || binary.BigEndian.Uint32(plaintext[:4]) != peerEpoch {
			return DecodeResult{}
		}
		dest := binary.BigEndian.Uint32(plaintext[4:8])
		if (dest != 0 && dest != o.localEpoch) || (o.requireRecipient && dest == 0) {
			return DecodeResult{}
		}
		res.RecipientBound = true
		plaintext = plaintext[8:]
	} else if o.requireRecipient {
		return DecodeResult{}
	}
	res.Payload = plaintext
	return res
}

func (o *TunnelObfuscator) decodeCarrier(frame []byte) DecodeResult {
	res := o.InspectCarrierFrame(frame)
	if !res.HasFrame || res.SelfEcho || res.Keepalive {
		return res
	}
	peerEpoch := res.PeerEpoch
	// Neither camera bytes nor unauthenticated keepalives may claim a peer or
	// refresh its lock. Only successfully decrypted tunnel data reaches here.
	o.mu.Lock()
	defer o.mu.Unlock()
	// Phantom lock: accept data only from the locked peer; lock to the first
	// data sender, re-lock after lockIdleMs idle. Foreign-epoch data dropped.
	// WLB_NO_LOCK bypasses it (debug: rule out ghost mislock on test rooms).
	// carrierMux also bypasses it: multi-client must accept ALL peer epochs
	// (the creator demuxes them by sender epoch into per-peer bridges).
	if !carrierNoLock && !carrierMux {
		now := time.Now().UnixMilli()
		le := o.lockedEpoch.Load()
		if le == 0 || now-o.lockedAtMs.Load() > lockIdleMs {
			o.lockedEpoch.Store(peerEpoch)
			o.lockedAtMs.Store(now)
		} else if le != peerEpoch {
			res.Keepalive = true // ignore ghost-publisher data
			res.Payload = nil
			return res
		} else {
			o.lockedAtMs.Store(now)
		}
	}
	if !o.hasPeer {
		o.peerEpoch = peerEpoch
		o.hasPeer = true
	} else if o.peerEpoch != peerEpoch {
		o.peerEpoch = peerEpoch
		res.PeerRestart = true
	}
	// Peer's direction is actively carrying data - the keepalive warmer yields.
	o.lastPeerDataMs.Store(time.Now().UnixMilli())
	return res
}

func (o *TunnelObfuscator) Decode(frame []byte) DecodeResult {
	if carrierMode {
		return o.decodeCarrier(frame)
	}
	if len(frame) < 1 {
		return DecodeResult{}
	}
	var hdrLen, epochOff int
	switch frame[0] {
	case vp8Keepalive[0]:
		hdrLen = keepaliveHdrLen
		epochOff = vp8KeepaliveLen
	case vp8Interframe[0]:
		hdrLen = interframeHdrLen
		epochOff = vp8InterframeLen
	default:
		return DecodeResult{}
	}
	if len(frame) < hdrLen {
		return DecodeResult{}
	}
	peerEpoch := binary.BigEndian.Uint32(frame[epochOff : epochOff+epochFieldLen])
	if peerEpoch == o.localEpoch {
		return DecodeResult{HasFrame: true, SelfEcho: true, PeerEpoch: peerEpoch}
	}

	res := DecodeResult{HasFrame: true, PeerEpoch: peerEpoch}
	o.mu.Lock()
	if !o.hasPeer {
		o.peerEpoch = peerEpoch
		o.hasPeer = true
	} else if o.peerEpoch != peerEpoch {
		o.peerEpoch = peerEpoch
		res.PeerRestart = true
	}
	o.mu.Unlock()

	if len(frame) == hdrLen {
		res.Keepalive = true
		return res
	}

	body := frame[hdrLen:]
	nonceSize := o.aead.NonceSize()
	if len(body) < nonceSize+o.aead.Overhead() {
		return DecodeResult{}
	}
	nonce := body[:nonceSize]
	ciphertext := body[nonceSize:]
	plaintext, err := o.aead.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return DecodeResult{}
	}
	res.Payload = plaintext
	return res
}
