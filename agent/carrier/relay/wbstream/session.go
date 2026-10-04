package wbstream

import (
	"context"
	"log"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/livekit"
	"whitelist-bypass/relay/tunnel"
)

type peerEntry struct {
	sid       string
	identity  string
	firstSeen time.Time
	state     int32
	promoted  bool
}

const (
	TunnelModeVideo = "video"
	TunnelModeDC    = "dc"
)

type SessionConfig struct {
	RoomToken      string
	ServerURL      string
	DisplayName    string
	TunnelMode     string
	Obfuscator     *tunnel.TunnelObfuscator
	LogFn          func(string, ...any)
	SettingEngine  *webrtc.SettingEngine
	NetDialContext func(ctx context.Context, network, addr string) (net.Conn, error)
	ResolveICEHost func(host string) (string, error)
	VP8FPS         int
	VP8Batch       int
	RoomID         string
	AccessToken    string
	ReadBuf        int
	ScreenShare    bool // when true, publish a second VP8 track as ScreenShare and shard outbound across both
	IsJoiner       bool // when true, run the configPingPong loop; only the joiner sends VP8 config to the peer
}

type Session struct {
	cfg SessionConfig

	lk                 *livekit.Client
	sampleTracks       []*webrtc.TrackLocalStaticSample
	sampleTransceivers []*webrtc.RTPTransceiver

	pubReliableDC      *webrtc.DataChannel
	pubReliableDCReady bool
	subReliableDC      *webrtc.DataChannel

	vp8tun          *tunnel.MultiTrackTunnel
	dctun           *tunnel.DCTunnel
	mu              sync.Mutex
	tunFired        bool
	done            chan struct{}
	doneOnce        sync.Once
	closing         bool // protected by mu; blocks late publisher callbacks during Close
	receiveMu       sync.Mutex
	carrierPeer     carrierPeerGate
	health          mediaHealth
	healthReadyOnce sync.Once

	peersBySID map[string]peerEntry // SID -> first-seen time + state
	kickedSIDs map[string]bool      // SIDs we kicked; SFU may still echo them as Active until it processes the kick

	configAcked     chan struct{}
	configAckedOnce sync.Once

	OnConnected   func(tunnel.DataTunnel)
	OnPeerRestart func()
	// OnRemoteCandidate is forwarded from the underlying LiveKit client.
	// It fires for every trickle ICE candidate sent by the SFU, plus
	// once with target=-1 for every SDP description (carrying inline
	// candidates) before the description is applied to Pion.
	OnRemoteCandidate func(target int, candidateOrSDP string)
}

func NewSession(cfg SessionConfig) *Session {
	if cfg.LogFn == nil {
		cfg.LogFn = log.Printf
	}
	if cfg.Obfuscator != nil {
		cfg.Obfuscator.EnableRecipientBinding(cfg.IsJoiner)
	}
	return &Session{
		cfg:         cfg,
		done:        make(chan struct{}),
		configAcked: make(chan struct{}),
	}
}

func (s *Session) MarkConfigAcked() bool {
	first := false
	s.configAckedOnce.Do(func() {
		first = true
		s.cfg.LogFn("[lk] peer acked vp8 config")
		close(s.configAcked)
	})
	return first
}

func (s *Session) Done() <-chan struct{}        { return s.done }
func (s *Session) ConfigAcked() <-chan struct{} { return s.configAcked }

func (s *Session) PublisherConnectionState() webrtc.PeerConnectionState {
	if s.lk == nil || s.lk.PubPC() == nil {
		return webrtc.PeerConnectionStateNew
	}
	return s.lk.PubPC().ConnectionState()
}

func (s *Session) SubscriberConnectionState() webrtc.PeerConnectionState {
	if s.lk == nil || s.lk.SubPC() == nil {
		return webrtc.PeerConnectionStateNew
	}
	return s.lk.SubPC().ConnectionState()
}

