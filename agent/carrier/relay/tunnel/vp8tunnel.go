package tunnel

import (
	"encoding/binary"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"whitelist-bypass/relay/common"
)

const (
	defaultVP8FPS   = 24
	defaultVP8Batch = 30
	// keepaliveIdlePeriod controls how often we emit a keepalive frame
	// when sendQueue is empty. Originally 100ms (=10 keepalives/s) which
	// kept the WebRTC heartbeat lively at the cost of CPU on idle channels.
	// Raised to 500ms (=2 keepalives/s) — still well within typical
	// WebRTC liveness thresholds (RTCP gives the actual peer-loss signal
	// at a different cadence) and cuts idle wake-ups by 5×. On a phone
	// with 6 parallel librelay instances this is most of the heat.
	keepaliveIdlePeriod = 500 * time.Millisecond
	sendQueueDepth      = 1024
	// idleTickerSlowdown — when sendQueue stays empty for this many ticks
	// in a row, the writer slows the ticker to keepaliveIdlePeriod. Real
	// traffic resets it back to sampleInterval (fast). This avoids running
	// a sub-millisecond ticker just to wait for data that isn't coming.
	idleTickerSlowdown = 240
)

type VP8DataTunnel struct {
	// Tracks for outbound VP8 publication. When multi-publisher mode is
	// enabled (creator added extra video transceivers), each tick consumes
	// up to len(tracks) chunks from sendQueue and pushes one to each track,
	// multiplying effective per-room downstream cap. With one track this
	// degenerates to the original behaviour.
	tracks       []*webrtc.TrackLocalStaticSample
	logFn        func(string, ...any)
	obf          *TunnelObfuscator
	stopCh       chan struct{}
	sendQueue    chan []byte
	cfgChan      chan struct{}
	mediaControl chan addressedMediaControl

	stopOnce sync.Once
	running  atomic.Bool

	cfgMu sync.Mutex
	fps   int
	batch int

	sentFrames   atomic.Uint64
	recvFrames   atomic.Uint64
	recvAttempts atomic.Uint64
	recvFail     atomic.Uint64

	// Adaptive carrier rate control (WLB_CARRIER_ADAPT).
	sentData     atomic.Uint64 // carrier DATA payload bytes sent
	recvData     atomic.Uint64 // carrier DATA payload bytes received
	dynRateKbps  atomic.Int64  // current adaptive send target; 0 => use fixed env
	maxRateKbps  int           // upper bound (from WLB_CARRIER_KBPS)
	lastSentSnap uint64        // OnRateReport-local
	lastReportAt time.Time     // OnRateReport-local

	// ARQ reliable layer (WLB_CARRIER_ARQ).
	ctrlQueue   chan []byte // pre-built ARQ control/retransmit payloads (priority)
	arqSendSeq  uint32
	arqSendMu   sync.Mutex
	arqSendBuf  map[uint32][]byte // seq -> relay bytes, for retransmit
	arqSendKeys []uint32          // FIFO of buffered seqs for eviction
	arqNackWin  map[uint32]bool   // seqs NACK'd this AIMD window (dedupe re-NACKs)
	arqRecvMu   sync.Mutex
	arqExpected uint32
	arqReorder  map[uint32][]byte // seq -> relay bytes, out-of-order hold
	arqMaxSeen  uint32
	arqGapAt    time.Time     // when the current head gap was first observed
	arqNacked   atomic.Uint64 // seqs requested via NACK this window (congestion signal)

	// Per-conn ARQ (WLB_CARRIER_PCARQ). Each relay connID is an INDEPENDENT
	// reliable stream (own seq/reorder/NACK/skip-timeout), so a loss on one conn
	// never head-of-line-blocks the others. connIDs come from a monotonic counter
	// and are never reused, so closed-conn state can be pruned without resurrection
	// ambiguity. Send keys are key(connID,seq)=connID<<32|seq.
	pcFlow       *pcFlowState
	pcSendMu     sync.Mutex
	pcSendSeq    map[uint32]uint32 // connID -> last assigned seq
	pcSendBuf    map[uint64][]byte // key -> on-wire unit [seq||relayFrame], for retransmit
	pcSendFIFO   []uint64          // eviction order of keys (global bound)
	pcNackWin    map[uint64]bool   // dedupe loss until this unit is ACKed/evicted
	pcSentUnits  atomic.Uint64     // relay-frames sent (correct AIMD loss denominator)
	pcAckedUnits atomic.Uint64     // cumulative unique delivery confirmations, survives reset

	pcRecvMu   sync.Mutex
	pcExpected map[uint32]uint32            // connID -> next expected seq
	pcReorder  map[uint32]map[uint32][]byte // connID -> seq -> relay frame
	pcMaxSeen  map[uint32]uint32            // connID -> max seq seen
	pcGapAt    map[uint32]time.Time         // connID -> when current head gap first seen

	// observe-only seq probe (WLB_CARRIER_SEQPROBE) - measures real frame
	// loss/reorder/dup without changing delivery.
	probeSendSeq atomic.Uint32
	probeRecvMu  sync.Mutex
	probeExpect  uint32
	probeMaxSeen uint32
	probeInOrder uint64
	probeGap     uint64 // seqs skipped (frames lost or not-yet-arrived ahead)
	probeReorder uint64 // arrived after a later seq (late/out-of-order)
	probeDup     uint64

	// multi-client mux (WLB_CARRIER_MUX). One bounded outbound queue PER peer
	// epoch; the carrier writer round-robins peers (one peer's bytes per
	// frame, tagged with its dest-epoch) so a heavy peer can't starve others.
	// Bounded channels give natural backpressure (a slow-draining peer blocks
	// its own upstream read instead of dropping bytes = corrupting the TCP
	// stream). muxOrder + muxRR drive fair round-robin.
	muxMu    sync.Mutex
	muxPeers map[uint32]chan []byte
	muxOrder []uint32
	muxRR    int
	// OnDataMux, when set (creator multi-client), is called with the SENDER
	// epoch + payload for routing into per-peer bridges instead of OnData.
	OnDataMux func(senderEpoch uint32, data []byte)

	OnData  func([]byte)
	OnClose func()
}

func (t *VP8DataTunnel) SetOnData(fn func([]byte)) { t.OnData = fn }
func (t *VP8DataTunnel) SetOnClose(fn func())      { t.OnClose = fn }

func NewVP8DataTunnel(track *webrtc.TrackLocalStaticSample, obf *TunnelObfuscator, logFn func(string, ...any)) *VP8DataTunnel {
	return NewVP8DataTunnelMulti([]*webrtc.TrackLocalStaticSample{track}, obf, logFn)
}

