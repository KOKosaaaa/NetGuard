package tunnel

import (
	"encoding/binary"
	"errors"
)

// VP8 data-carrying keyframe: emit a syntactically-valid VP8 keyframe whose DCT
// coefficient tokens ENCODE our tunnel bytes. The Yandex SFU decodes it without
// error (valid syntax -> no PLI -> full-rate forward, ~2.4 Mbit measured) while
// the receiving relay re-decodes the tokens to recover the bytes.
//
// Scheme (v1, deliberately simple & robust):
//   - Every coefficient block is FULLY populated with non-zero coefficients
//     (positions firstCoeff..15). No DCT_0, no EOB token -> the block ends
//     naturally at 16 coefficients, which sidesteps VP8's "EOB cannot follow a
//     zero" context special-case entirely.
//   - Each coefficient carries 3 payload bits: 2 select the token among
//     {DCT_1,DCT_2,DCT_3,DCT_4}, 1 is the sign bit.
//   - Entropy context (coeff band, above/left non-zero, prev-token) is tracked
//     IDENTICALLY on both ends so the arithmetic coder round-trips exactly.
//
// Capacity per keyframe = mbw*mbh * (Y2:16 + 16*Y:15 + 8*UV:16) * 3 bits
//                       = mbw*mbh * 384 * 3 bits. 320x180 -> ~34 KB/frame.

// coeffTree is the VP8 DCT coefficient token tree (RFC 6386 13.2). Leaves are
// -token (DCT_0=0 .. dct_cat6=10, dct_eob=11); internal nodes are even indices.
var coeffTree = []int{
	-11, 2, // eob / ->
	0, 4, // DCT_0 (-0) / ->
	-1, 6, // DCT_1 / ->
	8, 12,
	-2, 10, // DCT_2 / ->
	-3, -4, // DCT_3 / DCT_4
	14, 16,
	-5, -6, // cat1 / cat2
	18, 20,
	-7, -8, // cat3 / cat4
	-9, -10, // cat5 / cat6
}

const (
	dctEOB  = 11
	maxCoef = 16
)

// --- boolean (range) DECODER, inverse of boolEncoder (RFC 6386 7.3) ---

type boolDecoder struct {
	buf      []byte
	pos      int
	value    uint32
	rangeV   uint32
	bitCount int
}

func newBoolDecoder(buf []byte) *boolDecoder {
	d := &boolDecoder{buf: buf, rangeV: 255}
	d.value = uint32(d.nextByte())<<8 | uint32(d.nextByte())
	return d
}

func (d *boolDecoder) nextByte() byte {
	if d.pos < len(d.buf) {
		b := d.buf[d.pos]
		d.pos++
		return b
	}
	d.pos++
	return 0
}

func (d *boolDecoder) getBit(prob int) int {
	split := 1 + (((d.rangeV - 1) * uint32(prob)) >> 8)
	bigSplit := split << 8
	var ret int
	if d.value >= bigSplit {
		ret = 1
		d.rangeV -= split
		d.value -= bigSplit
	} else {
		ret = 0
		d.rangeV = split
	}
	for d.rangeV < 128 {
		d.value <<= 1
		d.rangeV <<= 1
		d.bitCount++
		if d.bitCount == 8 {
			d.bitCount = 0
			d.value |= uint32(d.nextByte())
		}
	}
	return ret
}

func (d *boolDecoder) readTree(tree []int, probs []uint8) int {
	i := 0
	for {
		b := d.getBit(int(probs[i>>1]))
		i = tree[i+b]
		if i <= 0 {
			return -i
		}
	}
}

// --- payload bit reader / writer (MSB-first) ---

type bitReader struct {
	data   []byte
	bitpos int
}

// read returns the next n bits (MSB-first); reads past the end return 0.
func (r *bitReader) read(n int) int {
	v := 0
	for i := 0; i < n; i++ {
		v <<= 1
		bytePos := r.bitpos >> 3
		if bytePos < len(r.data) {
			bit := (r.data[bytePos] >> uint(7-(r.bitpos&7))) & 1
			v |= int(bit)
		}
		r.bitpos++
	}
	return v
}

