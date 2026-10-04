package joiner

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/tunnel"
)

const (
	vkReconnectInitialDelay = time.Second
	vkReconnectMaxDelay     = 16 * time.Second
)

type vkAuthRottenError struct {
	Code string
	Msg  string
}

func (e *vkAuthRottenError) Error() string {
	if e.Msg != "" {
		return fmt.Sprintf("auth rotten: %s %s", e.Code, e.Msg)
	}
	return fmt.Sprintf("auth rotten: %s", e.Code)
}

type VKHeadlessAuthParams struct {
	SessionKey      string `json:"sessionKey"`
	ApplicationKey  string `json:"applicationKey"`
	APIBaseURL      string `json:"apiBaseURL"`
	JoinLink        string `json:"joinLink"`
	AnonymToken     string `json:"anonymToken"`
	AppVersion      string `json:"appVersion"`
	ProtocolVersion string `json:"protocolVersion"`
	TunnelMode      string `json:"tunnelMode"`
	VP8FPS          int    `json:"vp8Fps"`
	VP8Batch        int    `json:"vp8Batch"`
}

type VKJoinResponse struct {
	Endpoint   string `json:"endpoint"`
	Token      string `json:"token"`
	TurnServer struct {
		URLs       []string `json:"urls"`
		Username   string   `json:"username"`
		Credential string   `json:"credential"`
	} `json:"turn_server"`
	StunServer struct {
		URLs []string `json:"urls"`
	} `json:"stun_server"`
}

type VKHeadlessJoiner struct {
	logFn       func(string, ...any)
	OnConnected func(tunnel.DataTunnel)
	// OnRemoteCandidate is fired for every trickle ICE candidate the
	// VK SFU sends and once per incoming SDP (target=-1) so the caller
	// can extract any candidates carried inline. The Windows joiner
	// uses this to install /32 bypass routes before the candidate
	// reaches Pion.
	OnRemoteCandidate func(target int, candidateOrSDP string)
	ResolveFn         ResolveFunc
	Status            StatusEmitter
	PCConfig          PeerConnectionConfigurer
	AddTracks         AddTunnelTracksFunc
	ReadTrackFn       ReadTrackFunc

	authParams   *VKHeadlessAuthParams
	joinResp     *VKJoinResponse
	vkWs         *websocket.Conn
	vkMu         sync.Mutex
	vkSeq        int
	remotePeerID *int64

	pc          *webrtc.PeerConnection
	sampleTrack *webrtc.TrackLocalStaticSample
	dc          *webrtc.DataChannel
	vp8tunnel   *tunnel.VP8DataTunnel
	obf         *tunnel.TunnelObfuscator
	vp8FPS      int
	vp8Batch    int
	remoteSet   bool
	pendingICE  []webrtc.ICECandidateInit

	reconnectAttempt atomic.Int32
	stopCh           chan struct{}
	stopOnce         sync.Once
}

func NewVKHeadlessJoiner(logFn func(string, ...any), resolveFn ResolveFunc, status StatusEmitter, pcConfig PeerConnectionConfigurer, addTracks AddTunnelTracksFunc, readTrackFn ReadTrackFunc) *VKHeadlessJoiner {
	return &VKHeadlessJoiner{
		logFn:       logFn,
		ResolveFn:   resolveFn,
		Status:      status,
		PCConfig:    pcConfig,
		AddTracks:   addTracks,
		ReadTrackFn: readTrackFn,
		stopCh:      make(chan struct{}),
	}
}