func NewVP8DataTunnelMulti(tracks []*webrtc.TrackLocalStaticSample, obf *TunnelObfuscator, logFn func(string, ...any)) *VP8DataTunnel {
	return &VP8DataTunnel{
		tracks:       tracks,
		obf:          obf,
		logFn:        logFn,
		stopCh:       make(chan struct{}),
		sendQueue:    make(chan []byte, sendQueueDepth),
		muxPeers:     make(map[uint32]chan []byte),
		cfgChan:      make(chan struct{}, 1),
		mediaControl: make(chan addressedMediaControl, 2),
		fps:          defaultVP8FPS,
		batch:        defaultVP8Batch,
		ctrlQueue:    make(chan []byte, 256),
		arqSendBuf:   make(map[uint32][]byte),
		arqReorder:   make(map[uint32][]byte),
		arqNackWin:   make(map[uint32]bool),
		pcFlow:       newPCFlow(),
		pcSendSeq:    make(map[uint32]uint32),
		pcSendBuf:    make(map[uint64][]byte),
		pcNackWin:    make(map[uint64]bool),
		pcExpected:   make(map[uint32]uint32),
		pcReorder:    make(map[uint32]map[uint32][]byte),
		pcMaxSeen:    make(map[uint32]uint32),
		pcGapAt:      make(map[uint32]time.Time),
	}
}

// ARQ on-wire (inside the obfuscator payload): first byte is the ARQ type.
const (
	arqData = 0x01 // [0x01][seq:u32][relay bytes]
	arqNack = 0x02 // [0x02][count:u16][seq:u32]*count
	// Per-conn ARQ (carrierPCARQ). Each unit carries ONE whole relay-frame (which
	// is self-delimiting via its own u32 length prefix) tagged with a per-conn seq.
	pcData = 0x10 // [0x10] then units: [seq:u32][relay-frame]...
	pcNack = 0x11 // [0x11][count:u16] then [connID:u32][seq:u32]*count
)

func pcKey(connID, seq uint32) uint64 { return uint64(connID)<<32 | uint64(seq) }

const (
	arqSendBufMax = 4096             // max buffered frames for retransmit
	arqSkipAfter  = 30 * time.Second // compatibility name: close, NEVER skip a reliable head gap
	arqMaxNack    = 64               // max seqs per NACK message
	// arqWarmupSlack bounds the "fresh stream" anchor. A sender always begins at
	// seq 1, but the VP8 carrier's first frames can fail to decode during keyframe
	// warmup, so the first seq the receiver actually decodes may be > 1. If that
	// first seq is within the warmup window we anchor at 1 (not the first seq) and
	// let NACK recover the early gap — otherwise the genuinely-first payloads (e.g.
	// the SOCKS MsgConnect, which has no app-level resend) sit below the cursor,
	// never get NACK'd, and are lost forever -> connections hang. A first seq above
	// this window means a mid-stream join (no early frames are owed to us), so we
	// anchor to it directly and the skip-timeout self-heals any false gap.
	arqWarmupSlack = 64
)

func (t *VP8DataTunnel) Reconfigure(fps, batch int) {
	if fps <= 0 && batch <= 0 {
		return
	}
	t.cfgMu.Lock()
	changed := false
	if fps > 0 && t.fps != fps {
		t.fps = fps
		changed = true
	}
	if batch > 0 && t.batch != batch {
		t.batch = batch
		changed = true
	}
	newFPS, newBatch := t.fps, t.batch
	t.cfgMu.Unlock()
	if !changed {
		return
	}
	t.logFn("vp8tunnel: reconfigure fps=%d batch=%d", newFPS, newBatch)
	select {
	case t.cfgChan <- struct{}{}:
	default:
	}
}

func (t *VP8DataTunnel) FPS() int {
	t.cfgMu.Lock()
	defer t.cfgMu.Unlock()
	return t.fps
}

func (t *VP8DataTunnel) Batch() int {
	t.cfgMu.Lock()
	defer t.cfgMu.Unlock()
	return t.batch
}

func (t *VP8DataTunnel) SendData(data []byte) {
	if len(data) == 0 {
		return
	}
	// Multi-client: route plain SendData through the mux writer addressed to
	// dest=0 (broadcast). The joiner side uses SendData and has no specific
	// destination - the single creator routes incoming frames by SENDER epoch,
	// so dest=0 is fine. Without this the mux writer (which drains only
	// muxQueue) would never send the joiner's data.
	if carrierMux && t.muxPeers != nil {
		t.SendDataTo(0, data)
		return
	}
	if carrierPCARQ {
		t.pcEnqueue(data)
		return
	}
	select {
	case t.sendQueue <- data:
	case <-t.stopCh:
	}
}

// SendDataTo queues relay bytes addressed to a specific peer epoch
// (multi-client mux) onto that peer's bounded queue. The carrier writer
// round-robins peer queues and tags each emitted frame with the dest-epoch so
// only that peer's joiner processes it. Falls back to the plain sendQueue when
// mux is off. A full per-peer channel blocks here, backpressuring that peer's
// upstream read (TCP flow control) instead of dropping bytes.
func (t *VP8DataTunnel) SendDataTo(epoch uint32, data []byte) {
	if len(data) == 0 {
		return
	}
	if !carrierMux || t.muxPeers == nil {
		t.SendData(data)
		return
	}
	t.muxMu.Lock()
	ch := t.muxPeers[epoch]
	if ch == nil {
		ch = make(chan []byte, sendQueueDepth)
		t.muxPeers[epoch] = ch
		t.muxOrder = append(t.muxOrder, epoch)
	}
	t.muxMu.Unlock()
	cp := make([]byte, len(data))
	copy(cp, data)
	select {
	case ch <- cp:
	case <-t.stopCh:
	}
}

// nextMuxFrame round-robins the per-peer queues and returns the next peer's
// bytes (up to maxFrameData) plus its dest-epoch. Fair: each call advances to
// the next peer with pending data, so no peer can monopolize the carrier.
func (t *VP8DataTunnel) nextMuxFrame(maxFrameData int) (uint32, []byte) {
	t.muxMu.Lock()
	defer t.muxMu.Unlock()
	n := len(t.muxOrder)
	for i := 0; i < n; i++ {
		t.muxRR = (t.muxRR + 1) % n
		ep := t.muxOrder[t.muxRR]
		ch := t.muxPeers[ep]
		if ch == nil || len(ch) == 0 {
			continue
		}
		var buf []byte
		for len(buf) < maxFrameData {
			select {
			case d := <-ch:
				buf = append(buf, d...)
			default:
				goto done
			}
		}
	done:
		if len(buf) > 0 {
			return ep, buf
		}
	}
	return 0, nil
}

