package main

import (
	"errors"
	"fmt"
	"io"
	"log"
	"math/rand"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"github.com/pion/webrtc/v4/pkg/media/ivfreader"
	"github.com/pion/webrtc/v4/pkg/media/oggreader"
	"whitelist-bypass/relay/common"
	tmapi "whitelist-bypass/relay/telemost"
	"whitelist-bypass/relay/tunnel"
)

// peerLockIdleMs — sticky lock on the first peer that sends real data.
// If the locked peer goes silent for this long, the lock is released and
// the next non-keepalive frame from any peer takes over. 30s tolerates
// short Yandex SFU stalls; longer than that the joiner has likely died
// and we want to accept a fresh client.
const peerLockIdleMs = 30000

// muxEnabled (WLB_CARRIER_MUX): multi-client mode. readTrack then routes every
// peer's frames by SENDER epoch into per-peer bridges instead of phantom-locking
// to a single peer.
var muxEnabled = os.Getenv("WLB_CARRIER_MUX") != ""

type SFURelay struct {
	pubPC        *webrtc.PeerConnection
	subPC        *webrtc.PeerConnection
	pubRemoteSet bool
	subRemoteSet bool
	pubPending   []webrtc.ICECandidateInit
	subPending   []webrtc.ICECandidateInit
	mu           sync.Mutex

	sampleTrack   *webrtc.TrackLocalStaticSample
	// Extra video tracks for multi-publisher bandwidth experiment. When
	// populated (WLB_EXTRA_VIDEO_TRACKS env var > 0), pubPC publishes N+1
	// total video m-lines; VP8DataTunnel can be reconfigured to round-robin
	// across them, multiplying per-room downstream cap (Yandex SFU caps
	// each stream at ~1.25 Mbps to subscribers).
	extraVideoTracks []*webrtc.TrackLocalStaticSample
	audioTrack    *webrtc.TrackLocalStaticSample
	tun           *tunnel.VP8DataTunnel
	obf           *tunnel.TunnelObfuscator
	OnConnected   func(*tunnel.VP8DataTunnel)
	OnPubReady    func()
	OnPeerRestart func()
	OnPubICE      func(*webrtc.ICECandidate)
	OnSubICE      func(*webrtc.ICECandidate)

	readBufSize int

	// Phantom-tolerant peer lock. Yandex SFU keeps ghost participants in
	// the room indefinitely; their video tracks feed into the same
	// obfuscator and ping-pong its peerEpoch, which used to fire
	// PeerRestart on every other frame and reset all active TCP/UDP
	// tunnels. We instead lock to the first peer that delivers real
	// payload (not keepalive) and silently drop frames from other epochs.
	lockedPeerEpoch atomic.Uint32
	lockedDataAtMs  atomic.Int64

	// Audio-decoy goroutine control. Reads a random .ogg/Opus file from
	// $WLB_AUDIO_DIR on first pub-PC connect and streams its samples on
	// the publisher audio track to make the bot look like a normal call
	// participant (anti-abuse signal to Yandex SFU).
	audioStopCh     chan struct{}
	audioStarted    atomic.Bool
	audioFrameCount uint64
}

func (r *SFURelay) SetObfuscator(o *tunnel.TunnelObfuscator) { r.obf = o }

func NewSFURelay() *SFURelay {
	return &SFURelay{}
}

// pumpValidVP8 sends syntactically-valid VP8 carrier frames: a real
// [tag + first-partition] prefix (precomputed by libvpx, loaded from dir)
// followed by arbitrary token-partition bytes. A VP8 decoder decodes these to
// garbage WITHOUT error, so the SFU should not PLI and should forward at the
// sent bitrate. Paces ~3 Mbit @ 24fps, keyframe prefix every 48 frames.
func pumpValidVP8(track *webrtc.TrackLocalStaticSample, dir string) {
	kf, err := os.ReadFile(filepath.Join(dir, "vp8_kf_prefix.bin"))
	if err != nil {
		log.Printf("[validvp8] read kf prefix: %v", err)
		return
	}
	inter, err := os.ReadFile(filepath.Join(dir, "vp8_inter_prefix.bin"))
	if err != nil {
		log.Printf("[validvp8] read inter prefix: %v", err)
		return
	}
	log.Printf("[validvp8] prefixes loaded kf=%dB inter=%dB", len(kf), len(inter))
	const dataPerFrame = 15000
	dur := time.Second / 24
	var seq uint64
	for {
		var frame []byte
		if seq%48 == 0 {
			frame = append(frame, kf...)
		} else {
			frame = append(frame, inter...)
		}
		d := make([]byte, dataPerFrame)
		rand.Read(d)
		frame = append(frame, d...)
		if err := track.WriteSample(media.Sample{Data: frame, Duration: dur}); err != nil {
			log.Printf("[validvp8] WriteSample: %v", err)
		}
		seq++
		time.Sleep(dur)
	}
}

