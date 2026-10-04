package telemost

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	mathrand "math/rand"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/pion/interceptor"
	"github.com/pion/interceptor/pkg/cc"
	"github.com/pion/interceptor/pkg/gcc"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/common"
)

const (
	APIBase = "https://cloud-api.yandex.ru/telemost_front/v2/telemost"
	Origin  = "https://telemost.yandex.ru"
)

var CapabilitiesOffer = map[string][]string{
	"offerAnswerMode":                       {"SEPARATE"},
	"initialSubscriberOffer":                {"ON_HELLO"},
	"slotsMode":                             {"FROM_CONTROLLER"},
	"simulcastMode":                         {"DISABLED", "STATIC"},
	"selfVadStatus":                         {"FROM_SERVER", "FROM_CLIENT"},
	"dataChannelSharing":                    {"TO_RTP"},
	"videoEncoderConfig":                    {"NO_CONFIG", "ONLY_INIT_CONFIG", "RUNTIME_CONFIG"},
	"dataChannelVideoCodec":                 {"VP8", "UNIQUE_CODEC_FROM_TRACK_DESCRIPTION"},
	"bandwidthLimitationReason":             {"BANDWIDTH_REASON_DISABLED", "BANDWIDTH_REASON_ENABLED"},
	"sdkDefaultDeviceManagement":            {"SDK_DEFAULT_DEVICE_MANAGEMENT_DISABLED", "SDK_DEFAULT_DEVICE_MANAGEMENT_ENABLED"},
	"joinOrderLayout":                       {"JOIN_ORDER_LAYOUT_DISABLED", "JOIN_ORDER_LAYOUT_ENABLED"},
	"pinLayout":                             {"PIN_LAYOUT_DISABLED"},
	"sendSelfViewVideoSlot":                 {"SEND_SELF_VIEW_VIDEO_SLOT_DISABLED", "SEND_SELF_VIEW_VIDEO_SLOT_ENABLED"},
	"serverLayoutTransition":                {"SERVER_LAYOUT_TRANSITION_DISABLED"},
	"sdkPublisherOptimizeBitrate":           {"SDK_PUBLISHER_OPTIMIZE_BITRATE_DISABLED", "SDK_PUBLISHER_OPTIMIZE_BITRATE_FULL", "SDK_PUBLISHER_OPTIMIZE_BITRATE_ONLY_SELF"},
	"sdkNetworkLostDetection":               {"SDK_NETWORK_LOST_DETECTION_DISABLED"},
	"sdkNetworkPathMonitor":                 {"SDK_NETWORK_PATH_MONITOR_DISABLED"},
	"publisherVp9":                          {"PUBLISH_VP9_DISABLED", "PUBLISH_VP9_ENABLED"},
	"svcMode":                               {"SVC_MODE_DISABLED", "SVC_MODE_L3T3", "SVC_MODE_L3T3_KEY"},
	"subscriberOfferAsyncAck":               {"SUBSCRIBER_OFFER_ASYNC_ACK_DISABLED", "SUBSCRIBER_OFFER_ASYNC_ACK_ENABLED"},
	"subscriberDtlsPassiveMode":             {"SUBSCRIBER_DTLS_PASSIVE_MODE_DISABLED", "SUBSCRIBER_DTLS_PASSIVE_MODE_ENABLED"},
	"androidBluetoothRoutingFix":            {"ANDROID_BLUETOOTH_ROUTING_FIX_DISABLED"},
	"fixedIceCandidatesPoolSize":            {"FIXED_ICE_CANDIDATES_POOL_SIZE_DISABLED"},
	"sdkAndroidTelecomIntegration":          {"SDK_ANDROID_TELECOM_INTEGRATION_DISABLED"},
	"setActiveCodecsMode":                   {"SET_ACTIVE_CODECS_MODE_DISABLED", "SET_ACTIVE_CODECS_MODE_VIDEO_ONLY"},
	"publisherOpusDred":                     {"PUBLISHER_OPUS_DRED_DISABLED"},
	"publisherOpusLowBitrate":               {"PUBLISHER_OPUS_LOW_BITRATE_DISABLED"},
	"sdkAndroidDestroySessionOnTaskRemoved": {"SDK_ANDROID_DESTROY_SESSION_ON_TASK_REMOVED_DISABLED"},
	"svcModes":                              {"FALSE"},
	"reportTelemetryModes":                  {"TRUE"},
	"keepDefaultDevicesModes":               {"FALSE"},
}