func (t *VP8DataTunnel) Start(fps, batch int) {
	t.cfgMu.Lock()
	if fps > 0 {
		t.fps = fps
	}
	if batch > 0 {
		t.batch = batch
	}
	t.cfgMu.Unlock()
	if !t.running.CompareAndSwap(false, true) {
		return
	}
	go t.writerLoop()
}

// markStopped permits a reliability failure to commit its stop decision under
// its state lock, while callbacks remain outside that lock.
func (t *VP8DataTunnel) markStopped() bool {
	if !t.running.CompareAndSwap(true, false) {
		return false
	}
	t.stopOnce.Do(func() { close(t.stopCh) })
	return true
}

func (t *VP8DataTunnel) Stop() {
	if !t.markStopped() {
		return
	}
	if t.OnClose != nil {
		t.OnClose()
	}
}

func (t *VP8DataTunnel) currentIntervals() (sampleInterval time.Duration, keepaliveEvery, fps, batch int) {
	t.cfgMu.Lock()
	fps = t.fps
	batch = t.batch
	t.cfgMu.Unlock()

	frameInterval := time.Second / time.Duration(fps)
	sampleInterval = frameInterval
	if batch > 1 {
		sampleInterval = frameInterval / time.Duration(batch)
	}
	if sampleInterval <= 0 {
		sampleInterval = time.Millisecond
	}

	keepaliveEvery = int(keepaliveIdlePeriod / sampleInterval)
	if keepaliveEvery < 1 {
		keepaliveEvery = 1
	}
	return
}

// carrierWriterLoop emits one valid-VP8 carrier frame per video-frame interval
// (steady ~24fps, like a real encoder) so the SFU keeps the forward channel
// open and allocates full bitrate. Each frame batches as many queued relay
// messages as fit (the relay protocol's DecodeFrames splits them on receive),
// capped so the send rate stays under the SFU forward ceiling. Empty ticks
// emit a keepalive frame to sustain the stream.
func (t *VP8DataTunnel) carrierWriterLoop() {
	const fps = 24
	// Per-frame data cap = target kbit/s / 8 / fps. Tunable via WLB_CARRIER_KBPS
	// (default 2800) so the SFU forward rate can be tuned with just an env +
	// restart, no rebuild. Keep under the SFU per-stream forward ceiling (~3
	// Mbit) or the stream overshoots and the SFU throttles it like a flood.
	maxKbps := 2800
	if v := os.Getenv("WLB_CARRIER_KBPS"); v != "" {
		if k, err := strconv.Atoi(v); err == nil && k > 0 {
			maxKbps = k
		}
	}
	t.maxRateKbps = maxKbps
	if carrierPCARQ {
		t.pcWriterLoop(maxKbps)
		return
	}
	// Fixed mode: maxFrameData from maxKbps. Adaptive mode: start conservative
	// (3 Mbit) and let AIMD ramp dynRateKbps up to maxKbps based on peer reports.
	frameDataFor := func(kbps int) int {
		d := kbps * 1000 / 8 / fps
		if d < 256 {
			d = 256
		}
		return d
	}
	maxFrameData := frameDataFor(maxKbps)
	if carrierAdapt || carrierARQ || carrierPCARQ {
		start := 6000
		if start > maxKbps {
			start = maxKbps
		}
		t.dynRateKbps.Store(int64(start))
	}
	if carrierPCARQ {
		go t.pcNackLoop()         // receiver: per-conn NACK gaps
		go t.arqRateLoop(maxKbps) // sender: NACK-volume AIMD (shared congestion signal)
	} else if carrierARQ {
		go t.arqNackLoop()        // receiver: NACK gaps
		go t.arqRateLoop(maxKbps) // sender: NACK-volume AIMD
	} else if carrierAdapt {
		go t.feedbackLoop()
	}
	interval := time.Second / fps
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	t.logFn("vp8tunnel: carrier writer started fps=%d maxKbps=%d adapt=%v arq=%v", fps, maxKbps, carrierAdapt, carrierARQ)
	for {
		select {
		case <-t.stopCh:
			return
		case <-t.cfgChan:
			// fixed cadence in carrier mode; ignore reconfigure
		case <-ticker.C:
			if carrierAdapt || carrierARQ || carrierPCARQ {
				if dyn := t.dynRateKbps.Load(); dyn > 0 {
					maxFrameData = frameDataFor(int(dyn))
				}
			}
			// ARQ: send one priority control/retransmit frame this tick.
			if carrierARQ || carrierPCARQ {
				select {
				case cp := <-t.ctrlQueue:
					if cs := t.obf.EncodeData(cp); cs != nil {
						t.tracks[0].WriteSample(media.Sample{Data: cs, Duration: interval})
						t.sentFrames.Add(1)
					}
				default:
				}
			}
			// Multi-client: serve ONE peer per frame via fair round-robin over
			// per-peer queues (nextMuxFrame), so a heavy peer can't starve
			// others. Payload = [destEpoch:u32 || bytes]; keepalive when idle.
			// Own send path (skips the plain sendQueue drain) via continue.
			if carrierMux {
				destEp, mbuf := t.nextMuxFrame(maxFrameData)
				var sample []byte
				if len(mbuf) > 0 {
					t.sentData.Add(uint64(len(mbuf)))
					payload := make([]byte, 4+len(mbuf))
					binary.BigEndian.PutUint32(payload[0:4], destEp)
					copy(payload[4:], mbuf)
					sample = t.obf.EncodeData(payload)
				} else {
					sample = t.obf.EncodeKeepalive()
				}
				if sample == nil {
					continue
				}
				if err := t.tracks[0].WriteSample(media.Sample{Data: sample, Duration: interval}); err != nil {
					t.logFn("vp8tunnel: carrier WriteSample(mux): %v", err)
					continue
				}
				n := t.sentFrames.Add(1)
				if carrierVerboseFrames && (n <= 5 || n%200 == 0) {
					t.logFn("vp8tunnel: carrier MUX frame #%d dest=0x%08x dataLen=%d", n, destEp, len(mbuf))
				}
				continue
			}
			var sample []byte
			if carrierPCARQ {
				// Per-conn ARQ: drain WHOLE relay-frames (don't concatenate) so each
				// keeps its connID boundary, then wrap each with its own per-conn seq.
				var frames [][]byte
				total := 0
			pcdrain:
				for total < maxFrameData {
					select {
					case d := <-t.sendQueue:
						frames = append(frames, d)
						total += len(d)
					default:
						break pcdrain
					}
				}
				if len(frames) > 0 {
					t.sentData.Add(uint64(total))
					if payload := t.pcWrapFrames(frames); payload != nil {
						sample = t.obf.EncodeData(payload)
					}
				}
				if sample == nil {
					sample = t.obf.EncodeKeepalive()
				}
			} else {
				var buf []byte
			drain:
				for len(buf) < maxFrameData {
					select {
					case d := <-t.sendQueue:
						buf = append(buf, d...)
					default:
						break drain
					}
				}
				if len(buf) > 0 {
					t.sentData.Add(uint64(len(buf)))
					payload := buf
					if carrierARQ {
						payload = t.arqWrapData(buf)
					} else if carrierSeqProbe {
						seq := t.probeSendSeq.Add(1)
						p := make([]byte, 4+len(buf))
						binary.BigEndian.PutUint32(p[0:4], seq)
						copy(p[4:], buf)
						payload = p
					}
					sample = t.obf.EncodeData(payload)
				} else {
					sample = t.obf.EncodeKeepalive()
				}
			}
			if sample == nil {
				continue
			}
			if err := t.tracks[0].WriteSample(media.Sample{Data: sample, Duration: interval}); err != nil {
				t.logFn("vp8tunnel: carrier WriteSample: %v", err)
				continue
			}
			n := t.sentFrames.Add(1)
			if carrierVerboseFrames && (n <= 5 || n%200 == 0) {
				t.logFn("vp8tunnel: carrier frame #%d sample=%d", n, len(sample))
			}
		}
	}
}