// pumpVP8Data publishes a stream of DATA-CARRYING VP8 keyframes
// (AssembleKeyframeData) at a target bitrate. Diagnostic for WLB_VP8_DATA:
// measures whether the SFU forwards our valid data-coded frames at full
// rate (proving the data-in-real-VP8 channel) vs the 0.027 token-garbage.
func pumpVP8Data(track *webrtc.TrackLocalStaticSample) {
	w, h := 320, 180
	kbps := envIntDefault("WLB_CARRIER_KBPS", 2800)
	verifyData := os.Getenv("WLB_VERIFY_DATA") != ""
	log.Printf("[vp8data] publishing data-carrier keyframes %dx%d target=%dkbps", w, h, kbps)
	var seq uint64
	for {
		data := make([]byte, 8000)
		if verifyData {
			s := uint32(seq)
			data[0] = byte(s >> 24); data[1] = byte(s >> 16); data[2] = byte(s >> 8); data[3] = byte(s)
			for i := 4; i < len(data); i++ {
				data[i] = byte(s*31 + uint32(i)*131 + 7)
			}
		} else {
			rand.Read(data)
		}
		frame, err := tunnel.AssembleKeyframeData(w, h, data)
		if err != nil {
			log.Printf("[vp8data] assemble: %v", err)
			return
		}
		dur := time.Duration(float64(len(frame)) * 8 / float64(kbps*1000) * float64(time.Second))
		if dur <= 0 {
			dur = time.Second / 24
		}
		if werr := track.WriteSample(media.Sample{Data: frame, Duration: dur}); werr != nil {
			log.Printf("[vp8data] WriteSample: %v", werr)
		}
		seq++
		if seq%48 == 0 {
			log.Printf("[vp8data] sent %d frames (%dB each, dur=%v)", seq, len(frame), dur)
		}
		time.Sleep(dur)
	}
}

// pumpRealVideo publishes a real decodable VP8 clip (IVF) to the track in a
// loop, pacing at the frame rate. Diagnostic for WLB_REAL_VIDEO: lets us see
// the SFU forward allocation for genuine video vs our fake tunnel stream.
func pumpRealVideo(track *webrtc.TrackLocalStaticSample, path string) {
	for {
		f, err := os.Open(path)
		if err != nil {
			log.Printf("[realvid] open %s: %v", path, err)
			return
		}
		ivf, hdr, err := ivfreader.NewWith(f)
		if err != nil {
			log.Printf("[realvid] ivf parse: %v", err)
			f.Close()
			return
		}
		dur := time.Second / 24
		if hdr != nil && hdr.TimebaseDenominator > 0 && hdr.TimebaseNumerator > 0 {
			d := time.Duration(float64(time.Second) * float64(hdr.TimebaseNumerator) / float64(hdr.TimebaseDenominator))
			if d > 0 && d < time.Second {
				dur = d
			}
		}
		n := 0
		for {
			frame, _, perr := ivf.ParseNextFrame()
			if perr != nil {
				break
			}
			if werr := track.WriteSample(media.Sample{Data: frame, Duration: dur}); werr != nil {
				log.Printf("[realvid] WriteSample: %v", werr)
			}
			n++
			time.Sleep(dur)
		}
		f.Close()
		log.Printf("[realvid] looped %d frames (dur=%s), restarting", n, dur)
	}
}