type bitWriter struct {
	data []byte
	cur  byte
	fill int
}

func (w *bitWriter) write(val, n int) {
	for i := n - 1; i >= 0; i-- {
		w.cur = (w.cur << 1) | byte((val>>uint(i))&1)
		w.fill++
		if w.fill == 8 {
			w.data = append(w.data, w.cur)
			w.cur = 0
			w.fill = 0
		}
	}
}

func (w *bitWriter) flush() []byte {
	if w.fill > 0 {
		w.data = append(w.data, w.cur<<uint(8-w.fill))
		w.cur = 0
		w.fill = 0
	}
	return w.data
}

// --- entropy context (per RFC 6386 13) ---
// All blocks we emit are non-empty, so non-zero flags become 1 after the first
// row/column; we still track them exactly so encoder & decoder pick the same
// probabilities (the only edge cases are the frame's top row and left column).

type entropyCtx struct {
	mbw                int
	aboveY, leftY      []int // 4 cols/rows of 4x4
	aboveU, leftU      []int // 2x2
	aboveV, leftV      []int
	aboveY2, leftY2    []int // 1 per MB
}

func newEntropyCtx(mbw int) *entropyCtx {
	return &entropyCtx{
		mbw:     mbw,
		aboveY:  make([]int, mbw*4),
		aboveU:  make([]int, mbw*2),
		aboveV:  make([]int, mbw*2),
		aboveY2: make([]int, mbw),
		leftY:   make([]int, 4),
		leftU:   make([]int, 2),
		leftV:   make([]int, 2),
		leftY2:  make([]int, 1),
	}
}

// resetLeft clears the left contexts at the start of each macroblock row.
func (c *entropyCtx) resetLeft() {
	for i := range c.leftY {
		c.leftY[i] = 0
	}
	for i := range c.leftU {
		c.leftU[i] = 0
	}
	for i := range c.leftV {
		c.leftV[i] = 0
	}
	c.leftY2[0] = 0
}

// blockType -> coeff prob table index. 0=Y(after Y2), 1=Y2, 2=UV.
// firstCoeff: 1 for Y-after-Y2 (DC lives in Y2), else 0.

// codeBlockEncode emits one fully-populated block's tokens, pulling 3 payload
// bits per coefficient from r. aboveNZ/leftNZ are pointers to this block's
// context cells (updated to 1, since the block is non-empty).
func codeBlockEncode(e *boolEncoder, r *bitReader, blockType, firstCoeff int, aboveNZ, leftNZ *int) {
	prevToken := -1 // -1 => use first-coeff (above+left) context
	for pos := firstCoeff; pos < maxCoef; pos++ {
		band := coeffBands[pos]
		ctx := coeffContext(prevToken, *aboveNZ, *leftNZ)
		probs := defaultCoefProbs[blockType][band][ctx][:]
		sel := r.read(2)       // 0..3 -> token DCT_1..DCT_4
		token := sel + 1       // 1..4
		e.writeTree(coeffTree, probs, token)
		e.putBit(128, r.read(1)) // sign bit (carries 1 payload bit)
		prevToken = token
	}
	*aboveNZ = 1
	*leftNZ = 1
}

// codeBlockDecode mirrors codeBlockEncode: reads tokens+signs and writes the
// recovered 3 bits/coefficient into w.
func codeBlockDecode(d *boolDecoder, w *bitWriter, blockType, firstCoeff int, aboveNZ, leftNZ *int) error {
	prevToken := -1
	for pos := firstCoeff; pos < maxCoef; pos++ {
		band := coeffBands[pos]
		ctx := coeffContext(prevToken, *aboveNZ, *leftNZ)
		probs := defaultCoefProbs[blockType][band][ctx][:]
		token := d.readTree(coeffTree, probs)
		if token < 1 || token > 4 {
			return errors.New("vp8data: unexpected token (stream not ours / corrupt)")
		}
		w.write(token-1, 2)
		sign := d.getBit(128)
		w.write(sign, 1)
		prevToken = token
	}
	*aboveNZ = 1
	*leftNZ = 1
	return nil
}