func (t *VP8DataTunnel) writerLoop() {
	if carrierMode {
		t.carrierWriterLoop()
		return
	}
	// GCC-style smooth pacing on DATA frames. The default flooding behaviour
	// (one chunk/track every ~1.4ms tick) pushes ~6.5 Mbps into the Yandex
	// SFU, which triggers loss and backs the receiver estimate down to ~1.1
	// Mbps. A real Telemost client paces smoothly and sustains ~2-2.5 Mbps.
	// WLB_PUBLISH_KBPS sets a target bitrate (kbit/s) for the DATA stream;
	// 0 (default) keeps the original flood. Keepalives are NOT paced.
	//
	// Token bucket: paceTokens accrues at rateBps bytes/s (capped at paceBurst
	// to bound burstiness), and each DATA sample costs len(sample) tokens. A
	// frame that can't be afforded this tick is held in `pending` (NOT pushed
	// back to sendQueue) so ordering is preserved exactly for the next tick.
	var rateBps float64
	if v := os.Getenv("WLB_PUBLISH_KBPS"); v != "" {
		if kbps, err := strconv.Atoi(v); err == nil && kbps > 0 {
			rateBps = float64(kbps) * 125.0 // kbit/s -> bytes/s
		}
	}
	const paceBurst = 64 * 1024 // max accrued tokens (bytes)
	var paceTokens float64
	var paceLast time.Time
	var pending []byte // a dequeued DATA frame held for pacing across ticks
	if rateBps > 0 {
		t.logFn("vp8tunnel: pacing ENABLED target=%d kbit/s (%.0f B/s) burst=%dB",
			int(rateBps*8/1000), rateBps, paceBurst)
	}

	for {
		sampleInterval, keepaliveEvery, fps, batch := t.currentIntervals()
		t.logFn("vp8tunnel: writer (re)started fps=%d batch=%d sampleInterval=%s keepaliveEvery=%d",
			fps, batch, sampleInterval, keepaliveEvery)

		// Dual-rate ticker: fast when traffic flowing, slow when idle.
		// Saves significant CPU on phones running multiple librelay
		// instances when there's no active payload.
		fastTicker := time.NewTicker(sampleInterval)
		slowTicker := time.NewTicker(keepaliveIdlePeriod)
		slow := false
		idleTicks := 0
		consecutiveIdle := 0
		reconfigure := false

		tickerC := func() <-chan time.Time {
			if slow {
				return slowTicker.C
			}
			return fastTicker.C
		}

		// pickTrack routes each frame to a specific publisher track based
		// on the relay-protocol connID embedded in the data (bytes 4-8 of
		// the frame). Same connID always lands on same track — preserves
		// TCP byte ordering. Different connIDs hash to different tracks
		// to parallelise across the Yandex SFU per-stream cap.
		pickTrack := func(data []byte) int {
			if len(t.tracks) <= 1 || len(data) < 8 {
				return 0
			}
			connID := binary.BigEndian.Uint32(data[4:8])
			return int(connID % uint32(len(t.tracks)))
		}

		for !reconfigure {
			select {
			case <-t.stopCh:
				fastTicker.Stop()
				slowTicker.Stop()
				return
			case <-t.cfgChan:
				reconfigure = true
			case <-tickerC():
				// Live GCC estimate (publisher congestion control) overrides
				// the fixed WLB_PUBLISH_KBPS rate when present. This closes the
				// loop: writer paces to the SFU-revealed available bandwidth.
				if g := common.GCCTargetBps.Load(); g > 0 {
					rateBps = float64(g) / 8.0
				}
				// Refill the pacing token bucket once per tick. No-op when
				// rateBps==0 (flooding mode), so flood behaviour is unchanged.
				if rateBps > 0 {
					now := time.Now()
					if !paceLast.IsZero() {
						paceTokens += now.Sub(paceLast).Seconds() * rateBps
						if paceTokens > paceBurst {
							paceTokens = paceBurst
						}
					}
					paceLast = now
				}

				// Try to drain up to len(tracks) chunks this tick, one
				// per track (sticky-by-connID). Sample interval is short
				// (~1.4ms), so each tick effectively becomes a fan-out.
				wrotePerTrack := make([]bool, len(t.tracks))
				gotData := false
				for i := 0; i < len(t.tracks); i++ {
					// Prefer a frame held from a previous tick (pacing or
					// slot collision); only dequeue fresh when none is held.
					// Holding in `pending` (not re-queueing) preserves order.
					var data []byte
					if pending != nil {
						data = pending
					} else {
						select {
						case data = <-t.sendQueue:
						default:
							goto doneFan
						}
					}
					idx := pickTrack(data)
					if wrotePerTrack[idx] {
						// Slot taken this tick — hold for the next tick.
						pending = data
						goto doneFan
					}
					sample := t.obf.EncodeData(data)
					if sample == nil {
						pending = nil // drop malformed, advance past it
						continue
					}
					// Pacing gate: can't afford this sample yet → hold it and
					// let tokens accrue. Oversized samples (> burst cap) go out
					// regardless to avoid a deadlock; the resulting token debt
					// throttles the frames that follow.
					if rateBps > 0 && paceTokens < float64(len(sample)) && float64(len(sample)) <= paceBurst {
						pending = data
						goto doneFan
					}
					if err := t.tracks[idx].WriteSample(media.Sample{Data: sample, Duration: sampleInterval}); err != nil {
						t.logFn("vp8tunnel: WriteSample error on track %d: %v", idx, err)
						pending = nil
						continue
					}
					pending = nil
					if rateBps > 0 {
						paceTokens -= float64(len(sample))
					}
					wrotePerTrack[idx] = true
					gotData = true
					n := t.sentFrames.Add(1)
					if carrierVerboseFrames && (n <= 5 || n%500 == 0) {
						t.logFn("vp8tunnel: sent frame #%d size=%d track=%d", n, len(sample), idx)
					}
				}
			doneFan:
				hasPending := pending != nil
				if !gotData && !hasPending {
					// Genuinely idle (no data sent, nothing held) — only first
					// track emits keepalive (avoid multiplying idle bw N×).
					if slow {
						sample := t.obf.EncodeKeepalive()
						if sample != nil {
							t.tracks[0].WriteSample(media.Sample{Data: sample, Duration: sampleInterval})
							n := t.sentFrames.Add(1)
							if n <= 5 || n%500 == 0 {
								t.logFn("vp8tunnel: sent frame #%d size=%d track=0 (keepalive)", n, len(sample))
							}
						}
					} else {
						idleTicks++
						if idleTicks >= keepaliveEvery {
							idleTicks = 0
							sample := t.obf.EncodeKeepalive()
							if sample != nil {
								t.tracks[0].WriteSample(media.Sample{Data: sample, Duration: sampleInterval})
								n := t.sentFrames.Add(1)
								if n <= 5 || n%500 == 0 {
									t.logFn("vp8tunnel: sent frame #%d size=%d track=0 (keepalive)", n, len(sample))
								}
							}
						}
					}
				}
				if gotData || hasPending {
					// Active (sent this tick, or holding a paced frame): keep
					// the fast ticker so tokens are spent as soon as they
					// accrue. Pacing throttle must NOT look like idleness.
					idleTicks = 0
					consecutiveIdle = 0
					if slow {
						slow = false
					}
				} else {
					consecutiveIdle++
					if !slow && consecutiveIdle > idleTickerSlowdown {
						slow = true
					}
				}
			}
		}
		fastTicker.Stop()
		slowTicker.Stop()
	}
}