func (s *Session) Start() error {
	s.lk = livekit.NewClient(livekit.Config{
		ServerURL:      s.cfg.ServerURL,
		Token:          s.cfg.RoomToken,
		Origin:         Origin,
		UserAgent:      common.UserAgent,
		LogFn:          s.cfg.LogFn,
		SettingEngine:  s.cfg.SettingEngine,
		NetDialContext: s.cfg.NetDialContext,
		ResolveICEHost: s.cfg.ResolveICEHost,
	})
	s.lk.OnReady = s.onLKReady
	s.lk.OnTrack = s.onRemoteTrack
	s.lk.OnDataChannel = s.onRemoteDataChannel
	s.lk.OnPubConnected = s.startTunnel
	if s.cfg.AccessToken != "" && s.cfg.RoomID != "" {
		s.lk.OnParticipantUpdate = s.onParticipantUpdate
	}
	s.lk.OnRemoteCandidate = func(target int, ic webrtc.ICECandidateInit) {
		if s.OnRemoteCandidate != nil {
			s.OnRemoteCandidate(target, ic.Candidate)
		}
	}
	s.lk.OnRemoteSDP = func(target int, _, sdp string) {
		if s.OnRemoteCandidate != nil {
			s.OnRemoteCandidate(-1, sdp)
		}
	}

	if err := s.lk.Connect(); err != nil {
		return err
	}
	go s.lk.PingLoop()
	go func() {
		if err := s.lk.ReadLoop(); err != nil {
			s.cfg.LogFn("[lk] read loop ended: %v", err)
		}
		s.Close()
	}()
	return nil
}

func (s *Session) stopTunnels() {
	s.mu.Lock()
	vp8 := s.vp8tun
	s.mu.Unlock()
	if vp8 != nil {
		vp8.Stop()
	}
}

func (s *Session) onLKReady() {
	pubPC := s.lk.PubPC()
	if pubPC == nil {
		return
	}

	camID := "videochannel-" + uuid.New().String()
	trackCam, err := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000},
		camID, "tunnel-video-"+uuid.New().String(),
	)
	if err != nil {
		s.cfg.LogFn("[lk] create local cam track: %v", err)
		return
	}
	tracks := []*webrtc.TrackLocalStaticSample{trackCam}

	if s.cfg.ScreenShare {
		screenID := "screenchannel-" + uuid.New().String()
		trackScreen, err := webrtc.NewTrackLocalStaticSample(
			webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000},
			screenID, "tunnel-screen-"+uuid.New().String(),
		)
		if err != nil {
			s.cfg.LogFn("[lk] create local screen track: %v", err)
			return
		}
		tracks = append(tracks, trackScreen)
	}

	transceivers := make([]*webrtc.RTPTransceiver, 0, len(tracks))
	for _, t := range tracks {
		trx, err := pubPC.AddTransceiverFromTrack(t,
			webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionSendonly})
		if err != nil {
			s.cfg.LogFn("[lk] add transceiver: %v", err)
			return
		}
		transceivers = append(transceivers, trx)
		go s.readPublisherFeedback(trx.Sender())
	}

	s.mu.Lock()
	s.sampleTracks = tracks
	s.sampleTransceivers = transceivers
	s.mu.Unlock()

	ordered := true
	dc, err := pubPC.CreateDataChannel("_reliable", &webrtc.DataChannelInit{
		Ordered: &ordered,
	})
	if err != nil {
		s.cfg.LogFn("[lk] create reliable DC: %v", err)
		return
	}
	s.mu.Lock()
	s.pubReliableDC = dc
	s.mu.Unlock()
	dc.OnOpen(func() {
		s.cfg.LogFn("[lk] reliable DC open")
		s.mu.Lock()
		s.pubReliableDCReady = true
		s.mu.Unlock()
		s.maybeStartDCTunnel()
	})

	for i, t := range tracks {
		source := livekit.TrackSourceCamera
		if i > 0 {
			source = livekit.TrackSourceScreenShare
		}
		if err := s.lk.SendAddTrack(t.ID(), "videochannel",
			livekit.TrackTypeVideo, source, 1280, 720); err != nil {
			s.cfg.LogFn("[lk] send add-track: %v", err)
			return
		}
	}

	offer, err := pubPC.CreateOffer(nil)
	if err != nil {
		s.cfg.LogFn("[lk] create offer: %v", err)
		return
	}
	if err := pubPC.SetLocalDescription(offer); err != nil {
		s.cfg.LogFn("[lk] set local offer: %v", err)
		return
	}
	if err := s.lk.SendOffer(offer.SDP); err != nil {
		s.cfg.LogFn("[lk] send offer: %v", err)
		return
	}
	s.cfg.LogFn("[lk] sent publisher offer (%d bytes)", len(offer.SDP))
}