func (h *VKHeadlessJoiner) RunWithParams(jsonParams string) {
	var params VKHeadlessAuthParams
	if err := json.Unmarshal([]byte(jsonParams), &params); err != nil {
		h.logFn("headless: failed to parse auth params: %v", err)
		h.Status.EmitStatusError("bad params: " + err.Error())
		return
	}
	h.authParams = &params
	obf, err := tunnel.NewTunnelObfuscator(tunnel.DeriveSecretFromJoinLink(params.JoinLink))
	if err != nil {
		h.logFn("headless: obfuscator init failed: %v", err)
		h.Status.EmitStatusError("obfuscator init: " + err.Error())
		return
	}
	h.obf = obf
	h.vp8FPS = params.VP8FPS
	h.vp8Batch = params.VP8Batch
	h.logFn("headless: auth params received")
	h.logFn("headless: obf key-source=%q localEpoch=0x%08x", params.JoinLink, obf.LocalEpoch())
	h.logFn("headless:   appVersion=%s protocolVersion=%s vp8Fps=%d vp8Batch=%d",
		params.AppVersion, params.ProtocolVersion, params.VP8FPS, params.VP8Batch)

	h.Status.EmitStatus(common.StatusConnecting)
	if err := h.runOnce(); err != nil {
		h.logFn("headless: %v", err)
		h.Status.EmitStatusError(err.Error())
		return
	}

	for {
		if h.isClosed() {
			return
		}
		h.Status.EmitStatus(common.StatusTunnelLost)
		if !h.waitBeforeRetry(int(h.reconnectAttempt.Load())) {
			return
		}
		h.reconnectAttempt.Add(1)
		if h.isClosed() {
			return
		}
		h.logFn("headless: reconnect attempt #%d", h.reconnectAttempt.Load())
		h.Status.EmitStatus(common.StatusReconnecting)
		if err := h.runOnce(); err != nil {
			var authRotten *vkAuthRottenError
			if errors.As(err, &authRotten) {
				h.logFn("headless: %v, surrendering", err)
				h.Status.EmitStatusError("call failed: " + err.Error())
				return
			}
			h.logFn("headless: %v, will retry", err)
		}
	}
}

func (h *VKHeadlessJoiner) runOnce() error {
	h.resetSessionState()
	if err := h.joinCall(); err != nil {
		return err
	}
	h.connectSFU()
	return nil
}

func (h *VKHeadlessJoiner) waitBeforeRetry(attempt int) bool {
	delay := vkReconnectInitialDelay << attempt
	if delay > vkReconnectMaxDelay || delay <= 0 {
		delay = vkReconnectMaxDelay
	}
	h.logFn("headless: waiting %s before reconnect", delay)
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-timer.C:
		return !h.isClosed()
	case <-h.stopCh:
		return false
	}
}

func (h *VKHeadlessJoiner) isClosed() bool {
	select {
	case <-h.stopCh:
		return true
	default:
		return false
	}
}

func (h *VKHeadlessJoiner) resetSessionState() {
	h.vkMu.Lock()
	ws := h.vkWs
	h.vkWs = nil
	h.vkSeq = 0
	h.vkMu.Unlock()
	if ws != nil {
		ws.Close()
	}
	if h.vp8tunnel != nil {
		h.vp8tunnel.Stop()
		h.vp8tunnel = nil
	}
	if h.dc != nil {
		h.dc.Close()
		h.dc = nil
	}
	if h.pc != nil {
		h.pc.Close()
		h.pc = nil
	}
	h.sampleTrack = nil
	h.remoteSet = false
	h.pendingICE = nil
	h.remotePeerID = nil
	h.joinResp = nil
}

func (h *VKHeadlessJoiner) Close() {
	h.stopOnce.Do(func() { close(h.stopCh) })
	StopCaptchaProxy()
	h.vkMu.Lock()
	ws := h.vkWs
	h.vkWs = nil
	h.vkMu.Unlock()
	if ws != nil {
		ws.Close()
	}
	if h.vp8tunnel != nil {
		h.vp8tunnel.Stop()
	}
	if h.pc != nil {
		h.pc.Close()
	}
}