func (t *VP8DataTunnel) HandleFrame(frame []byte) {
	na := t.recvAttempts.Add(1)
	// Test-only loss injection: drop 1 in testDropEvery incoming frames to
	// simulate RTP loss locally (validate ARQ recovery without the phone).
	if testDropEvery > 0 && na%uint64(testDropEvery) == 0 {
		return
	}
	res := t.obf.Decode(frame)
	if na <= 40 {
		t.logFn("vp8tunnel: RECV att=%d flen=%d hasFrame=%v selfEcho=%v keepalive=%v peerEp=0x%x plen=%d", na, len(frame), res.HasFrame, res.SelfEcho, res.Keepalive, res.PeerEpoch, len(res.Payload))
	}
	if !res.HasFrame {
		nf := t.recvFail.Add(1)
		if nf <= 12 || nf%200 == 0 {
			h := frame
			if len(h) > 10 {
				h = h[:10]
			}
			t.logFn("vp8tunnel: DECODE-FAIL #%d (attempt %d) frameLen=%d head=%x", nf, na, len(frame), h)
		}
		return
	}
	if res.SelfEcho {
		return
	}
	if res.PeerRestart {
		t.logFn("vp8tunnel: peer restart detected, new epoch=0x%08x", res.PeerEpoch)
	}
	if res.Keepalive || len(res.Payload) == 0 {
		return
	}
	n := t.recvFrames.Add(1)
	t.recvData.Add(uint64(len(res.Payload)))
	if carrierVerboseFrames && (n <= 5 || n%500 == 0) {
		t.logFn("vp8tunnel: recv frame #%d size=%d", n, len(res.Payload))
	}
	t.DeliverPayload(res.Payload)
}

// DeliverPayload routes a decoded carrier payload: through the ARQ reorder
// layer when enabled, else straight to OnData. Used by BOTH receive paths -
// the joiner's HandleFrame and the creator's SFURelay.readTrack (which decodes
// itself), so ARQ unwrapping happens on both sides.
func (t *VP8DataTunnel) DeliverPayload(payload []byte) {
	if carrierMux {
		// Multi-client frame: [destEpoch:u32 || relay bytes]. Joiner side only
		// processes frames addressed to its own epoch (dest==0 = broadcast).
		// The creator routes by SENDER epoch instead and uses DeliverFromPeer,
		// not this path.
		if len(payload) < 4 {
			return
		}
		dest := binary.BigEndian.Uint32(payload[0:4])
		body := payload[4:]
		// Accept ONLY frames addressed to our own epoch. The SFU broadcasts
		// every participant's video to all, so a joiner also receives OTHER
		// joiners' upstream frames (which they send with dest=0). Treating
		// dest=0 as broadcast would inject another peer's bytes into our TCP
		// stream and corrupt it - so dest=0 (and any other peer's dest) is
		// dropped. The creator always addresses downstream to a specific peer
		// epoch, so legitimate frames for us have dest==our localEpoch.
		if t.obf == nil || dest != t.obf.LocalEpoch() {
			return
		}
		if t.OnData != nil {
			t.OnData(body)
		}
		return
	}
	if carrierPCARQ {
		t.pcHandle(payload)
		return
	}
	if carrierARQ {
		t.arqHandle(payload)
		return
	}
	if carrierSeqProbe && len(payload) >= 4 {
		seq := binary.BigEndian.Uint32(payload[0:4])
		t.probeAccount(seq)
		payload = payload[4:]
	}
	if t.OnData != nil {
		t.OnData(payload)
	}
}

// DeliverFromPeer is the CREATOR-side multi-client receive entry: it strips the
// dest-epoch (addressed to the creator) and routes the relay bytes to the
// per-peer bridge via OnDataMux, keyed by the SENDER epoch. Wired in the
// creator's readTrack demux (replaces the single-peer DeliverPayload + phantom
// lock when WLB_CARRIER_MUX is on). Falls back to OnData if OnDataMux unset.
func (t *VP8DataTunnel) DeliverFromPeer(senderEpoch uint32, payload []byte) {
	body := payload
	if carrierMux && len(payload) >= 4 {
		body = payload[4:] // strip dest-epoch (it's the creator's own epoch)
	}
	if t.OnDataMux != nil {
		t.OnDataMux(senderEpoch, body)
		return
	}
	if t.OnData != nil {
		t.OnData(body)
	}
}