// logIncomingRTCP drains and logs RTCP feedback the SFU sends to our video
// publisher. Reveals whether the SFU runs bandwidth estimation on our stream
// (transport-cc / REMB) and whether it requests keyframes (PLI/FIR). Diagnostic
// only, gated by WLB_RTCP_LOG.
func logIncomingRTCP(sender *webrtc.RTPSender) {
	if sender == nil {
		log.Printf("[rtcp] no video sender, logger off")
		return
	}
	buf := make([]byte, 1500)
	counts := map[string]int{}
	var rcvd, lost, pli int
	last := time.Now()
	for {
		n, _, err := sender.Read(buf)
		if err != nil {
			log.Printf("[rtcp] reader stopped: %v (counts=%v)", err, counts)
			return
		}
		pkts, err := rtcp.Unmarshal(buf[:n])
		if err != nil {
			continue
		}
		for _, p := range pkts {
			counts[fmt.Sprintf("%T", p)]++
			switch fb := p.(type) {
			case *rtcp.CCFeedbackReport:
				for _, rb := range fb.ReportBlocks {
					for _, mb := range rb.MetricBlocks {
						if mb.Received {
							rcvd++
						} else {
							lost++
						}
					}
				}
			case *rtcp.PictureLossIndication:
				pli++
			}
		}
		if time.Since(last) > 2*time.Second {
			tot := rcvd + lost
			lp := 0.0
			if tot > 0 {
				lp = float64(lost) * 100 / float64(tot)
			}
			log.Printf("[rtcp] CCFB acked=%d lost=%d loss=%.2f%% PLI=%d types=%v", rcvd, lost, lp, pli, counts)
			last = time.Now()
		}
	}
}

func (r *SFURelay) Init(iceServers []webrtc.ICEServer) error {
	config := webrtc.Configuration{ICEServers: iceServers}

	pubPC, err := tmapi.NewPeerConnectionGCC(config)
	if err != nil {
		return err
	}
	r.pubPC = pubPC

	sampleTrack, _ := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8},
		"video", "tunnel-video",
	)
	r.sampleTrack = sampleTrack

	// Channels: 2 — must match Yandex Telemost SFU's negotiated opus/48000/2.
	// Pion checks codec capability against the answer SDP; mismatch yields
	// "unable to start track, codec is not supported by remote" and the
	// entire pub PC fails to connect. Our OGG decoy files are converted to
	// stereo via ffmpeg -ac 2 to match.
	audioTrack, _ := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2},
		"audio", "tunnel-audio",
	)
	r.audioTrack = audioTrack
	pubPC.AddTransceiverFromTrack(audioTrack, webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionSendonly})
	vidTx, _ := pubPC.AddTransceiverFromTrack(sampleTrack, webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionSendonly})
	if os.Getenv("WLB_RTCP_LOG") != "" && vidTx != nil {
		go logIncomingRTCP(vidTx.Sender())
	}

	// Optional extra video tracks for multi-publisher bandwidth boost.
	// Default 0 (current behavior). Set WLB_EXTRA_VIDEO_TRACKS=2 to get
	// a total of 3 video m-lines and let Yandex SFU forward 3 streams.
	extraN := envIntDefault("WLB_EXTRA_VIDEO_TRACKS", 0)
	for i := 0; i < extraN; i++ {
		t, _ := webrtc.NewTrackLocalStaticSample(
			webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8},
			fmt.Sprintf("video-extra-%d", i+1),
			fmt.Sprintf("tunnel-video-extra-%d", i+1),
		)
		r.extraVideoTracks = append(r.extraVideoTracks, t)
		pubPC.AddTransceiverFromTrack(t, webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionSendonly})
	}
	if extraN > 0 {
		log.Printf("[relay] multi-publisher: added %d extra video tracks", extraN)
	}

	pubPC.OnICECandidate(func(cand *webrtc.ICECandidate) {
		if cand == nil || r.OnPubICE == nil {
			return
		}
		r.OnPubICE(cand)
	})

	pubPC.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		log.Printf("[pub] connection state: %s", state.String())
		if state == webrtc.PeerConnectionStateConnected {
			if r.tun == nil {
				if os.Getenv("WLB_VP8_DATA") != "" {
					log.Printf("[vp8data] data-carrier publish mode (tunnel DISABLED)")
					r.tun = &tunnel.VP8DataTunnel{}
					go pumpVP8Data(r.sampleTrack)
				} else if vv := os.Getenv("WLB_VALID_VP8"); vv != "" {
					// Diagnostic: send syntactically-valid VP8 carrier frames
					// (real tag+first-partition prefix + arbitrary token-partition
					// bytes). Decoder accepts without error -> no PLI -> SFU
					// should forward at full video rate. Measures whether the
					// cap is purely about decode-validity.
					log.Printf("[validvp8] carrier mode from %s (tunnel DISABLED)", vv)
					r.tun = &tunnel.VP8DataTunnel{}
					go pumpValidVP8(r.sampleTrack, vv)
				} else if rv := os.Getenv("WLB_REAL_VIDEO"); rv != "" {
					// Diagnostic: publish a REAL decodable VP8 clip instead of
					// the tunnel, to test whether the SFU's ~1.2 Mbit forward
					// cap is about media validity (decodability) vs our client
					// identity. r.tun stays nil (no tunnel); measure forward
					// rate with a relay-meas subscriber.
					log.Printf("[realvid] publishing real VP8 from %s (tunnel DISABLED)", rv)
					r.tun = &tunnel.VP8DataTunnel{} // mark started to avoid re-entry
					go pumpRealVideo(r.sampleTrack, rv)
				} else {
					allTracks := append([]*webrtc.TrackLocalStaticSample{r.sampleTrack}, r.extraVideoTracks...)
					log.Printf("[relay] starting VP8 publish tunnel on pub PC connected (tracks=%d)", len(allTracks))
					r.tun = tunnel.NewVP8DataTunnelMulti(allTracks, r.obf, log.Printf)
					r.tun.Start(0, 0)
					if r.OnConnected != nil {
						r.OnConnected(r.tun)
					}
				}
			}
			r.maybeStartAudioDecoy()
			if r.OnPubReady != nil {
				r.OnPubReady()
			}
			// DIAG: log all RTP send stats every 10s
			go func() {
				ticker := time.NewTicker(10 * time.Second)
				defer ticker.Stop()
				for range ticker.C {
					stats := pubPC.GetStats()
					log.Printf("[stats-debug] %d entries", len(stats))
					for key, s := range stats {
						log.Printf("[stats-debug] key=%q type=%T", key, s)
						if outStat, ok := s.(webrtc.OutboundRTPStreamStats); ok {
							log.Printf("[audio-stats] kind=%q ssrc=%d packets=%d bytes=%d", outStat.Kind, outStat.SSRC, outStat.PacketsSent, outStat.BytesSent)
						}
					}
				}
			}()
		}
	})

	subPC, err := tmapi.NewPeerConnection(config)
	if err != nil {
		pubPC.Close()
		return err
	}
	r.subPC = subPC

	subPC.OnICECandidate(func(cand *webrtc.ICECandidate) {
		if cand == nil || r.OnSubICE == nil {
			return
		}
		r.OnSubICE(cand)
	})

	subPC.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		log.Printf("[sub] connection state: %s", state.String())
	})

	subPC.OnTrack(func(track *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
		log.Printf("[sub] remote track: %s", track.Codec().MimeType)
		go r.readTrack(track)
	})

	log.Printf("[relay] pub+sub PCs created (%d ICE servers)", len(iceServers))
	return nil
}