var StartupSlotSizes = [][][2]int{
	{{0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}},
	{{464, 261}, {464, 261}, {464, 261}, {336, 189}, {272, 153}, {272, 153}, {272, 153}, {272, 153}, {224, 126}, {224, 126}, {224, 126}, {224, 126}},
	{{464, 261}, {464, 261}, {464, 261}, {336, 189}, {272, 153}, {272, 153}, {272, 153}, {272, 153}, {224, 126}, {224, 126}, {224, 126}, {224, 126}},
	{{672, 378}, {672, 378}, {464, 261}, {336, 189}, {320, 180}, {320, 180}, {320, 180}, {320, 180}, {272, 153}, {272, 153}, {224, 126}, {224, 126}},
}

type SlotBindEvent struct {
	Slot          int
	ParticipantID string
	Mid           string
	Reason        string
}

type Client struct {
	HTTP       *http.Client
	Cookie     string
	UserAgent  string
	AppVersion string
	InstanceID string
}

func NewAPI(settingEngine *webrtc.SettingEngine) (*webrtc.API, error) {
	return newAPIInternal(settingEngine, false)
}

// NewPeerConnectionGCC builds a PC like NewPeerConnection but, when the
// WLB_GCC env is set, adds a send-side Google Congestion Control interceptor
// (transport-cc driven). Use ONLY for the publisher PC: it paces outgoing
// media to the bandwidth the SFU's transport-cc feedback reveals, mimicking a
// real libwebrtc client instead of flooding. The Yandex SFU negotiates
// transport-cc (not goog-remb) on the publish path, so it meters our stream
// by this feedback loop; without a controller we flood and the SFU clamps us.
func NewPeerConnectionGCC(config webrtc.Configuration) (*webrtc.PeerConnection, error) {
	api, err := newAPIInternal(nil, true)
	if err != nil {
		return nil, err
	}
	return api.NewPeerConnection(config)
}