// probeAccount (observe-only) tallies in-order/gap/reorder/dup against the
// expected seq and logs a rolling summary. It NEVER reorders or drops -
// delivery is unchanged - so it measures real reliability at zero cost.
func (t *VP8DataTunnel) probeAccount(seq uint32) {
	t.probeRecvMu.Lock()
	if t.probeExpect == 0 && t.probeMaxSeen == 0 {
		t.probeExpect = seq + 1
		t.probeMaxSeen = seq
		t.probeInOrder++
		t.probeRecvMu.Unlock()
		return
	}
	switch {
	case seq == t.probeExpect:
		t.probeInOrder++
		t.probeExpect = seq + 1
	case seq > t.probeExpect:
		t.probeGap += uint64(seq - t.probeExpect) // seqs skipped at the head
		t.probeExpect = seq + 1
	default: // seq < probeExpect: a missing one showed up late, or a dup
		if seq <= t.probeMaxSeen {
			t.probeReorder++
			if t.probeGap > 0 {
				t.probeGap-- // a "gap" seq actually arrived (reorder, not loss)
			}
		} else {
			t.probeDup++
		}
	}
	if seq > t.probeMaxSeen {
		t.probeMaxSeen = seq
	}
	io, gap, ro, dup := t.probeInOrder, t.probeGap, t.probeReorder, t.probeDup
	t.probeRecvMu.Unlock()
	total := io + gap
	if total > 0 && (io+ro+dup)%100 == 0 {
		loss := float64(gap) * 100 / float64(total)
		t.logFn("vp8tunnel: SEQPROBE inorder=%d gap_lost=%d reorder=%d dup=%d est_loss=%.3f%%", io, gap, ro, dup, loss)
	}
}

// feedbackLoop (carrier-adapt) reports our measured RECEIVE rate to the peer
// once per second so the peer's AIMD can size its send rate to the link.
func (t *VP8DataTunnel) feedbackLoop() {
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	var lastRecv uint64
	lastT := time.Now()
	for {
		select {
		case <-t.stopCh:
			return
		case now := <-tick.C:
			r := t.recvData.Load()
			dt := now.Sub(lastT).Seconds()
			lastT = now
			var bps uint64
			if dt > 0 {
				bps = uint64(float64(r-lastRecv) / dt)
			}
			lastRecv = r
			t.SendData(EncodeRateReport(uint32(min64(bps, 0xFFFFFFFF))))
		}
	}
}

func min64(a, b uint64) uint64 {
	if a < b {
		return a
	}
	return b
}

// OnRateReport (carrier-adapt) applies AIMD to our send rate using the peer's
// reported receive rate vs how much we actually sent in the same interval.
// Clean delivery (>=95%) -> additive increase; loss (<90%) -> multiplicative
// decrease. Bounded by [1000, maxRateKbps].
func (t *VP8DataTunnel) OnRateReport(peerRecvBps uint32) {
	if !carrierAdapt {
		return
	}
	now := time.Now()
	sent := t.sentData.Load()
	if t.lastReportAt.IsZero() {
		t.lastReportAt = now
		t.lastSentSnap = sent
		return
	}
	dt := now.Sub(t.lastReportAt).Seconds()
	t.lastReportAt = now
	prevSent := t.lastSentSnap
	t.lastSentSnap = sent
	if dt <= 0 {
		return
	}
	sentBps := float64(sent-prevSent) / dt
	if sentBps < 100000 { // <0.1 Mbit sent: not enough signal, hold
		return
	}
	cur := t.dynRateKbps.Load()
	if cur <= 0 {
		cur = 3000
	}
	ratio := float64(peerRecvBps) / sentBps
	switch {
	case ratio >= 0.95:
		cur += 1000 // additive increase ~1 Mbit/s
	case ratio < 0.90:
		cur = int64(float64(cur) * 0.75) // multiplicative decrease
	}
	if cur > int64(t.maxRateKbps) {
		cur = int64(t.maxRateKbps)
	}
	if cur < 1000 {
		cur = 1000
	}
	t.dynRateKbps.Store(cur)
	t.logFn("vp8tunnel: AIMD peerRecv=%.0fkbit sent=%.0fkbit ratio=%.2f -> dyn=%dkbps", float64(peerRecvBps)*8/1000, sentBps*8/1000, ratio, cur)
}

// ---- ARQ reliable layer ----

// arqWrapData assigns a seq to a data frame, buffers it for retransmit, and
// returns the on-wire ARQ payload [arqData][seq][relay bytes].
func (t *VP8DataTunnel) arqWrapData(buf []byte) []byte {
	t.arqSendMu.Lock()
	t.arqSendSeq++
	seq := t.arqSendSeq
	cp := make([]byte, len(buf))
	copy(cp, buf)
	t.arqSendBuf[seq] = cp
	t.arqSendKeys = append(t.arqSendKeys, seq)
	for len(t.arqSendKeys) > arqSendBufMax {
		delete(t.arqSendBuf, t.arqSendKeys[0])
		t.arqSendKeys = t.arqSendKeys[1:]
	}
	t.arqSendMu.Unlock()
	out := make([]byte, 5+len(buf))
	out[0] = arqData
	binary.BigEndian.PutUint32(out[1:5], seq)
	copy(out[5:], buf)
	return out
}

// arqHandle routes a decoded ARQ payload (receiver side for data, sender side
// for NACK).
func (t *VP8DataTunnel) arqHandle(payload []byte) {
	if len(payload) < 1 {
		return
	}
	if a := t.recvFrames.Load(); a <= 15 {
		var seq uint32
		if len(payload) >= 5 {
			seq = binary.BigEndian.Uint32(payload[1:5])
		}
		t.logFn("vp8tunnel: ARQ-DBG handle type=0x%02x seq=%d plen=%d expected=%d", payload[0], seq, len(payload), t.arqExpected)
	}
	switch payload[0] {
	case arqNack:
		t.arqOnNack(payload)
	case arqData:
		if len(payload) < 5 {
			return
		}
		seq := binary.BigEndian.Uint32(payload[1:5])
		t.arqRecvData(seq, payload[5:])
	}
}

// arqOnNack (sender): re-queue buffered frames for the requested seqs.
func (t *VP8DataTunnel) arqOnNack(payload []byte) {
	if len(payload) < 3 {
		return
	}
	count := int(binary.BigEndian.Uint16(payload[1:3]))
	off := 3
	t.arqSendMu.Lock()
	var reqs [][]byte
	var unique uint64
	for i := 0; i < count && off+4 <= len(payload); i++ {
		seq := binary.BigEndian.Uint32(payload[off : off+4])
		off += 4
		if !t.arqNackWin[seq] { // count each lost seq once per AIMD window
			t.arqNackWin[seq] = true
			unique++
		}
		if buf, ok := t.arqSendBuf[seq]; ok {
			out := make([]byte, 5+len(buf))
			out[0] = arqData
			binary.BigEndian.PutUint32(out[1:5], seq)
			copy(out[5:], buf)
			reqs = append(reqs, out)
		}
	}
	t.arqSendMu.Unlock()
	t.arqNacked.Add(unique)
	for _, r := range reqs {
		select {
		case t.ctrlQueue <- r:
		default: // ctrlQueue full: drop, receiver will NACK again
		}
	}
}