func (r *SFURelay) CreatePubOffer() (webrtc.SessionDescription, error) {
	offer, err := r.pubPC.CreateOffer(nil)
	if err != nil {
		return offer, err
	}
	if err := r.pubPC.SetLocalDescription(offer); err != nil {
		return offer, err
	}
	offer.SDP = tmapi.MungeSDPAddVideoContent(offer.SDP)
	if bas := os.Getenv("WLB_PUB_BAS"); bas != "" {
		log.Printf("[relay] pub offer munged: WLB_PUB_BAS=%s sdpLen=%d", bas, len(offer.SDP))
	}
	if os.Getenv("WLB_DUMP_SDP") != "" {
		log.Printf("[relay] ===PUB OFFER SDP BEGIN===\n%s\n===PUB OFFER SDP END===", offer.SDP)
	}
	return offer, nil
}

func (r *SFURelay) SetPubAnswer(sdp string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if os.Getenv("WLB_DUMP_SDP") != "" {
		log.Printf("[relay] ===PUB ANSWER SDP BEGIN===\n%s\n===PUB ANSWER SDP END===", sdp)
	}
	err := r.pubPC.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeAnswer, SDP: sdp,
	})
	if err != nil {
		return err
	}
	r.pubRemoteSet = true
	for _, cand := range r.pubPending {
		r.pubPC.AddICECandidate(cand)
	}
	r.pubPending = nil
	return nil
}