// coeffContext returns the token-prob context (0..2). For the first coefficient
// (prevToken<0) it is above+left non-zero; afterwards it is based on the
// magnitude of the previous token: 0 (zero, never here), 1 (==1), 2 (>1).
func coeffContext(prevToken, aboveNZ, leftNZ int) int {
	if prevToken < 0 {
		return aboveNZ + leftNZ
	}
	if prevToken == 1 {
		return 1
	}
	return 2
}

// iterate walks the per-macroblock, per-block coefficient layout and invokes
// `fn` for each block with its (blockType, firstCoeff, aboveNZ*, leftNZ*). Used
// by both encoder and decoder so the traversal order & context wiring match.
func (c *entropyCtx) iterate(mbw, mbh int, fn func(blockType, firstCoeff int, aboveNZ, leftNZ *int)) {
	for my := 0; my < mbh; my++ {
		c.resetLeft()
		for mx := 0; mx < mbw; mx++ {
			// Y2 (type 1)
			fn(1, 0, &c.aboveY2[mx], &c.leftY2[0])
			// 16 Y subblocks (type 0, firstCoeff=1), 4x4 raster
			for sb := 0; sb < 16; sb++ {
				row, col := sb/4, sb%4
				fn(0, 1, &c.aboveY[mx*4+col], &c.leftY[row])
			}
			// 4 U subblocks (type 2), 2x2
			for sb := 0; sb < 4; sb++ {
				row, col := sb/2, sb%2
				fn(2, 0, &c.aboveU[mx*2+col], &c.leftU[row])
			}
			// 4 V subblocks (type 2)
			for sb := 0; sb < 4; sb++ {
				row, col := sb/2, sb%2
				fn(2, 0, &c.aboveV[mx*2+col], &c.leftV[row])
			}
		}
	}
}

// frameCapacityBits returns how many payload bits a w*h keyframe can carry.
func frameCapacityBits(w, h int) int {
	mbw, mbh := (w+15)/16, (h+15)/16
	coeffs := mbw * mbh * (16 + 16*15 + 8*16)
	return coeffs * 3
}

// AssembleKeyframeData builds a valid VP8 keyframe carrying `payload`. A 32-bit
// big-endian length prefix is embedded so the decoder knows the real byte count;
// remaining capacity is padded with zero bits. Returns an error if the payload
// does not fit in one w*h frame.
func AssembleKeyframeData(w, h int, payload []byte) ([]byte, error) {
	capBits := frameCapacityBits(w, h)
	need := 32 + len(payload)*8
	if need > capBits {
		return nil, errors.New("vp8data: payload too large for frame")
	}
	framed := make([]byte, 4+len(payload))
	binary.BigEndian.PutUint32(framed[:4], uint32(len(payload)))
	copy(framed[4:], payload)
	r := &bitReader{data: framed}

	mbw, mbh := (w+15)/16, (h+15)/16

	// First partition: header + per-MB mode records (skip=0 so tokens are read).
	fp := newBoolEncoder()
	writeKeyframeHeader(fp)
	for n := 0; n < mbw*mbh; n++ {
		fp.putBit(128, 0)                         // mb_skip_coeff = 0 (not skipped)
		fp.writeTree(kfYmodeTree, kfYmodeProb, 0) // Y mode = DC_PRED (uses Y2)
		fp.writeTree(uvModeTree, kfUvModeProb, 0) // UV mode = DC_PRED
	}
	firstPart := fp.finish()

	// Token partition: coefficients for every block.
	tp := newBoolEncoder()
	ctx := newEntropyCtx(mbw)
	ctx.iterate(mbw, mbh, func(blockType, firstCoeff int, aboveNZ, leftNZ *int) {
		codeBlockEncode(tp, r, blockType, firstCoeff, aboveNZ, leftNZ)
	})
	tokenPart := tp.finish()

	return assembleFrame(w, h, firstPart, tokenPart), nil
}