// arqRecvData (receiver): in-order delivery with a reorder buffer.
func (t *VP8DataTunnel) arqRecvData(seq uint32, relay []byte) {
	select {
	case <-t.stopCh:
		return
	default:
	}
	t.arqRecvMu.Lock()
	if t.arqExpected == 0 && t.arqMaxSeen == 0 {
		// Anchor the receive cursor. A fresh stream starts at seq 1; if the first
		// DECODED seq is within the warmup window, the earlier seqs were dropped by
		// keyframe warmup (not absent) — anchor at 1 so NACK recovers them. Only a
		// first seq beyond the window is treated as a mid-stream join.
		if seq <= arqWarmupSlack {
			t.arqExpected = 1
		} else {
			t.arqExpected = seq
		}
	}
	if seq > t.arqMaxSeen {
		t.arqMaxSeen = seq
	}
	if seq < t.arqExpected {
		t.arqRecvMu.Unlock()
		return // duplicate / late retransmit
	}
	var deliver [][]byte
	if seq == t.arqExpected {
		deliver = append(deliver, append([]byte(nil), relay...))
		t.arqExpected++
		for {
			b, ok := t.arqReorder[t.arqExpected]
			if !ok {
				break
			}
			deliver = append(deliver, b)
			delete(t.arqReorder, t.arqExpected)
			t.arqExpected++
		}
		if t.arqExpected > t.arqMaxSeen {
			t.arqGapAt = time.Time{} // no gap at head
		} else {
			t.arqGapAt = time.Now() // a different head gap starts its own deadline
		}
	} else {
		if _, ok := t.arqReorder[seq]; !ok {
			cp := make([]byte, len(relay))
			copy(cp, relay)
			t.arqReorder[seq] = cp
		}
		if t.arqGapAt.IsZero() {
			t.arqGapAt = time.Now()
		}
	}
	t.arqRecvMu.Unlock()
	for _, d := range deliver {
		if t.OnData != nil {
			t.OnData(d)
		}
	}
}

// arqNackLoop requests missing data; expiry closes the tunnel, never skips bytes.
func (t *VP8DataTunnel) arqNackLoop() {
	tick := time.NewTicker(60 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-t.stopCh:
			return
		case <-tick.C:
			t.arqRecvMu.Lock()
			if t.arqExpected > t.arqMaxSeen || t.arqExpected == 0 {
				t.arqRecvMu.Unlock()
				continue
			}
			// A missing TCP payload cannot be skipped: that corrupts the byte stream.
			// Bound recovery time and memory, then fail the tunnel explicitly.
			bufferedBytes := 0
			for _, b := range t.arqReorder {
				bufferedBytes += len(b)
			}
			if (!t.arqGapAt.IsZero() && time.Since(t.arqGapAt) > arqSkipAfter) || len(t.arqReorder) > arqSendBufMax || bufferedBytes > 8*1024*1024 {
				t.arqRecvMu.Unlock()
				t.logFn("carrier: reliable delivery expired; closing tunnel without skipping bytes")
				t.Stop()
				return
			}
			// Collect missing seqs in [expected, maxSeen].
			var miss []uint32
			for s := t.arqExpected; s <= t.arqMaxSeen && len(miss) < arqMaxNack; s++ {
				if _, ok := t.arqReorder[s]; !ok {
					miss = append(miss, s)
				}
			}
			t.arqRecvMu.Unlock()
			if len(miss) == 0 {
				continue
			}
			nk := make([]byte, 3+4*len(miss))
			nk[0] = arqNack
			binary.BigEndian.PutUint16(nk[1:3], uint16(len(miss)))
			for i, s := range miss {
				binary.BigEndian.PutUint32(nk[3+4*i:], s)
			}
			select {
			case t.ctrlQueue <- nk:
			default:
			}
		}
	}
}

// arqRateLoop (sender): NACK-volume-driven AIMD. Low NACK ratio -> ramp up,
// high -> back off; settles just under the link. ARQ recovers the probe loss.
func (t *VP8DataTunnel) arqRateLoop(maxKbps int) {
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	var lastSentFrames uint64
	var lastSentUnits uint64
	var lastAckedUnits uint64
	var lossEWMA float64 // smoothed loss to avoid overreacting to single spikes
	var recovery pcRecoveryRate
	useRecovery := os.Getenv("WLB_PC_RATE_MODE") == "recovery" || os.Getenv("WLB_PC_RATE_MODE") == "probe"
	// Floor: keep each room reasonably high; 6 rooms x 4000 = 24 Mbit << ~80
	// link, so the floor is safe and stops the multi-flow death-spiral that
	// dropped the aggregate to ~10. ARQ recovers the residual loss, so we only
	// back off on SUSTAINED loss, gently.
	floorKbps := int64(4000)
	if int64(maxKbps) < floorKbps {
		floorKbps = int64(maxKbps)
	}
	for {
		select {
		case <-t.stopCh:
			return
		case <-tick.C:
			sent := t.sentFrames.Load()
			df := sent - lastSentFrames
			lastSentFrames = sent
			nacks := t.arqNacked.Swap(0)
			t.arqSendMu.Lock()
			t.arqNackWin = make(map[uint32]bool)
			t.arqSendMu.Unlock()
			if carrierPCARQ {
				// per-conn NACKs are counted in relay-FRAMES (many per carrier
				// frame), so the loss denominator must be units sent, not carrier
				// frames - otherwise loss% reads many-x too high and the AIMD floors.
				units := t.pcSentUnits.Load()
				df = units - lastSentUnits
				lastSentUnits = units
				acked := t.pcAckedUnits.Load()
				da := acked - lastAckedUnits
				lastAckedUnits = acked
				cur, ewma := pcRateStep(t.dynRateKbps.Load(), int64(maxKbps), df, nacks, da, lossEWMA)
				if useRecovery {
					t.pcSendMu.Lock()
					rto := t.pcRTOLocked()
					t.pcSendMu.Unlock()
					cur = recovery.step(time.Now(), t.dynRateKbps.Load(), int64(maxKbps), df, nacks, da, rto)
					ewma = recovery.ewma
					if recovery.reason != "collect" && recovery.lastSent+recovery.lastAcked+recovery.lastNacked > 0 {
						t.logFn("carrier: WB rate cohort=%.1fs sent=%d acked=%d nacked=%d reason=%s target=%dkbps", recovery.lastSeconds, recovery.lastSent, recovery.lastAcked, recovery.lastNacked, recovery.reason, cur)
					}
				}
				lossEWMA = ewma
				t.dynRateKbps.Store(cur)
				if df > 0 || nacks > 0 || da > 0 {
					t.logFn("vp8tunnel: ARQ-AIMD fresh-nacks=%d sent=%d acked=%d ewma=%.1f%% -> dyn=%dkbps", nacks, df, da, lossEWMA*100, cur)
				}
				continue
			}
			if df < 5 {
				continue
			}
			cur := t.dynRateKbps.Load()
			if cur <= 0 {
				cur = 6000
			}
			lossPct := float64(nacks) / float64(df)
			lossEWMA = 0.5*lossEWMA + 0.5*lossPct
			switch {
			case lossEWMA < 0.03:
				cur += 2000 // clean enough -> ramp up fast
			case lossEWMA > 0.10:
				cur = int64(float64(cur) * 0.90) // sustained loss -> gentle back off
			}
			if cur > int64(maxKbps) {
				cur = int64(maxKbps)
			}
			if cur < floorKbps {
				cur = floorKbps
			}
			t.dynRateKbps.Store(cur)
			t.logFn("vp8tunnel: ARQ-AIMD nacks=%d/%d loss=%.1f%% ewma=%.1f%% -> dyn=%dkbps", nacks, df, lossPct*100, lossEWMA*100, cur)
		}
	}
}