func (s *Session) startTunnel() {
	s.mu.Lock()
	select {
	case <-s.done:
		s.mu.Unlock()
		return
	default:
	}
	if s.closing || s.vp8tun != nil || len(s.sampleTracks) == 0 {
		s.mu.Unlock()
		return
	}
	subs := make([]*tunnel.VP8DataTunnel, 0, len(s.sampleTracks))
	for _, t := range s.sampleTracks {
		subs = append(subs, tunnel.NewVP8DataTunnel(t, s.cfg.Obfuscator, s.cfg.LogFn))
	}
	s.vp8tun = tunnel.NewMultiTrackTunnel(subs)
	s.vp8tun.Start(s.cfg.VP8FPS, s.cfg.VP8Batch)
	tun := s.vp8tun
	s.mu.Unlock()
	go s.watchCarrier(tun, 45*time.Second)
	s.cfg.LogFn("[lk] vp8 tunnel writer started tracks=%d", len(subs))
	if s.cfg.IsJoiner {
		go s.configPingPong(tun, len(subs))
	}

	if s.cfg.TunnelMode == TunnelModeVideo {
		s.fireOnConnected(tun)
		return
	}
	if s.cfg.TunnelMode == "" {
		tun.SetOnData(func(payload []byte) { s.activate(tun, payload) })
	}
}

// Media failure can leave signaling alive. End the session so the existing
// reconnect loop can recover instead of retrying config on a dead tunnel forever.
func (s *Session) watchCarrier(tun *tunnel.MultiTrackTunnel, handshakeTimeout time.Duration) {
	var timer *time.Timer
	var deadline <-chan time.Time
	ack := s.configAcked
	startHealth := func() {
		if s.cfg.IsJoiner && s.cfg.TunnelMode != TunnelModeDC && tun.MediaHealthSupported() {
			go s.watchMediaHealth(tun)
		}
	}
	if s.cfg.IsJoiner && s.cfg.TunnelMode != TunnelModeDC {
		timer = time.NewTimer(handshakeTimeout)
		deadline = timer.C
		defer timer.Stop()
	}
	for {
		select {
		case <-s.done:
			return
		case <-tun.Done():
			s.cfg.LogFn("[lk] carrier stopped; ending session for recovery")
			s.Close()
			return
		case <-ack:
			startHealth()
			ack = nil
			deadline = nil
			if timer != nil {
				timer.Stop()
			}
		case <-deadline:
			// A simultaneous ACK wins over the deadline.
			select {
			case <-s.configAcked:
				startHealth()
				deadline = nil
				ack = nil
				continue
			default:
			}
			s.cfg.LogFn("[lk] exit config ACK timeout; ending session for recovery")
			s.Close()
			return
		}
	}
}

func (s *Session) configPingPong(tun *tunnel.MultiTrackTunnel, trackCount int) {
	frame := tunnel.EncodeVP8Config(s.cfg.VP8FPS, s.cfg.VP8Batch, trackCount)
	tun.SendData(frame)
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.configAcked:
			return
		case <-s.done:
			return
		case <-ticker.C:
			s.cfg.LogFn("[lk] resending vp8 config (no ack yet)")
			tun.SendData(tunnel.EncodeVP8Config(s.cfg.VP8FPS, s.cfg.VP8Batch, trackCount))
		}
	}
}