func (r *SFURelay) SetSubOffer(sdp string) (webrtc.SessionDescription, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	err := r.subPC.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer, SDP: sdp,
	})
	if err != nil {
		return webrtc.SessionDescription{}, err
	}
	r.subRemoteSet = true
	for _, cand := range r.subPending {
		r.subPC.AddICECandidate(cand)
	}
	r.subPending = nil

	answer, err := r.subPC.CreateAnswer(nil)
	if err != nil {
		return answer, err
	}
	r.subPC.SetLocalDescription(answer)
	answer.SDP = tmapi.MungeSDPSubBAS(answer.SDP)
	return answer, nil
}

func (r *SFURelay) AddPubICECandidate(cand webrtc.ICECandidateInit) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.pubRemoteSet {
		r.pubPending = append(r.pubPending, cand)
		return
	}
	r.pubPC.AddICECandidate(cand)
}

func (r *SFURelay) AddSubICECandidate(cand webrtc.ICECandidateInit) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.subRemoteSet {
		r.subPending = append(r.subPending, cand)
		return
	}
	r.subPC.AddICECandidate(cand)
}

func (r *SFURelay) CreatePubRenegotiate() (webrtc.SessionDescription, error) {
	offer, err := r.pubPC.CreateOffer(&webrtc.OfferOptions{ICERestart: false})
	if err != nil {
		return offer, err
	}
	err = r.pubPC.SetLocalDescription(offer)
	if err != nil {
		return offer, err
	}
	offer.SDP = tmapi.MungeSDPAddVideoContent(offer.SDP)
	r.mu.Lock()
	r.pubRemoteSet = false
	r.pubPending = nil
	r.mu.Unlock()
	return offer, nil
}

// maybeStartAudioDecoy launches the audio-decoy goroutine if WLB_AUDIO_DIR is
// set and contains at least one .ogg/.opus file. Idempotent — multiple PC
// reconnects in the same SFURelay lifetime spawn only one decoy. Without an
// audio file the publisher's audio track remains silent (current behavior),
// which is fine but makes the bot trivially recognisable as automation
// (no voice + no music = inert account). With a track playing it presents as
// a normal call participant playing background music.
func (r *SFURelay) maybeStartAudioDecoy() {
	if r.audioStarted.Load() {
		return
	}
	dir := os.Getenv("WLB_AUDIO_DIR")
	if dir == "" {
		return
	}
	matches, err := filepath.Glob(filepath.Join(dir, "*.ogg"))
	if err != nil || len(matches) == 0 {
		extra, _ := filepath.Glob(filepath.Join(dir, "*.opus"))
		matches = append(matches, extra...)
	}
	if len(matches) == 0 {
		log.Printf("[audio] WLB_AUDIO_DIR=%s has no .ogg/.opus files, skipping decoy", dir)
		return
	}
	if !r.audioStarted.CompareAndSwap(false, true) {
		return
	}
	r.audioStopCh = make(chan struct{})
	log.Printf("[audio] decoy starting: %d candidates in %s (will rotate at each EOF)", len(matches), dir)
	go r.streamAudioLoop(matches)
}

// streamAudioLoop picks a random file from the candidate pool, plays it to
// EOF, then picks ANOTHER random file (not the one just played) and continues.
// This avoids the "user listened to one 3-minute Crime and Punishment chapter
// on repeat for 6 hours" detectability problem. A real human swaps tracks /
// chapters / podcasts, so the bot should too. The previous track is tracked
// to prevent immediate re-pick when the pool has >=2 entries.
func (r *SFURelay) streamAudioLoop(pool []string) {
	if len(pool) == 0 {
		return
	}
	prev := -1
	for {
		select {
		case <-r.audioStopCh:
			return
		default:
		}
		idx := rand.Intn(len(pool))
		if len(pool) > 1 && idx == prev {
			idx = (idx + 1) % len(pool)
		}
		prev = idx
		path := pool[idx]
		log.Printf("[audio] now playing: %s", filepath.Base(path))
		if err := r.streamOggOnce(path); err != nil && !errors.Is(err, io.EOF) {
			log.Printf("[audio] decoy read error on %s: %v (sleeping 5s)", filepath.Base(path), err)
			select {
			case <-r.audioStopCh:
				return
			case <-time.After(5 * time.Second):
			}
		}
	}
}