// ---- hot-reload support ----

// ResetState clears the carrier's *working* state in-place for a hot-reload:
// the operator/app tells a LIVE bot to reset its tunnel WITHOUT leaving and
// rejoining the call. The PeerConnection, WebSocket session and obfuscator
// (aead key + local/peer epoch) stay alive, so the SFU sees the same
// participant — no leave/join churn, which is what triggers Yandex anti-abuse
// when many bots restart at once.
//
// Cleared: pending send queues (bytes of now-closed conns) and the ARQ
// retransmit/reorder buffers (stale once conns are torn down).
// PRESERVED: arqSendSeq / arqExpected / arqMaxSeen — the ARQ sequence space
// must stay monotonic across a reload, otherwise the peer (whose counters we
// can't touch) desyncs and every subsequent frame looks like a gap. A stuck
// head gap is already self-healed by arqNackLoop's arqSkipAfter timeout, so we
// don't need to reset the receive cursor here.
func (t *VP8DataTunnel) ResetState() {
	drain := func(ch chan []byte) int {
		n := 0
		for {
			select {
			case <-ch:
				n++
			default:
				return n
			}
		}
	}
	dq := drain(t.sendQueue)
	cq := drain(t.ctrlQueue)
	// Per-peer mux queues (multi-client): drain each so a closed peer's bytes
	// don't linger. Keys stay (peers are still present in the call).
	mq := 0
	t.muxMu.Lock()
	for _, ch := range t.muxPeers {
		mq += drain(ch)
	}
	t.muxMu.Unlock()
	t.arqSendMu.Lock()
	t.arqSendBuf = make(map[uint32][]byte)
	t.arqSendKeys = nil
	t.arqNackWin = make(map[uint32]bool)
	t.arqSendMu.Unlock()
	t.arqRecvMu.Lock()
	t.arqReorder = make(map[uint32][]byte)
	t.arqGapAt = time.Time{}
	t.arqRecvMu.Unlock()
	// Per-conn ARQ: the reload tears down all conns and connIDs are never reused,
	// so every per-conn stream is now dead — clear send+recv state entirely.
	t.pcRecvMu.Lock()
	t.pcResetSend()
	t.pcResetReceiveLocked()
	t.pcExpected = make(map[uint32]uint32)
	t.pcReorder = make(map[uint32]map[uint32][]byte)
	t.pcMaxSeen = make(map[uint32]uint32)
	t.pcGapAt = make(map[uint32]time.Time)
	t.pcRecvMu.Unlock()
	t.logFn("vp8tunnel: ResetState drained send=%d ctrl=%d mux=%d, cleared ARQ buffers (epoch+seq preserved)", dq, cq, mq)
}

// ResetPeerRestart re-anchors the RECEIVE side after the peer PROCESS restarted
// (detected at the SFU/track layer, not the per-frame PeerRestart flag). A
// restarted peer comes back with a fresh random obfuscator epoch AND its ARQ
// send seq restarts at 1, while our receive cursor (arqExpected/arqMaxSeen) is
// still high from the dead session — so every new frame looks like seq<expected
// and is dropped forever, and the NACK loop stays silent because
// arqExpected>arqMaxSeen. We clear the cursor (next decoded frame re-anchors via
// the warmup logic), drop the now-stale reorder buffer, reset the observe-only
// seq probe, and release the obfuscator's peer-lock so the new epoch is adopted
// at once instead of being shadowed for lockIdleMs. The SEND side is left
// untouched: the restarted peer re-anchors to our still-monotonic send seq as a
// mid-stream join. Idempotent.
func (t *VP8DataTunnel) ResetPeerRestart() {
	t.arqRecvMu.Lock()
	t.arqExpected = 0
	t.arqMaxSeen = 0
	t.arqReorder = make(map[uint32][]byte)
	t.arqGapAt = time.Time{}
	t.arqRecvMu.Unlock()

	t.probeRecvMu.Lock()
	t.probeExpect = 0
	t.probeMaxSeen = 0
	t.probeRecvMu.Unlock()

	// All old relay sockets close when a peer process restarts. Its connIDs
	// may start at 1 again, so both per-connection sequence spaces must reset.
	t.pcRecvMu.Lock()
	t.pcResetSend()
	t.pcResetReceiveLocked()
	t.pcExpected = make(map[uint32]uint32)
	t.pcReorder = make(map[uint32]map[uint32][]byte)
	t.pcMaxSeen = make(map[uint32]uint32)
	t.pcGapAt = make(map[uint32]time.Time)
	t.pcRecvMu.Unlock()

	if t.obf != nil {
		t.obf.ResetPeerLock()
	}
	if t.logFn != nil {
		t.logFn("vp8tunnel: peer-restart reset (ARQ recv cursor + reorder + peer-lock cleared)")
	}
}

// Stats reports lifetime carrier counters for the /status control endpoint.
func (t *VP8DataTunnel) Stats() (sentFrames, recvFrames, recvFail uint64, dynKbps int64) {
	return t.sentFrames.Load(), t.recvFrames.Load(), t.recvFail.Load(), t.dynRateKbps.Load()
}