// DecodeKeyframeData recovers the payload from a keyframe produced by
// AssembleKeyframeData.
func DecodeKeyframeData(frame []byte) ([]byte, error) {
	if len(frame) < 10 {
		return nil, errors.New("vp8data: frame too short")
	}
	tag := int(frame[0]) | int(frame[1])<<8 | int(frame[2])<<16
	if tag&1 != 0 {
		return nil, errors.New("vp8data: not a keyframe")
	}
	firstPartSize := (tag >> 5) & 0x7FFFF
	w := (int(frame[6]) | int(frame[7])<<8) & 0x3FFF
	h := (int(frame[8]) | int(frame[9])<<8) & 0x3FFF
	tokenOff := 10 + firstPartSize
	if tokenOff > len(frame) {
		return nil, errors.New("vp8data: bad first_part_size")
	}
	mbw, mbh := (w+15)/16, (h+15)/16

	d := newBoolDecoder(frame[tokenOff:])
	w2 := &bitWriter{}
	var derr error
	ctx := newEntropyCtx(mbw)
	ctx.iterate(mbw, mbh, func(blockType, firstCoeff int, aboveNZ, leftNZ *int) {
		if derr != nil {
			return
		}
		if e := codeBlockDecode(d, w2, blockType, firstCoeff, aboveNZ, leftNZ); e != nil {
			derr = e
		}
	})
	if derr != nil {
		return nil, derr
	}
	bits := w2.flush()
	if len(bits) < 4 {
		return nil, errors.New("vp8data: decoded stream too short")
	}
	n := binary.BigEndian.Uint32(bits[:4])
	if int(n) > len(bits)-4 {
		return nil, errors.New("vp8data: length prefix exceeds decoded data")
	}
	return bits[4 : 4+n], nil
}

// writeKeyframeHeader writes the fixed first-partition header (same fields as
// AssembleKeyframeSkip) into fp, EXCEPT the per-MB records which the caller adds.
func writeKeyframeHeader(fp *boolEncoder) {
	fp.putFlag(0)       // color_space
	fp.putFlag(0)       // clamping_type
	fp.putFlag(0)       // segmentation_enabled
	fp.putFlag(0)       // filter_type
	fp.putLiteral(0, 6) // loop_filter_level
	fp.putLiteral(0, 3) // sharpness_level
	fp.putFlag(0)       // loop_filter_adj_enable
	fp.putLiteral(0, 2) // log2_nbr_of_DCT_partitions = 0 -> 1 token partition
	fp.putLiteral(4, 7) // y_ac_qi
	fp.putFlag(0)       // y_dc_delta
	fp.putFlag(0)       // y2_dc_delta
	fp.putFlag(0)       // y2_ac_delta
	fp.putFlag(0)       // uv_dc_delta
	fp.putFlag(0)       // uv_ac_delta
	fp.putFlag(1)       // refresh_entropy_probs
	for i := 0; i < 4; i++ {
		for j := 0; j < 8; j++ {
			for k := 0; k < 3; k++ {
				for t := 0; t < 11; t++ {
					fp.putBit(int(coefUpdateProbs[i][j][k][t]), 0) // no coeff-prob update
				}
			}
		}
	}
	fp.putFlag(1)         // mb_no_skip_coeff = 1
	fp.putLiteral(128, 8) // prob_skip_false
}

// assembleFrame wraps the two partitions in the uncompressed keyframe header.
func assembleFrame(w, h int, firstPart, tokenPart []byte) []byte {
	fpSize := len(firstPart)
	tag := (fpSize << 5) | (1 << 4) // first_part_size, show_frame=1, version=0, keyframe=0
	out := []byte{
		byte(tag), byte(tag >> 8), byte(tag >> 16),
		0x9d, 0x01, 0x2a,
		byte(w & 0xff), byte((w >> 8) & 0x3f),
		byte(h & 0xff), byte((h >> 8) & 0x3f),
	}
	out = append(out, firstPart...)
	out = append(out, tokenPart...)
	return out
}
