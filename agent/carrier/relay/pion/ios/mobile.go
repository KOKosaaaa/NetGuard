package ios

import (
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"time"

	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/pion"
	joiner "whitelist-bypass/relay/pion/headless-joiner-common"
	"whitelist-bypass/relay/tunnel"
)

// wbWarmupGateTimeout bounds the cold-start warmup gate on mobile (see
// RelayBridge.MarkReadyOnConfigAck): hold SOCKS until the peer acks our vp8
// config (carrier proven warm both ways) or this elapses as a safety fallback.
const wbWarmupGateTimeout = 12 * time.Second

type HeadlessCallback interface {
	OnLog(msg string)
	OnStatus(status string)
	ResolveHost(hostname string) string
	SaveCache(key string, value string)
	LoadCache(key string) string
	ClearCache(key string)
}

type joinerHandle interface {
	Close()
}

var activeHeadless struct {
	sync.Mutex
	joiner   joinerHandle
	callback HeadlessCallback
	socksLn  net.Listener
	bridge   *tunnel.RelayBridge
	stopped  bool
	platform string
}

type iosStatusEmitter struct {
	statusFn func(string)
}

func (e *iosStatusEmitter) EmitStatus(status string)   { e.statusFn(status) }
func (e *iosStatusEmitter) EmitStatusError(msg string) { e.statusFn("ERROR:" + msg) }

type iosCacheStore struct {
	callback HeadlessCallback
}

func (c *iosCacheStore) Save(key string, value string) { c.callback.SaveCache(key, value) }
func (c *iosCacheStore) Load(key string) string        { return c.callback.LoadCache(key) }

// makeOnConnected builds the OnConnected handler that stands up the SOCKS bridge
// over a freshly-connected tunnel. onConfigAck, when non-nil (WB Stream), wires
// the carrier-warmth config ack: the bridge forwards MsgConfigAck to it (stops
// the session's config resend loop) AND holds SOCKS readiness until that ack
// arrives, so the first MsgConnect isn't lost in the vp8 keyframe-warmup window
// (cold-start hardening). nil keeps the original open-immediately behaviour.
func makeOnConnected(socksPort int, socksUser, socksPass string, logFn func(string, ...any), callback HeadlessCallback, onConfigAck func()) func(tunnel.DataTunnel) {
	return func(tun tunnel.DataTunnel) {
		activeHeadless.Lock()
		if activeHeadless.stopped {
			activeHeadless.Unlock()
			return
		}
		existing := activeHeadless.bridge
		activeHeadless.Unlock()

		if existing != nil {
			existing.SwapTunnel(tun)
			logFn("ios: tunnel swapped after reconnect")
			return
		}

		readBuf := common.VP8BufSize
		if _, ok := tun.(*tunnel.DCTunnel); ok {
			readBuf = common.DCBufSize
		}
		bridge := tunnel.NewRelayBridgeWithAuth(tun, "joiner", readBuf, logFn, socksUser, socksPass)
		bridge.SetPersistentListener(true)
		if onConfigAck != nil {
			bridge.SetOnConfigAck(onConfigAck)
			bridge.MarkReadyOnConfigAck(wbWarmupGateTimeout)
		} else {
			bridge.MarkReady()
		}

		activeHeadless.Lock()
		if activeHeadless.stopped {
			activeHeadless.Unlock()
			bridge.Close()
			return
		}
		activeHeadless.bridge = bridge
		activeHeadless.Unlock()

		socksAddr := fmt.Sprintf("127.0.0.1:%d", socksPort)
		logFn("ios: SOCKS5 proxy starting on %s", socksAddr)
		go func() {
			if err := bridge.ListenSOCKS(socksAddr); err != nil {
				logFn("ios: SOCKS5 listen error: %v", err)
				callback.OnStatus("ERROR:socks listen: " + err.Error())
			}
		}()
	}
}

func makeHelpers(callback HeadlessCallback) (func(string, ...any), joiner.ResolveFunc, *iosStatusEmitter) {
	logFn := func(format string, args ...any) {
		msg := fmt.Sprintf(format, args...)
		callback.OnLog(msg)
	}
	resolveFn := func(hostname string) (string, error) {
		result := callback.ResolveHost(hostname)
		if result == "" {
			return "", fmt.Errorf("empty resolve for %s", hostname)
		}
		return result, nil
	}
	statusEmitter := &iosStatusEmitter{
		statusFn: func(status string) {
			callback.OnStatus(status)
		},
	}
	return logFn, resolveFn, statusEmitter
}

func init() {
	common.MaskingEnabled = true
}

func StartWBStreamHeadless(socksPort int, socksUser, socksPass string, callback HeadlessCallback) {
	StopHeadless()

	activeHeadless.Lock()
	activeHeadless.callback = callback
	activeHeadless.stopped = false
	activeHeadless.platform = "wbstream"
	activeHeadless.Unlock()

	logFn, resolveFn, statusEmitter := makeHelpers(callback)
	wbJoiner := joiner.NewWBStreamHeadlessJoiner(logFn, resolveFn, statusEmitter, nil)
	wbJoiner.OnConnected = makeOnConnected(socksPort, socksUser, socksPass, logFn, callback, wbJoiner.MarkConfigAcked)

	activeHeadless.Lock()
	activeHeadless.joiner = wbJoiner
	activeHeadless.Unlock()

	callback.OnStatus(common.StatusReady)
}