func (h *VKHeadlessJoiner) joinCall() error {
	apiURL := h.authParams.APIBaseURL
	parsed, err := url.Parse(apiURL)
	if err != nil {
		return fmt.Errorf("bad apiBaseURL: %w", err)
	}
	resolvedIP, err := h.ResolveFn(parsed.Hostname())
	if err != nil {
		return fmt.Errorf("resolve %s: %w", parsed.Hostname(), err)
	}
	h.logFn("headless: resolved %s -> %s", parsed.Hostname(), resolvedIP)

	body := url.Values{
		"method":          {"vchat.joinConversationByLink"},
		"session_key":     {h.authParams.SessionKey},
		"application_key": {h.authParams.ApplicationKey},
		"joinLink":        {h.authParams.JoinLink},
		"anonymToken":     {h.authParams.AnonymToken},
		"isVideo":         {"true"},
		"isAudio":         {"false"},
		"mediaSettings":   {`{"isAudioEnabled":false,"isVideoEnabled":true,"isScreenSharingEnabled":false}`},
		"format":          {"json"},
	}

	client := &http.Client{
		Timeout: 15 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: verifiedSignalingTLS(parsed.Hostname()),
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				_, port, _ := net.SplitHostPort(addr)
				return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, net.JoinHostPort(resolvedIP, port))
			},
		},
	}

	req, err := http.NewRequest("POST", apiURL, strings.NewReader(body.Encode()))
	if err != nil {
		return fmt.Errorf("new request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("User-Agent", common.UserAgent)

	h.logFn("headless: calling joinConversationByLink...")
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("joinConversationByLink: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read join response: %w", err)
	}

	var joinResp VKJoinResponse
	if jsonErr := json.Unmarshal(raw, &joinResp); jsonErr != nil {
		return fmt.Errorf("decode join response: %w (body: %s)", jsonErr, truncateBody(raw))
	}
	if joinResp.Endpoint == "" {
		if rotten := detectVKAuthRotten(raw); rotten != nil {
			return rotten
		}
		return fmt.Errorf("empty endpoint in join response: %s", truncateBody(raw))
	}

	h.joinResp = &joinResp
	h.logFn("headless: joined, turn=%v", joinResp.TurnServer.URLs)
	return nil
}

func detectVKAuthRotten(raw []byte) *vkAuthRottenError {
	var generic map[string]interface{}
	if err := json.Unmarshal(raw, &generic); err != nil {
		return nil
	}
	errCode, _ := generic["error_code"].(string)
	if errCode == "" {
		if codeNum, ok := generic["error_code"].(float64); ok {
			errCode = fmt.Sprintf("%.0f", codeNum)
		}
	}
	if errCode == "" {
		errCode, _ = generic["errorCode"].(string)
	}
	if errCode == "" {
		return nil
	}
	switch errCode {
	case "SESSION_EXPIRED", "AUTH_LOGIN", "SESSION_NOT_FOUND", "INVALID_SESSION_KEY":
		errMsg, _ := generic["error_msg"].(string)
		return &vkAuthRottenError{Code: errCode, Msg: errMsg}
	}
	return nil
}

func truncateBody(raw []byte) string {
	const maxLen = 200
	if len(raw) > maxLen {
		return string(raw[:maxLen]) + "..."
	}
	return string(raw)
}

func (h *VKHeadlessJoiner) connectSFU() {
	parsed, err := url.Parse(h.joinResp.Endpoint)
	if err != nil {
		h.logFn("headless: bad endpoint URL: %s", common.MaskError(err))
		return
	}

	hostname := parsed.Hostname()
	resolvedIP, err := h.ResolveFn(hostname)
	if err != nil {
		h.logFn("headless: DNS resolve failed: %s", common.MaskError(err))
		return
	}
	h.logFn("headless: resolved %s -> %s", common.MaskAddr(hostname), common.MaskAddr(resolvedIP))

	wsURL := h.joinResp.Endpoint +
		"&platform=WEB" +
		"&appVersion=" + h.authParams.AppVersion +
		"&version=" + h.authParams.ProtocolVersion +
		"&device=browser&capabilities=0&clientType=VK&tgt=join"

	dialer := websocket.Dialer{
		HandshakeTimeout: 10 * time.Second,
		WriteBufferSize:  65536,
		TLSClientConfig:  verifiedSignalingTLS(hostname),
		NetDialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			_, port, _ := net.SplitHostPort(addr)
			return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, net.JoinHostPort(resolvedIP, port))
		},
	}

	header := http.Header{}
	header.Set("User-Agent", common.UserAgent)
	header.Set("Origin", "https://vk.com")

	ws, _, err := dialer.Dial(wsURL, header)
	if err != nil {
		h.logFn("headless: WS connect failed: %s", common.MaskError(err))
		return
	}
	h.vkMu.Lock()
	h.vkWs = ws
	h.vkSeq = 0
	h.vkMu.Unlock()
	h.logFn("headless: WS connected")

	h.vkSend("update-media-modifiers", map[string]interface{}{
		"mediaModifiers": map[string]interface{}{"denoise": true, "denoiseAnn": true},
	})
	h.vkSend("change-media-settings", map[string]interface{}{
		"mediaSettings": map[string]interface{}{
			"isAudioEnabled": false, "isVideoEnabled": true,
			"isScreenSharingEnabled": false, "isFastScreenSharingEnabled": false,
			"isAudioSharingEnabled": false, "isAnimojiEnabled": false,
		},
	})

	go h.pingLoop()
	h.readLoop()
}