func newAPIInternal(settingEngine *webrtc.SettingEngine, enableGCC bool) (*webrtc.API, error) {
	mediaEngine := &webrtc.MediaEngine{}
	if err := mediaEngine.RegisterDefaultCodecs(); err != nil {
		return nil, err
	}
	for _, uri := range []string{
		"urn:ietf:params:rtp-hdrext:toffset",
		"http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
		"urn:3gpp:video-orientation",
		"http://www.webrtc.org/experiments/rtp-hdrext/playout-delay",
		"http://www.webrtc.org/experiments/rtp-hdrext/video-content-type",
		"http://www.webrtc.org/experiments/rtp-hdrext/video-timing",
		"http://www.webrtc.org/experiments/rtp-hdrext/color-space",
	} {
		if err := mediaEngine.RegisterHeaderExtension(
			webrtc.RTPHeaderExtensionCapability{URI: uri},
			webrtc.RTPCodecTypeVideo,
		); err != nil {
			return nil, fmt.Errorf("register header extension %s: %w", uri, err)
		}
	}
	// Audio header extensions: ssrc-audio-level (RFC 6464) is what Yandex
	// Telemost SFU uses for VAD / active-speaker selection. Without it the
	// SFU treats publisher's stream as "silent" and never forwards audio
	// to other participants in the room, no matter how many Opus packets
	// we push. abs-send-time is the standard congestion-control twin.
	for _, uri := range []string{
		"urn:ietf:params:rtp-hdrext:ssrc-audio-level",
		"http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
	} {
		if err := mediaEngine.RegisterHeaderExtension(
			webrtc.RTPHeaderExtensionCapability{URI: uri},
			webrtc.RTPCodecTypeAudio,
		); err != nil {
			return nil, fmt.Errorf("register audio header extension %s: %w", uri, err)
		}
	}
	registry := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptors(mediaEngine, registry); err != nil {
		return nil, err
	}
	// Audio-level injector: Yandex Telemost SFU forwards a publisher's
	// Opus stream only when its VAD says "voice present". VAD is driven by
	// the ssrc-audio-level RTP header extension (RFC 6464). Pion's
	// WriteSample doesn't populate it, so we hook into the outbound
	// pipeline and stamp every Opus packet with V=1 + a loud-ish level
	// (-25 dBov). Constant value is fine — Tsoy is always playing, no
	// real voice gating happens.
	registry.Add(&audioLevelFactory{})

	// Send-side congestion control (publisher only). The SFU drives our
	// publish bitrate via transport-cc feedback; without a controller we
	// flood ~6.5 Mbit, overshoot, and the SFU clamps the forward to ~1 Mbit.
	// GCC consumes the transport-cc feedback and paces outgoing media to the
	// estimated available bandwidth, ramping smoothly like a real client.
	if enableGCC && os.Getenv("WLB_GCC") != "" {
		initBps, maxBps, minBps := 1_000_000, 8_000_000, 200_000
		if v := os.Getenv("WLB_GCC_INIT"); v != "" {
			if n, e := strconv.Atoi(v); e == nil && n > 0 {
				initBps = n * 1000
			}
		}
		if v := os.Getenv("WLB_GCC_MAX"); v != "" {
			if n, e := strconv.Atoi(v); e == nil && n > 0 {
				maxBps = n * 1000
			}
		}
		ccFactory, err := cc.NewInterceptor(func() (cc.BandwidthEstimator, error) {
			return gcc.NewSendSideBWE(
				gcc.SendSideBWEInitialBitrate(initBps),
				gcc.SendSideBWEMaxBitrate(maxBps),
				gcc.SendSideBWEMinBitrate(minBps),
				// NoOp pacer: GCC only ESTIMATES; it must not delay/queue our
				// flooded raw VP8 samples (its leaky-bucket pacer backs up and
				// chokes the tunnel). The estimate is published via
				// common.GCCTargetBps and the VP8 writer paces to it.
				gcc.SendSideBWEPacer(gcc.NewNoOpPacer()),
			)
		})
		if err != nil {
			return nil, fmt.Errorf("gcc interceptor: %w", err)
		}
		ccFactory.OnNewPeerConnection(func(id string, bwe cc.BandwidthEstimator) {
			log.Printf("[gcc] estimator ready init=%d max=%d min=%d kbps", initBps/1000, maxBps/1000, minBps/1000)
			go func() {
				for {
					time.Sleep(2 * time.Second)
					t := bwe.GetTargetBitrate()
					common.GCCTargetBps.Store(int64(t))
					log.Printf("[gcc] target=%d kbps", t/1000)
				}
			}()
		})
		registry.Add(ccFactory)
	}

	opts := []func(*webrtc.API){
		webrtc.WithMediaEngine(mediaEngine),
		webrtc.WithInterceptorRegistry(registry),
	}
	if settingEngine != nil {
		opts = append(opts, webrtc.WithSettingEngine(*settingEngine))
	}
	return webrtc.NewAPI(opts...), nil
}

func NewPeerConnection(config webrtc.Configuration) (*webrtc.PeerConnection, error) {
	api, err := NewAPI(nil)
	if err != nil {
		return nil, err
	}
	return api.NewPeerConnection(config)
}

// audioLevelInterceptor stamps every outbound Opus RTP packet with the
// ssrc-audio-level header extension carrying V=1, level=25 (~ -25 dBov, a
// medium-loud speech level). Yandex Telemost SFU treats this as "active
// speaker, please forward".
type audioLevelInterceptor struct {
	interceptor.NoOp
}