func (r *SFURelay) streamOggOnce(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	ogg, _, err := oggreader.NewWith(f)
	if err != nil {
		return err
	}

	const opusFrameMs = 20
	ticker := time.NewTicker(opusFrameMs * time.Millisecond)
	defer ticker.Stop()
	var lastGranule uint64
	for {
		select {
		case <-r.audioStopCh:
			return nil
		default:
		}
		pageData, pageHeader, err := ogg.ParseNextPage()
		if errors.Is(err, io.EOF) {
			return io.EOF
		}
		if err != nil {
			return err
		}
		sampleCount := pageHeader.GranulePosition - lastGranule
		lastGranule = pageHeader.GranulePosition
		// 48kHz Opus clock — convert samples to ms via /48.
		dur := time.Duration(sampleCount/48) * time.Millisecond
		if dur < opusFrameMs*time.Millisecond {
			dur = opusFrameMs * time.Millisecond
		}
		if r.audioTrack != nil {
			werr := r.audioTrack.WriteSample(media.Sample{Data: pageData, Duration: dur})
			r.audioFrameCount++
			// Log every 50 frames (~1 second at 50 fps Opus) to verify activity without log spam
			if r.audioFrameCount%50 == 1 {
				if werr != nil {
					log.Printf("[audio-loop] frame=%d bytes=%d dur=%v ERROR=%v", r.audioFrameCount, len(pageData), dur, werr)
				} else {
					log.Printf("[audio-loop] frame=%d bytes=%d dur=%v OK", r.audioFrameCount, len(pageData), dur)
				}
			}
			if werr != nil {
				return werr
			}
		}
		select {
		case <-r.audioStopCh:
			return nil
		case <-ticker.C:
		}
	}
}

func (r *SFURelay) Close() {
	if r.audioStopCh != nil {
		select {
		case <-r.audioStopCh:
			// already closed
		default:
			close(r.audioStopCh)
		}
	}
	if r.tun != nil {
		r.tun.Stop()
		r.tun = nil
	}
	if r.pubPC != nil {
		r.pubPC.Close()
		r.pubPC = nil
	}
	if r.subPC != nil {
		r.subPC.Close()
		r.subPC = nil
	}
}

var gridRecvTotal int64
var gridTrackCount int32
var gridRateStarted int32