func (s *Session) maybeStartDCTunnel() {
	s.mu.Lock()
	if s.dctun != nil {
		s.mu.Unlock()
		return
	}
	pubDC := s.pubReliableDC
	subDC := s.subReliableDC
	pubReady := s.pubReliableDCReady
	s.mu.Unlock()
	if pubDC == nil || subDC == nil || !pubReady {
		return
	}
	if subDC.ReadyState() != webrtc.DataChannelStateOpen {
		return
	}
	subRaw, err := subDC.Detach()
	if err != nil {
		s.cfg.LogFn("[lk] detach sub DC: %v", err)
		return
	}
	pubRaw, err := pubDC.Detach()
	if err != nil {
		s.cfg.LogFn("[lk] detach pub DC: %v", err)
		return
	}
	readWrapped := newDataPacketWrapper(subRaw, livekit.DataPacketKindReliable)
	writeWrapped := newDataPacketWrapper(pubRaw, livekit.DataPacketKindReliable)
	readBuf := s.cfg.ReadBuf
	if readBuf == 0 {
		readBuf = common.DCBufSize
	}
	dctun := tunnel.NewChunkedDCTunnelFromRaw(readWrapped, writeWrapped, s.cfg.Obfuscator, readBuf, s.cfg.LogFn)
	if dctun == nil {
		return
	}
	s.mu.Lock()
	s.dctun = dctun
	s.mu.Unlock()
	s.cfg.LogFn("[lk] dc tunnel ready (pub+sub _reliable)")

	if s.cfg.TunnelMode == TunnelModeDC {
		s.fireOnConnected(dctun)
		return
	}
	if s.cfg.TunnelMode == "" {
		dctun.SetOnData(func(payload []byte) { s.activate(dctun, payload) })
	}
}

func (s *Session) fireOnConnected(tun tunnel.DataTunnel) {
	s.mu.Lock()
	if s.tunFired {
		s.mu.Unlock()
		return
	}
	s.tunFired = true
	s.mu.Unlock()
	if s.OnConnected != nil {
		s.OnConnected(tun)
	}
}

func (s *Session) activate(tun tunnel.DataTunnel, payload []byte) {
	s.mu.Lock()
	if s.tunFired {
		s.mu.Unlock()
		return
	}
	s.tunFired = true
	s.mu.Unlock()
	s.cfg.LogFn("[lk] auto-detected active tunnel: %T", tun)
	if s.OnConnected != nil {
		s.OnConnected(tun)
	}
	var fwd func([]byte)
	switch v := tun.(type) {
	case *tunnel.DCTunnel:
		fwd = v.OnData()
	case *tunnel.MultiTrackTunnel:
		// MultiTrackTunnel does not expose a readable OnData hook; the trigger
		// payload is dropped here. Next frame will arrive via the SetOnData
		// callback OnConnected wires up.
	}
	if fwd != nil {
		fwd(payload)
	}
}

func (s *Session) currentVP8Tun() *tunnel.MultiTrackTunnel {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.vp8tun
}

func (s *Session) AdaptTrackCount(peerCount int) {
	if peerCount < 1 {
		return
	}
	pubPC := s.lk.PubPC()
	if pubPC == nil {
		return
	}
	s.mu.Lock()
	current := len(s.sampleTracks)
	s.mu.Unlock()
	if peerCount == current {
		s.cfg.LogFn("[lk] adapt-track-count: peer=%d current=%d, no change", peerCount, current)
		return
	}
	if peerCount > current {
		s.cfg.LogFn("[lk] adapt-track-count: scaling publisher tracks %d -> %d", current, peerCount)
		for i := current; i < peerCount; i++ {
			if !s.addPublisherTrack(pubPC, i) {
				return
			}
		}
	} else {
		s.cfg.LogFn("[lk] adapt-track-count: shrinking publisher tracks %d -> %d", current, peerCount)
		for i := current; i > peerCount; i-- {
			if !s.removePublisherTrack() {
				return
			}
		}
	}
	offer, err := pubPC.CreateOffer(nil)
	if err != nil {
		s.cfg.LogFn("[lk] adapt-track-count: create offer: %v", err)
		return
	}
	if err := pubPC.SetLocalDescription(offer); err != nil {
		s.cfg.LogFn("[lk] adapt-track-count: set local offer: %v", err)
		return
	}
	if err := s.lk.SendOffer(offer.SDP); err != nil {
		s.cfg.LogFn("[lk] adapt-track-count: send offer: %v", err)
		return
	}
	s.cfg.LogFn("[lk] adapt-track-count: renegotiation offer sent (%d bytes)", len(offer.SDP))
}