type audioLevelFactory struct{}

func (f *audioLevelFactory) NewInterceptor(id string) (interceptor.Interceptor, error) {
	return &audioLevelInterceptor{}, nil
}

func (a *audioLevelInterceptor) BindLocalStream(info *interceptor.StreamInfo, writer interceptor.RTPWriter) interceptor.RTPWriter {
	if !strings.EqualFold(info.MimeType, webrtc.MimeTypeOpus) {
		return writer
	}
	var extID uint8
	for _, ext := range info.RTPHeaderExtensions {
		if ext.URI == "urn:ietf:params:rtp-hdrext:ssrc-audio-level" {
			extID = uint8(ext.ID)
			break
		}
	}
	if extID == 0 {
		return writer
	}
	return interceptor.RTPWriterFunc(func(header *rtp.Header, payload []byte, attributes interceptor.Attributes) (int, error) {
		_ = header.SetExtension(extID, []byte{0x80 | 25})
		return writer.Write(header, payload, attributes)
	})
}

// MungeSDPSubBAS injects b=AS/b=TIAS + x-google-max-bitrate into the video
// section of a SUBSCRIBER answer (env WLB_SUB_BAS=<kbps>), telling the SFU we
// can receive up to that rate. A headless pion subscriber otherwise sends no
// receive-bandwidth hint, so the SFU may cap the downlink to a conservative
// default and mask the stream's real sustainable rate. Reversible test knob.
func MungeSDPSubBAS(sdp string) string {
	kbps := 0
	if v := os.Getenv("WLB_SUB_BAS"); v != "" {
		if k, err := strconv.Atoi(v); err == nil && k > 0 {
			kbps = k
		}
	}
	if kbps == 0 {
		return sdp
	}
	lines := strings.Split(sdp, "\r\n")
	out := make([]string, 0, len(lines)+4)
	inVideo := false
	basDone := false
	for _, line := range lines {
		if strings.HasPrefix(line, "m=") {
			inVideo = strings.HasPrefix(line, "m=video")
			basDone = false
		}
		if inVideo && strings.HasPrefix(line, "a=fmtp:") {
			line = line + fmt.Sprintf(";x-google-max-bitrate=%d;x-google-min-bitrate=%d;x-google-start-bitrate=%d", kbps, kbps/4, kbps/2)
		}
		out = append(out, line)
		if inVideo && !basDone && strings.HasPrefix(line, "c=") {
			out = append(out, fmt.Sprintf("b=AS:%d", kbps))
			out = append(out, fmt.Sprintf("b=TIAS:%d", kbps*1000))
			basDone = true
		}
	}
	return strings.Join(out, "\r\n")
}