func (h *VKHeadlessJoiner) vkSend(command string, extra map[string]interface{}) {
	h.vkMu.Lock()
	defer h.vkMu.Unlock()
	if h.vkWs == nil {
		return
	}
	h.vkSeq++
	extra["command"] = command
	extra["sequence"] = h.vkSeq
	out, _ := json.Marshal(extra)
	h.vkWs.WriteMessage(websocket.TextMessage, out)
	h.logFn("headless: -> %s", command)
}

func (h *VKHeadlessJoiner) vkSendTransmitData(participantId int64, payload map[string]interface{}) {
	h.vkMu.Lock()
	defer h.vkMu.Unlock()
	if h.vkWs == nil {
		return
	}
	h.vkSeq++
	payloadJSON, _ := json.Marshal(payload)
	out := fmt.Sprintf(`{"command":"transmit-data","sequence":%d,"participantId":%d,"data":%s}`,
		h.vkSeq, participantId, payloadJSON)
	h.vkWs.WriteMessage(websocket.TextMessage, []byte(out))
}

func (h *VKHeadlessJoiner) pingLoop() {
	for {
		time.Sleep(15 * time.Second)
		h.vkMu.Lock()
		ws := h.vkWs
		h.vkMu.Unlock()
		if ws == nil {
			return
		}
		h.vkMu.Lock()
		ws.WriteMessage(websocket.PingMessage, nil)
		h.vkMu.Unlock()
	}
}

func (h *VKHeadlessJoiner) readLoop() {
	for {
		_, msg, err := h.vkWs.ReadMessage()
		if err != nil {
			h.logFn("headless: WS closed: %s", common.MaskError(err))
			h.Status.EmitStatus(common.StatusTunnelLost)
			return
		}
		if string(msg) == "ping" {
			h.vkMu.Lock()
			h.vkWs.WriteMessage(websocket.TextMessage, []byte("pong"))
			h.vkMu.Unlock()
			continue
		}
		h.handleVKMessage(msg)
	}
}

func (h *VKHeadlessJoiner) handleVKMessage(raw []byte) {
	var msg map[string]interface{}
	if err := json.Unmarshal(raw, &msg); err != nil {
		return
	}

	msgType, _ := msg["type"].(string)
	switch msgType {
	case "notification":
		notif, _ := msg["notification"].(string)
		switch notif {
		case "connection":
			h.handleConnection(msg)
		case "transmitted-data":
			data, _ := msg["data"].(map[string]interface{})
			if data != nil {
				if pid, ok := msg["participantId"].(float64); ok && h.remotePeerID == nil {
					h.onRegisteredPeer(int64(pid))
				}
				h.onTransmittedData(data)
			}
		case "registered-peer":
			if pid, ok := msg["participantId"].(float64); ok {
				h.onRegisteredPeer(int64(pid))
			}
		case "topology-changed":
			topo, _ := msg["topology"].(string)
			h.logFn("headless: topology: %s", topo)
			if topo == "server" {
				h.logFn("headless: ERROR: server topology not supported")
			}
		case "participant-joined", "participant-added":
			h.logFn("headless: <- %s", notif)
		case "participant-left":
			h.logFn("headless: <- %s", notif)
		case "hungup":
			h.logFn("headless: ERROR: call ended (hungup)")
			h.Status.EmitStatusError("call ended")
		}

	case "response":
		seq, _ := msg["sequence"].(float64)
		h.logFn("headless: <- response seq=%d", int(seq))

	case "error":
		errMsg, _ := msg["message"].(string)
		errCode, _ := msg["error"].(string)
		h.logFn("headless: ERROR: %s %s", errCode, errMsg)
	}
}