// removePublisherTrack stops the trailing transceiver, drops its sub-tunnel
// from the multi-track wrapper, and trims the bookkeeping slices. The SFU
// sees the transceiver go inactive on the next renegotiation and stops
// forwarding the track to subscribers. Slot 0 is preserved.
func (s *Session) removePublisherTrack() bool {
	s.mu.Lock()
	if len(s.sampleTransceivers) <= 1 || len(s.sampleTracks) <= 1 {
		s.mu.Unlock()
		s.cfg.LogFn("[lk] adapt-track-count: refusing to remove cam slot")
		return false
	}
	last := len(s.sampleTransceivers) - 1
	trx := s.sampleTransceivers[last]
	s.sampleTransceivers = s.sampleTransceivers[:last]
	s.sampleTracks = s.sampleTracks[:last]
	vp8 := s.vp8tun
	s.mu.Unlock()
	if vp8 != nil {
		vp8.RemoveLastSubTunnel()
	}
	if err := trx.Stop(); err != nil {
		s.cfg.LogFn("[lk] adapt-track-count: stop transceiver: %v", err)
		return false
	}
	return true
}

func (s *Session) addPublisherTrack(pubPC *webrtc.PeerConnection, slot int) bool {
	labelPrefix := "screenchannel-"
	streamPrefix := "tunnel-screen-"
	source := livekit.TrackSourceScreenShare
	if slot == 0 {
		labelPrefix = "videochannel-"
		streamPrefix = "tunnel-video-"
		source = livekit.TrackSourceCamera
	}
	track, err := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000},
		labelPrefix+uuid.New().String(), streamPrefix+uuid.New().String(),
	)
	if err != nil {
		s.cfg.LogFn("[lk] adapt-track-count: new track slot=%d: %v", slot, err)
		return false
	}
	trx, err := pubPC.AddTransceiverFromTrack(track,
		webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionSendonly})
	if err != nil {
		s.cfg.LogFn("[lk] adapt-track-count: add transceiver slot=%d: %v", slot, err)
		return false
	}
	if err := s.lk.SendAddTrack(track.ID(), "videochannel",
		livekit.TrackTypeVideo, source, 1280, 720); err != nil {
		s.cfg.LogFn("[lk] adapt-track-count: send add-track slot=%d: %v", slot, err)
		return false
	}
	go s.readPublisherFeedback(trx.Sender())
	s.mu.Lock()
	s.sampleTracks = append(s.sampleTracks, track)
	s.sampleTransceivers = append(s.sampleTransceivers, trx)
	vp8 := s.vp8tun
	s.mu.Unlock()
	if vp8 != nil {
		vp8.AddSubTunnel(tunnel.NewVP8DataTunnel(track, s.cfg.Obfuscator, s.cfg.LogFn))
	}
	return true
}