func MungeSDPAddVideoContent(sdp string) string {
	// Optional publisher bitrate declaration: WLB_PUB_BAS=<kbps> injects
	// b=AS/b=TIAS into the video m-section and x-google-*-bitrate into its
	// fmtp lines, signalling the SFU we intend to publish a high bitrate.
	// Default (unset) leaves the SDP untouched. Reversible test knob.
	pubBasKbps := 0
	if v := os.Getenv("WLB_PUB_BAS"); v != "" {
		if k, err := strconv.Atoi(v); err == nil && k > 0 {
			pubBasKbps = k
		}
	}

	lines := strings.Split(sdp, "\r\n")
	out := make([]string, 0, len(lines)+6)
	// Track whether the current m-section is video / audio so we can emit
	// the right a=content attribute. Yandex Telemost SFU only allocates
	// publish slots for tracks that carry an explicit content tag; without
	// it the m=audio section is parsed but its packets are never forwarded
	// to subscribers (audioSlots: [] in slotsConfig). The fix mirrors what
	// the official client emits in its publish SDP.
	inVideo := false
	inAudio := false
	inserted := false
	flush := func() {
		if inserted {
			return
		}
		if inVideo {
			out = append(out, "a=content:speaker,main")
			inserted = true
		} else if inAudio {
			out = append(out, "a=content:main")
			inserted = true
		}
	}
	basDone := false
	for _, line := range lines {
		if strings.HasPrefix(line, "m=") {
			flush()
			inVideo = strings.HasPrefix(line, "m=video")
			inAudio = strings.HasPrefix(line, "m=audio")
			inserted = false
			basDone = false
		}
		// Append x-google-*-bitrate to the video fmtp lines (per-track hint).
		if pubBasKbps > 0 && inVideo && strings.HasPrefix(line, "a=fmtp:") {
			line = line + fmt.Sprintf(";x-google-max-bitrate=%d;x-google-min-bitrate=%d;x-google-start-bitrate=%d",
				pubBasKbps, pubBasKbps/4, pubBasKbps/2)
		}
		out = append(out, line)
		// Inject b=AS/b=TIAS right after the video c= line (session bw hint).
		if pubBasKbps > 0 && inVideo && !basDone && strings.HasPrefix(line, "c=") {
			out = append(out, fmt.Sprintf("b=AS:%d", pubBasKbps))
			out = append(out, fmt.Sprintf("b=TIAS:%d", pubBasKbps*1000))
			basDone = true
		}
		if (inVideo || inAudio) && !inserted && strings.HasPrefix(line, "a=mid:") {
			flush()
		}
	}
	flush()
	return strings.Join(out, "\r\n")
}

func SlotsConfigBindings(v interface{}) []SlotBindEvent {
	m, ok := v.(map[string]interface{})
	if !ok {
		return nil
	}
	slots, _ := m["slots"].([]interface{})
	var out []SlotBindEvent
	for idx, s := range slots {
		sm, _ := s.(map[string]interface{})
		if pv, _ := sm["participantVideoByMid"].(map[string]interface{}); pv != nil {
			pid, _ := pv["participantId"].(string)
			mid, _ := pv["mid"].(string)
			reason, _ := pv["limitationReason"].(string)
			out = append(out, SlotBindEvent{Slot: idx, ParticipantID: pid, Mid: mid, Reason: reason})
			continue
		}
		if p, _ := sm["participant"].(map[string]interface{}); p != nil {
			pid, _ := p["participantId"].(string)
			out = append(out, SlotBindEvent{Slot: idx, ParticipantID: pid})
		}
	}
	return out
}

func BriefJSON(v interface{}) string {
	const max = 240
	b, err := json.Marshal(v)
	if err != nil {
		return fmt.Sprintf("<json err: %v>", err)
	}
	if len(b) > max {
		return string(b[:max]) + "...(+" + fmt.Sprintf("%d", len(b)-max) + "B)"
	}
	return string(b)
}