func StartDionHeadless(socksPort int, socksUser, socksPass string, callback HeadlessCallback) {
	StopHeadless()

	activeHeadless.Lock()
	activeHeadless.callback = callback
	activeHeadless.stopped = false
	activeHeadless.platform = "dion"
	activeHeadless.Unlock()

	logFn, resolveFn, statusEmitter := makeHelpers(callback)
	dionJoiner := joiner.NewDionHeadlessJoiner(logFn, resolveFn, statusEmitter, nil)
	dionJoiner.OnConnected = makeOnConnected(socksPort, socksUser, socksPass, logFn, callback, nil)

	activeHeadless.Lock()
	activeHeadless.joiner = dionJoiner
	activeHeadless.Unlock()

	callback.OnStatus(common.StatusReady)
}

func StartTelemostHeadless(socksPort int, socksUser, socksPass string, callback HeadlessCallback) {
	StopHeadless()

	activeHeadless.Lock()
	activeHeadless.callback = callback
	activeHeadless.stopped = false
	activeHeadless.platform = "telemost"
	activeHeadless.Unlock()

	logFn, resolveFn, statusEmitter := makeHelpers(callback)
	tmJoiner := joiner.NewTelemostHeadlessJoiner(logFn, resolveFn, statusEmitter, nil, pion.AddTunnelTracks, pion.ReadTrack)
	tmJoiner.OnConnected = makeOnConnected(socksPort, socksUser, socksPass, logFn, callback, nil)

	activeHeadless.Lock()
	activeHeadless.joiner = tmJoiner
	activeHeadless.Unlock()

	callback.OnStatus(common.StatusReady)
}

func StartVKHeadless(socksPort int, socksUser, socksPass string, joinLink, displayName, tunnelMode string, vp8Fps, vp8Batch int, callback HeadlessCallback) {
	StopHeadless()

	activeHeadless.Lock()
	activeHeadless.callback = callback
	activeHeadless.stopped = false
	activeHeadless.platform = "vk"
	activeHeadless.Unlock()

	logFn, resolveFn, statusEmitter := makeHelpers(callback)
	vkJoiner := joiner.NewVKHeadlessJoiner(logFn, resolveFn, statusEmitter, nil, pion.AddTunnelTracks, pion.ReadTrack)
	vkJoiner.OnConnected = makeOnConnected(socksPort, socksUser, socksPass, logFn, callback, nil)

	activeHeadless.Lock()
	activeHeadless.joiner = vkJoiner
	activeHeadless.Unlock()

	go func() {
		authJSON, err := joiner.RunVKAuth(joinLink, displayName, logFn, statusEmitter.statusFn, &iosCacheStore{callback: callback}, resolveFn)
		if err != nil {
			logFn("vk-auth: failed: %v", err)
			callback.OnStatus("ERROR:" + err.Error())
			return
		}
		var params map[string]interface{}
		if json.Unmarshal([]byte(authJSON), &params) == nil {
			params["tunnelMode"] = tunnelMode
			if vp8Fps > 0 {
				params["vp8Fps"] = vp8Fps
			}
			if vp8Batch > 0 {
				params["vp8Batch"] = vp8Batch
			}
			if patched, err := json.Marshal(params); err == nil {
				authJSON = string(patched)
			}
		}
		logFn("vk-auth: sending join params to relay mode=%s vp8Fps=%d vp8Batch=%d", tunnelMode, vp8Fps, vp8Batch)
		vkJoiner.RunWithParams(authJSON)
	}()
}

func SendJoinParams(jsonParams string) {
	activeHeadless.Lock()
	currentJoiner := activeHeadless.joiner
	platform := activeHeadless.platform
	activeHeadless.Unlock()

	if currentJoiner == nil {
		return
	}

	switch platform {
	case "telemost":
		if tmJoiner, ok := currentJoiner.(*joiner.TelemostHeadlessJoiner); ok {
			go tmJoiner.RunWithParams(jsonParams)
		}
	case "vk":
		if vkJoiner, ok := currentJoiner.(*joiner.VKHeadlessJoiner); ok {
			go vkJoiner.RunWithParams(jsonParams)
		}
	case "wbstream":
		if wbJoiner, ok := currentJoiner.(*joiner.WBStreamHeadlessJoiner); ok {
			go wbJoiner.RunWithParams(jsonParams)
		}
	case "dion":
		if dionJoiner, ok := currentJoiner.(*joiner.DionHeadlessJoiner); ok {
			go dionJoiner.RunWithParams(jsonParams)
		}
	}
}

func StopCaptchaProxy() {
	joiner.StopCaptchaProxy()
}

func StopHeadless() {
	activeHeadless.Lock()
	activeHeadless.stopped = true
	currentJoiner := activeHeadless.joiner
	socksLn := activeHeadless.socksLn
	bridge := activeHeadless.bridge
	activeHeadless.joiner = nil
	activeHeadless.socksLn = nil
	activeHeadless.bridge = nil
	activeHeadless.callback = nil
	activeHeadless.platform = ""
	activeHeadless.Unlock()

	joiner.StopCaptchaProxy()
	if currentJoiner != nil {
		currentJoiner.Close()
	}
	if bridge != nil {
		bridge.Close()
	}
	if socksLn != nil {
		socksLn.Close()
	}
}