// Reading RTCP drives Pion's NACK responder. Without this reader lost RTP
// fragments wait for the much slower application-level retransmission loop.
// PLI/FIR also request a fresh VP8 reference instead of waiting up to 2 seconds.
func (s *Session) readPublisherFeedback(sender *webrtc.RTPSender) {
	if sender == nil {
		return
	}
	var lastKeyframe time.Time
	var lastReport time.Time
	var nacks, keyframes uint64
	var fractionLost uint8
	for {
		packets, _, err := sender.ReadRTCP()
		if err != nil {
			return // sender/PeerConnection close unblocks ReadRTCP
		}
		for _, packet := range packets {
			switch p := packet.(type) {
			case *rtcp.TransportLayerNack:
				nacks += uint64(len(p.Nacks))
			case *rtcp.ReceiverReport:
				for _, report := range p.Reports {
					fractionLost = report.FractionLost
				}
			case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
				keyframes++
				if s.cfg.Obfuscator != nil && time.Since(lastKeyframe) >= 250*time.Millisecond {
					s.cfg.Obfuscator.RequestKeyframe()
					lastKeyframe = time.Now()
				}
			}
		}
		if time.Since(lastReport) >= 5*time.Second {
			s.cfg.LogFn("[wb-feedback] nack-pairs=%d keyframe-requests=%d rtp-loss=%d/256", nacks, keyframes, fractionLost)
			lastReport = time.Now()
			nacks, keyframes = 0, 0
		}
	}
}

func (s *Session) rearmAutoDetect() {
	if s.cfg.TunnelMode != "" {
		return
	}
	s.mu.Lock()
	s.tunFired = false
	vp8 := s.vp8tun
	dc := s.dctun
	s.mu.Unlock()
	if vp8 != nil {
		vp8.SetOnData(func(payload []byte) { s.activate(vp8, payload) })
	}
	if dc != nil {
		dc.SetOnData(func(payload []byte) { s.activate(dc, payload) })
	}
}

func (s *Session) onRemoteTrack(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
	if track.Codec().MimeType != webrtc.MimeTypeVP8 {
		go func() {
			buf := make([]byte, common.UDPBufSize)
			for {
				if _, _, err := track.Read(buf); err != nil {
					return
				}
			}
		}()
		return
	}
	go s.readVP8Track(track)
}

func (s *Session) readVP8Track(track *webrtc.TrackRemote) {
	var trackEpoch uint32
	defer func() {
		s.receiveMu.Lock()
		s.carrierPeer.closeTrack(trackEpoch)
		s.receiveMu.Unlock()
	}()
	var assembler common.VP8Assembler
	var recvCount int
	buf := make([]byte, common.RTPBufSize)
	for {
		n, _, err := track.Read(buf)
		if err != nil {
			return
		}
		pkt := &rtp.Packet{}
		if pkt.Unmarshal(buf[:n]) != nil {
			continue
		}
		frameBuf := assembler.Push(pkt)
		if frameBuf == nil {
			continue
		}
		recvCount++
		if recvCount <= 3 || recvCount%200 == 0 {
			s.cfg.LogFn("[lk-video] recv vp8 frame #%d %d bytes", recvCount, len(frameBuf))
		}

		s.handleCarrierFrame(frameBuf, &trackEpoch)
	}
}

// All tracks share one reliable byte stream. A browser camera or a second track
// from the same sender must not clear its sequence cursors or close its sockets.
func (s *Session) handleCarrierFrame(frame []byte, trackEpoch *uint32) bool {
	s.receiveMu.Lock()
	defer s.receiveMu.Unlock()
	if s.cfg.Obfuscator == nil {
		return false
	}
	r := s.cfg.Obfuscator.InspectCarrierFrame(frame)
	if !r.HasFrame || r.SelfEcho || r.Keepalive || len(r.Payload) == 0 {
		return false
	}
	accepted, restarted := s.carrierPeer.accept(r.PeerEpoch, trackEpoch, time.Now())
	if !accepted {
		return false
	}
	if restarted {
		s.cfg.LogFn("[wb] authenticated tunnel peer replaced")
		if s.cfg.IsJoiner {
			// A restarted exit cannot preserve existing TCP streams. Rejoin with a
			// fresh local generation and a fresh readiness handshake.
			go s.Close()
			return false
		}
		if s.OnPeerRestart != nil {
			s.OnPeerRestart()
		}
		if tun := s.currentVP8Tun(); tun != nil {
			tun.ResetPeerRestart()
		}
		s.rearmAutoDetect()
	}
	// Only accepted authenticated data can select the reverse destination.
	s.cfg.Obfuscator.SetRecipientEpoch(r.PeerEpoch, r.RecipientBound)
	if r.RecipientBound && len(r.Payload) == 1+tunnel.MediaNonceSize {
		switch r.Payload[0] {
		case tunnel.MediaPing:
			if !s.cfg.IsJoiner {
				if tun := s.currentVP8Tun(); tun != nil {
					reply := append([]byte(nil), r.Payload...)
					reply[0] = tunnel.MediaPong
					tun.SendMediaControl(reply)
				}
			}
			return true
		case tunnel.MediaPong:
			if s.cfg.IsJoiner {
				if s.health.echo(r.Payload, time.Now()) {
					s.healthReadyOnce.Do(func() { s.cfg.LogFn("[wb-health] peer echo verified; media watchdog armed") })
				}
			}
			return true
		}
	}
	if tun := s.currentVP8Tun(); tun != nil {
		tun.HandleFrame(frame)
	}
	return true
}