func (c *Client) Do(method, path string, body interface{}) ([]byte, int, error) {
	var bodyReader io.Reader
	if body != nil {
		data, _ := json.Marshal(body)
		bodyReader = strings.NewReader(string(data))
	}
	req, err := http.NewRequest(method, APIBase+path, bodyReader)
	if err != nil {
		return nil, 0, err
	}
	ua := c.UserAgent
	if ua == "" {
		ua = common.UserAgent
	}
	instanceID := c.InstanceID
	if instanceID == "" {
		instanceID = uuid.New().String()
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Origin", Origin)
	req.Header.Set("Referer", Origin+"/")
	req.Header.Set("Client-Instance-Id", instanceID)
	if c.Cookie != "" {
		req.Header.Set("Cookie", c.Cookie)
	}
	if c.AppVersion != "" {
		req.Header.Set("X-Telemost-Client-Version", c.AppVersion)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	client := c.HTTP
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	return data, resp.StatusCode, err
}

func (c *Client) RequestStates(joinURI, peerID string) error {
	confURL := url.QueryEscape(joinURI)
	body := map[string]interface{}{
		"peers":       []map[string]string{{"peer_id": peerID}},
		"permissions": map[string]interface{}{},
		"conference":  map[string]interface{}{"version": -1},
	}
	r, status, err := c.Do("POST", "/conferences/"+confURL+"/request-states", body)
	if err != nil {
		return err
	}
	if status != 200 {
		return fmt.Errorf("status %d: %s", status, string(r))
	}
	return nil
}

func jitterSize(width int, rnd *mathrand.Rand) (int, int) {
	if width == 0 {
		return 0, 0
	}
	w := width + rnd.Intn(11) - 5
	return w, w * 9 / 16
}

func slotsMessageWithSizes(key int, template [][2]int, rnd *mathrand.Rand) map[string]interface{} {
	slots := make([]map[string]interface{}, len(template))
	for i, wh := range template {
		w, h := wh[0], wh[1]
		if rnd != nil {
			w, h = jitterSize(wh[0], rnd)
		}
		slots[i] = map[string]interface{}{"width": w, "height": h}
	}
	return map[string]interface{}{
		"uid": uuid.New().String(),
		"setSlots": map[string]interface{}{
			"slots":              slots,
			"audioSlotsCount":    0,
			"key":                key,
			"shutdownAllVideo":   nil,
			"withSelfView":       true,
			"selfViewVisibility": "ON_LOADING_THEN_SHOW",
			"gridConfig":         map[string]interface{}{},
		},
	}
}

// CapabilitiesNoLimit returns a clone of CapabilitiesOffer that advertises we
// do NOT support server-side bandwidth limitation or publisher bitrate
// optimization (offer only the *_DISABLED value for each). Capability
// negotiation forces the server to echo DISABLED, so its serverHello answer
// should carry bandwidthLimitationReason=DISABLED instead of ENABLED. Test
// knob (WLB_CAP_NOLIMIT) to see if the ~1.2 Mbit forward cap is driven by the
// server's bandwidth-limitation feature.
func CapabilitiesNoLimit() map[string][]string {
	out := make(map[string][]string, len(CapabilitiesOffer))
	for k, v := range CapabilitiesOffer {
		out[k] = v
	}
	out["bandwidthLimitationReason"] = []string{"BANDWIDTH_REASON_DISABLED"}
	out["sdkPublisherOptimizeBitrate"] = []string{"SDK_PUBLISHER_OPTIMIZE_BITRATE_DISABLED"}
	// Force the server to treat our publish as a single full-quality stream,
	// not a layered SVC/simulcast base layer (server picked SVC_MODE_L3T3_KEY,
	// which may budget bitrate across 3 spatial layers we never send).
	out["svcMode"] = []string{"SVC_MODE_DISABLED"}
	out["simulcastMode"] = []string{"DISABLED"}
	return out
}

func SetSlotsMessage(key int) map[string]interface{} {
	rnd := mathrand.New(mathrand.NewSource(time.Now().UnixNano()))
	return slotsMessageWithSizes(key, StartupSlotSizes[len(StartupSlotSizes)-1], rnd)
}

func StartupSetSlotsMessage(i, key int) map[string]interface{} {
	rnd := mathrand.New(mathrand.NewSource(time.Now().UnixNano() + int64(i)))
	return slotsMessageWithSizes(key, StartupSlotSizes[i], rnd)
}

// SetSlotsPinMessage is an EMPIRICAL attempt to pin a specific publisher's
// video into our slot 0 so the SFU forwards it to us (measurer-pin, stand gap
// #2). Outgoing slot format is guessed from the incoming slotsConfig binding
// shape ({participantId, mid}): we put the publisher's participantId on a
// full-size slot and request a non-disabled pin layout. If the SFU forwards
// video after this, the format is right; if not, iterate (try "pin"/"pinned"
// fields, pinLayout in hello capabilities, etc).
// SetSlotsGridMessage assigns up to len(slots) publisher participantIds to
// separate grid tiles (GRID layout, not single-pin), so the SFU forwards ALL
// of their videos to us at once. Used to measure multi-stream aggregate
// throughput to one subscriber in one room.
func SetSlotsGridMessage(key int, participantIDs []string) map[string]interface{} {
	sizes := StartupSlotSizes[len(StartupSlotSizes)-1]
	slots := make([]map[string]interface{}, len(sizes))
	for i, wh := range sizes {
		slots[i] = map[string]interface{}{"width": wh[0], "height": wh[1]}
		if i < len(participantIDs) {
			slots[i]["participantId"] = participantIDs[i]
			slots[i]["pinned"] = true
		}
	}
	return map[string]interface{}{
		"uid": uuid.New().String(),
		"setSlots": map[string]interface{}{
			"slots":              slots,
			"audioSlotsCount":    0,
			"key":                key,
			"shutdownAllVideo":   nil,
			"withSelfView":       true,
			"selfViewVisibility": "ON_LOADING_THEN_SHOW",
			"gridConfig":         map[string]interface{}{},
			"pinLayout":          "PIN_LAYOUT_DISABLED",
		},
	}
}

func SetSlotsPinMessage(key int, participantID string) map[string]interface{} {
	slots := make([]map[string]interface{}, len(StartupSlotSizes[len(StartupSlotSizes)-1]))
	for i, wh := range StartupSlotSizes[len(StartupSlotSizes)-1] {
		slots[i] = map[string]interface{}{"width": wh[0], "height": wh[1]}
	}
	// Pin the publisher into slot 0 (biggest tile).
	slots[0]["participantId"] = participantID
	slots[0]["pinned"] = true
	return map[string]interface{}{
		"uid": uuid.New().String(),
		"setSlots": map[string]interface{}{
			"slots":              slots,
			"audioSlotsCount":    0,
			"key":                key,
			"shutdownAllVideo":   nil,
			"withSelfView":       true,
			"selfViewVisibility": "ON_LOADING_THEN_SHOW",
			"gridConfig":         map[string]interface{}{},
			"pinLayout":          "PIN_LAYOUT_ENABLED",
			"pinnedParticipantId": participantID,
		},
	}
}

func SetSlotsOffsetMessage(offset int) map[string]interface{} {
	return map[string]interface{}{
		"uid":            uuid.New().String(),
		"setSlotsOffset": map[string]interface{}{"offset": offset},
	}
}

func SdkCodecsInfoMessage() map[string]interface{} {
	return map[string]interface{}{
		"uid": uuid.New().String(),
		"sdkCodecsInfo": map[string]interface{}{
			"vp8": map[string]interface{}{
				"supported": "CODEC_FEATURE_SUPPORTED",
				"hwDecode":  "CODEC_FEATURE_NOT_SUPPORTED",
				"hwEncode":  "CODEC_FEATURE_NOT_SUPPORTED",
				"isoString": "vp8",
			},
		},
	}
}

func UpdatePublisherTrackDescriptionMessage(pc *webrtc.PeerConnection, audioLabel, videoLabel string) map[string]interface{} {
	descs := []map[string]interface{}{}
	for _, tr := range pc.GetTransceivers() {
		sender := tr.Sender()
		if sender == nil || sender.Track() == nil {
			continue
		}
		kind := strings.ToUpper(sender.Track().Kind().String())
		mid := tr.Mid()
		label := videoLabel
		groupID := 1
		if kind == "AUDIO" {
			label = audioLabel
			groupID = 2
		}
		descs = append(descs, map[string]interface{}{
			"mid":            mid,
			"transceiverMid": mid,
			"kind":           kind,
			"priority":       0,
			"label":          label,
			"codecs":         map[string]interface{}{},
			"groupId":        groupID,
			"description":    "",
		})
	}
	return map[string]interface{}{
		"uid": uuid.New().String(),
		"updatePublisherTrackDescription": map[string]interface{}{
			"publisherTrackDescriptions": descs,
		},
	}
}