func (h *VKHeadlessJoiner) handleConnection(msg map[string]interface{}) {
	convParams, ok := msg["conversationParams"].(map[string]interface{})
	if !ok {
		return
	}
	turn, ok := convParams["turn"].(map[string]interface{})
	if !ok {
		return
	}
	urlsRaw, _ := turn["urls"].([]interface{})
	var urls []string
	for _, u := range urlsRaw {
		if s, ok := u.(string); ok {
			urls = append(urls, s)
		}
	}
	username, _ := turn["username"].(string)
	credential, _ := turn["credential"].(string)
	h.joinResp.TurnServer.URLs = urls
	h.joinResp.TurnServer.Username = username
	h.joinResp.TurnServer.Credential = credential
	h.logFn("headless: TURN from connection: %v", urls)

	if h.pc == nil {
		h.initPC()
	}
}

func (h *VKHeadlessJoiner) initPC() {
	var iceServers []webrtc.ICEServer
	if len(h.joinResp.StunServer.URLs) > 0 {
		iceServers = append(iceServers, webrtc.ICEServer{URLs: h.joinResp.StunServer.URLs})
	}
	if len(h.joinResp.TurnServer.URLs) > 0 {
		iceServers = append(iceServers, webrtc.ICEServer{
			URLs:       h.joinResp.TurnServer.URLs,
			Username:   h.joinResp.TurnServer.Username,
			Credential: h.joinResp.TurnServer.Credential,
		})
	}

	mode := h.authParams.TunnelMode

	settingEngine := webrtc.SettingEngine{}
	settingEngine.DisableCloseByDTLS(true)
	settingEngine.DetachDataChannels()
	if h.PCConfig != nil {
		h.PCConfig.ConfigureSettingEngine(&settingEngine)
	}

	pc, err := webrtc.NewAPI(webrtc.WithSettingEngine(settingEngine)).NewPeerConnection(webrtc.Configuration{
		ICEServers: iceServers,
	})
	if err != nil {
		h.logFn("headless: failed to create PC: %v", err)
		return
	}
	h.pc = pc

	h.logFn("headless: tunnel mode: %s", mode)

	if mode == "video" {
		h.sampleTrack = h.AddTracks(pc, h.logFn, "headless")
	}

	negotiated := true
	dcID := uint16(2)
	dc, err := pc.CreateDataChannel("tunnel", &webrtc.DataChannelInit{
		Negotiated: &negotiated,
		ID:         &dcID,
	})
	if err != nil {
		h.logFn("headless: warning: could not create tunnel DC: %v", err)
	} else {
		h.dc = dc
		dc.OnOpen(func() {
			h.logFn("headless: tunnel DC open")
			if mode == "dc" {
				h.reconnectAttempt.Store(0)
				h.logFn("headless: === DC TUNNEL CONNECTED ===")
				h.Status.EmitStatus(common.StatusTunnelConnected)
				if h.OnConnected != nil {
					h.OnConnected(tunnel.NewDCTunnel(dc, h.obf, common.RTPBufSize, h.logFn))
				}
			}
		})
		dc.OnClose(func() {
			h.logFn("headless: tunnel DC closed")
		})
	}

	pc.OnICECandidate(func(candidate *webrtc.ICECandidate) {
		if candidate == nil {
			return
		}
		h.onLocalICECandidate(candidate)
	})
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		h.logFn("headless: PC state: %s", state.String())
		if state == webrtc.PeerConnectionStateFailed || state == webrtc.PeerConnectionStateDisconnected {
			h.logFn("headless: PC %s, closing WS to trigger reconnect", state.String())
			h.vkMu.Lock()
			ws := h.vkWs
			h.vkMu.Unlock()
			if ws != nil {
				ws.Close()
			}
		}
		if mode == "video" && state == webrtc.PeerConnectionStateConnected && h.vp8tunnel == nil {
			h.reconnectAttempt.Store(0)
			h.logFn("headless: === TUNNEL CONNECTED ===")
			h.Status.EmitStatus(common.StatusTunnelConnected)
			h.vp8tunnel = tunnel.NewVP8DataTunnel(h.sampleTrack, h.obf, h.logFn)
			h.vp8tunnel.Start(h.vp8FPS, h.vp8Batch)
			h.vp8tunnel.SendData(tunnel.EncodeVP8Config(h.vp8tunnel.FPS(), h.vp8tunnel.Batch(), 1))
			h.logFn("headless: pushed vp8 config to creator fps=%d batch=%d", h.vp8tunnel.FPS(), h.vp8tunnel.Batch())
			if h.OnConnected != nil {
				h.OnConnected(h.vp8tunnel)
			}
		}
	})
	if mode == "video" {
		pc.OnTrack(func(track *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
			h.logFn("headless: remote track: %s", track.Codec().MimeType)
			go h.ReadTrackFn(track, func(frame []byte) {
				if h.vp8tunnel != nil {
					h.vp8tunnel.HandleFrame(frame)
				}
			}, h.logFn, "headless")
		})
	}

	h.logFn("headless: PC ready, waiting for remote offer")
}