func envIntDefault(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func (r *SFURelay) readTrack(track *webrtc.TrackRemote) {
	if track.Codec().MimeType != webrtc.MimeTypeVP8 {
		buf := make([]byte, common.UDPBufSize)
		for {
			if _, _, err := track.Read(buf); err != nil {
				return
			}
		}
	}

	// Experiment instrument (WLB_RECV_RATE): periodic forward-rate of the stream
	// we receive from the SFU. Measures what bitrate Yandex allocates to the
	// publisher's format (real-video vs valid-VP8 vs carrier). Off by default.
	verifyData := os.Getenv("WLB_VERIFY_DATA") != ""
	var vTotal, vOK, vDecodeFail, vMismatch int
	atomic.AddInt32(&gridTrackCount, 1)
	defer atomic.AddInt32(&gridTrackCount, -1)
	if os.Getenv("WLB_RECV_RATE") != "" && atomic.CompareAndSwapInt32(&gridRateStarted, 0, 1) {
		go func() {
			tk := time.NewTicker(2 * time.Second)
			defer tk.Stop()
			var prev int64
			for range tk.C {
				cur := atomic.LoadInt64(&gridRecvTotal)
				log.Printf("[recv-rate] %.3f Mbit/s total=%dKB tracks=%d", float64(cur-prev)*8/2/1e6, cur/1024, atomic.LoadInt32(&gridTrackCount))
				prev = cur
			}
		}()
	}

	var vp8Pkt codecs.VP8Packet
	var frameBuf []byte
	var lastSeq uint16
	var haveLastSeq bool
	frameValid := false
	var recvCount int
	bufSz := r.readBufSize
	if bufSz <= 0 {
		bufSz = common.RTPBufSize
	}
	buf := make([]byte, bufSz)
	for {
		n, _, err := track.Read(buf)
		if err != nil {
			return
		}
		pkt := &rtp.Packet{}
		if pkt.Unmarshal(buf[:n]) != nil {
			continue
		}
		if haveLastSeq && pkt.SequenceNumber != lastSeq+1 {
			frameValid = false
			frameBuf = frameBuf[:0]
		}
		lastSeq = pkt.SequenceNumber
		haveLastSeq = true

		vp8Payload, err := vp8Pkt.Unmarshal(pkt.Payload)
		if err != nil {
			frameValid = false
			frameBuf = frameBuf[:0]
			continue
		}
		if vp8Pkt.S == 1 {
			frameBuf = frameBuf[:0]
			frameValid = true
		}
		if !frameValid {
			continue
		}
		frameBuf = append(frameBuf, vp8Payload...)
		if !pkt.Marker {
			continue
		}
		recvCount++
		atomic.AddInt64(&gridRecvTotal, int64(len(frameBuf)))
		if recvCount <= 3 || recvCount%200 == 0 {
			log.Printf("[video] recv vp8 frame #%d %d bytes", recvCount, len(frameBuf))
		}

		if verifyData {
			vTotal++
			pl, derr := tunnel.DecodeKeyframeData(frameBuf)
			if derr != nil {
				vDecodeFail++
			} else if len(pl) >= 8000 {
				sq := uint32(pl[0])<<24 | uint32(pl[1])<<16 | uint32(pl[2])<<8 | uint32(pl[3])
				ok := true
				for i := 4; i < 8000; i++ {
					if pl[i] != byte(sq*31+uint32(i)*131+7) {
						ok = false
						break
					}
				}
				if ok {
					vOK++
				} else {
					vMismatch++
				}
			} else {
				vMismatch++
			}
			if vTotal%48 == 0 {
				log.Printf("[verify] total=%d ok=%d decodeFail=%d mismatch=%d", vTotal, vOK, vDecodeFail, vMismatch)
			}
			frameBuf = frameBuf[:0]
			frameValid = false
			continue
		}
		res := r.obf.Decode(frameBuf)
		frameBuf = frameBuf[:0]
		frameValid = false

		if !res.HasFrame || res.SelfEcho {
			continue
		}

		// Multi-client: no phantom-lock. Route every peer's real payload by
		// SENDER epoch into its own bridge; the bridge addresses replies back
		// to that epoch. Keepalives and epoch-0 frames are ignored.
		if muxEnabled {
			if res.Keepalive || len(res.Payload) == 0 || res.PeerEpoch == 0 {
				continue
			}
			if r.tun != nil {
				r.tun.DeliverFromPeer(res.PeerEpoch, res.Payload)
			}
			continue
		}

		// Phantom-tolerant lock: ignore obfuscator's PeerRestart flag
		// (it ping-pongs whenever ghost participants in the room send
		// video) and instead lock to the first peer that delivers
		// real payload. Frames from any other peer are dropped silently.
		locked := r.lockedPeerEpoch.Load()
		if locked != 0 {
			lastMs := r.lockedDataAtMs.Load()
			if lastMs > 0 && time.Now().UnixMilli()-lastMs > peerLockIdleMs {
				if r.lockedPeerEpoch.CompareAndSwap(locked, 0) {
					log.Printf("[relay] unlocked stale peer epoch=0x%08x (idle %dms)", locked, peerLockIdleMs)
					locked = 0
				} else {
					locked = r.lockedPeerEpoch.Load()
				}
			}
		}
		if locked == 0 {
			if res.Keepalive || len(res.Payload) == 0 || res.PeerEpoch == 0 {
				continue
			}
			if r.lockedPeerEpoch.CompareAndSwap(0, res.PeerEpoch) {
				log.Printf("[relay] locked to peer epoch=0x%08x", res.PeerEpoch)
				r.lockedDataAtMs.Store(time.Now().UnixMilli())
				// A new peer is in charge; tunnels from the previous
				// locked peer are now stale (if any). Fire the reset
				// hook so the bridge clears them.
				if r.OnPeerRestart != nil {
					r.OnPeerRestart()
				}
				locked = res.PeerEpoch
			} else {
				locked = r.lockedPeerEpoch.Load()
			}
		}
		if res.PeerEpoch != locked {
			continue
		}
		if res.Keepalive || len(res.Payload) == 0 {
			continue
		}
		r.lockedDataAtMs.Store(time.Now().UnixMilli())
		if r.tun != nil {
			r.tun.DeliverPayload(res.Payload)
		}
	}
}