func (s *Session) onRemoteDataChannel(dc *webrtc.DataChannel) {
	s.cfg.LogFn("[lk] remote DC label=%s id=%v", dc.Label(), dc.ID())
	if dc.Label() != "_reliable" {
		return
	}
	s.mu.Lock()
	s.subReliableDC = dc
	s.mu.Unlock()
	dc.OnOpen(func() {
		s.cfg.LogFn("[lk] remote _reliable DC open")
		s.maybeStartDCTunnel()
	})
}

func (s *Session) onParticipantUpdate(updates []livekit.ParticipantInfo) {
	selfSID := s.lk.Join().ParticipantSID

	s.mu.Lock()
	if s.peersBySID == nil {
		s.peersBySID = make(map[string]peerEntry)
	}
	canPromote := s.cfg.AccessToken != "" && s.cfg.RoomID != ""
	for _, p := range updates {
		if p.SID == "" || p.SID == selfSID {
			continue
		}
		if p.State == livekit.ParticipantStateDisconnected {
			delete(s.peersBySID, p.SID)
			delete(s.kickedSIDs, p.SID)
			continue
		}
		if s.kickedSIDs[p.SID] {
			continue
		}
		entry, ok := s.peersBySID[p.SID]
		if !ok {
			entry = peerEntry{sid: p.SID, identity: p.Identity, firstSeen: time.Now()}
		}
		if p.Identity != "" {
			entry.identity = p.Identity
		}
		entry.state = p.State
		s.peersBySID[p.SID] = entry
	}

	// A newcomer is not evidence that an existing Active participant is stale.
	// Keep live peers until an explicit disconnected update (or manual removal).
	// Automatically kicking them interrupts established tunnels on every join.
	var toPromote []peerEntry
	if canPromote {
		for sid, entry := range s.peersBySID {
			if !entry.promoted && entry.state == livekit.ParticipantStateActive && entry.identity != "" {
				entry.promoted = true
				s.peersBySID[sid] = entry
				toPromote = append(toPromote, entry)
			}
		}
	}
	s.mu.Unlock()

	for _, e := range toPromote {
		go s.promotePeer(e.sid, e.identity)
	}
}

func (s *Session) promotePeer(sid, identity string) {
	if err := SetParticipantPermissions(http.DefaultClient, s.cfg.AccessToken, s.cfg.RoomID, identity, ModeratorPermissions); err != nil {
		s.cfg.LogFn("[wb] promote failed identity=%s: %v", identity, err)
		s.mu.Lock()
		if entry, ok := s.peersBySID[sid]; ok {
			entry.promoted = false
			s.peersBySID[sid] = entry
		}
		s.mu.Unlock()
		return
	}
	s.cfg.LogFn("[wb] promoted to moderator identity=%s sid=%s", identity, sid)
}

func (s *Session) Close() {
	s.doneOnce.Do(func() {
		s.mu.Lock()
		s.closing = true
		s.mu.Unlock()
		s.stopTunnels()
		if s.lk != nil {
			s.lk.Close()
		}
		// Reconnect may start only after the old session finishes closing.
		close(s.done)
	})
}