func (h *VKHeadlessJoiner) onRegisteredPeer(pid int64) {
	h.remotePeerID = &pid
	h.logFn("headless: peer registered: %d", pid)
}

func (h *VKHeadlessJoiner) onLocalICECandidate(candidate *webrtc.ICECandidate) {
	if h.remotePeerID == nil {
		return
	}
	candidateJSON := candidate.ToJSON()
	raw, _ := json.Marshal(candidateJSON)
	var parsed interface{}
	json.Unmarshal(raw, &parsed)
	h.vkSendTransmitData(*h.remotePeerID, map[string]interface{}{"candidate": parsed})
}

func (h *VKHeadlessJoiner) onTransmittedData(data map[string]interface{}) {
	if h.pc == nil {
		return
	}

	if candidate, ok := data["candidate"]; ok {
		candidateJSON, _ := json.Marshal(candidate)
		var candidateInit webrtc.ICECandidateInit
		json.Unmarshal(candidateJSON, &candidateInit)
		if h.OnRemoteCandidate != nil {
			h.OnRemoteCandidate(0, candidateInit.Candidate)
		}
		if h.remoteSet {
			h.pc.AddICECandidate(candidateInit)
		} else {
			h.pendingICE = append(h.pendingICE, candidateInit)
		}
	}

	if sdp, ok := data["sdp"].(map[string]interface{}); ok {
		sdpType, _ := sdp["type"].(string)
		sdpStr, _ := sdp["sdp"].(string)
		if h.OnRemoteCandidate != nil {
			h.OnRemoteCandidate(-1, sdpStr)
		}
		h.logFn("headless: remote SDP: %s", sdpType)

		if sdpType == "answer" {
			h.pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: sdpStr})
			h.remoteSet = true
			for _, candidate := range h.pendingICE {
				h.pc.AddICECandidate(candidate)
			}
			h.pendingICE = nil
		} else if sdpType == "offer" {
			h.pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: sdpStr})
			h.remoteSet = true
			for _, candidate := range h.pendingICE {
				h.pc.AddICECandidate(candidate)
			}
			h.pendingICE = nil

			answer, err := h.pc.CreateAnswer(nil)
			if err != nil || h.remotePeerID == nil {
				h.logFn("headless: create answer failed: %v", err)
				return
			}
			h.pc.SetLocalDescription(answer)
			sdpJSON, _ := json.Marshal(answer.SDP)
			h.vkMu.Lock()
			if h.vkWs != nil {
				h.vkSeq++
				raw := fmt.Sprintf(`{"command":"transmit-data","sequence":%d,"participantId":%d,"data":{"sdp":{"sdp":%s,"type":%q},"animojiVersion":2},"participantType":"USER"}`,
					h.vkSeq, *h.remotePeerID, sdpJSON, answer.Type.String())
				h.vkWs.WriteMessage(websocket.TextMessage, []byte(raw))
				h.logFn("headless: -> answer (seq=%d)", h.vkSeq)
			}
			h.vkMu.Unlock()
		}
	}
}
