package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"whitelist-bypass/relay/androidbind"
	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/pion"
	"whitelist-bypass/relay/pion/android"
	"whitelist-bypass/relay/tunnel"
)

type stdLogger struct{}

func (s stdLogger) OnLog(msg string) {
	log.Print(msg)
}

func main() {
	mode := flag.String("mode", "", "joiner or creator")
	wsPort := flag.Int("ws-port", 9000, "WebSocket port for browser connection")
	socksPort := flag.Int("socks-port", 1080, "SOCKS5 proxy port (joiner mode only)")
	socksUser := flag.String("socks-user", "", "SOCKS5 proxy username")
	socksPass := flag.String("socks-pass", "", "SOCKS5 proxy password")
	controlPort := flag.Int("control-port", 0, "127.0.0.1 HTTP control port for hot-reload/status (0=off); WLB_CONTROL_PORT env overrides")
	flag.String("local-ip", "", "local IP address (unused, passed via hook)")
	flag.Parse()

	// hot-reload holders: the current joiner bridge + tunnel, published by
	// newPersistentJoinerBridge so the control channel can reset them in-place
	// (no leave/rejoin). Same idea as the creator's Bridge.activeTun/activeBridge.
	var (
		hrTun    tunnel.DataTunnel
		hrBridge *tunnel.RelayBridge
		hrMu     sync.Mutex
	)
	hrPublish := func(tun tunnel.DataTunnel, br *tunnel.RelayBridge) {
		hrMu.Lock()
		hrTun = tun
		hrBridge = br
		hrMu.Unlock()
	}
	hotReload := func() string {
		hrMu.Lock()
		tun := hrTun
		br := hrBridge
		hrMu.Unlock()
		if br == nil {
			return "no active tunnel yet — nothing to reset"
		}
		// ResetState lives on the carrier tunnel (VP8); DC tunnels don't have it.
		if vt, ok := tun.(*tunnel.VP8DataTunnel); ok {
			vt.ResetState()
		}
		br.Reset() // close all SOCKS conns; persistent listener stays up
		return "ok: carrier reset (PeerConnection preserved)"
	}
	serveControl := func(addr string) {
		mux := http.NewServeMux()
		mux.HandleFunc("/reload", func(w http.ResponseWriter, r *http.Request) {
			msg := hotReload()
			log.Printf("[control] /reload -> %s", msg)
			fmt.Fprintln(w, msg)
		})
		mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
			hrMu.Lock()
			tun := hrTun
			br := hrBridge
			hrMu.Unlock()
			fmt.Fprintf(w, "connected=%v\n", br != nil)
			if vt, ok := tun.(*tunnel.VP8DataTunnel); ok {
				sent, recv, recvFail, dynKbps := vt.Stats()
				fmt.Fprintf(w, "sent_frames=%d recv_frames=%d recv_fail=%d dyn_kbps=%d\n", sent, recv, recvFail, dynKbps)
			}
			if br != nil {
				tcp, udp, nextID := br.Stats()
				fmt.Fprintf(w, "tcp_conns=%d udp_conns=%d next_conn_id=%d\n", tcp, udp, nextID)
			}
		})
		log.Printf("[control] HTTP control channel on http://%s (/reload, /status)", addr)
		if err := (&http.Server{Addr: addr, Handler: mux}).ListenAndServe(); err != nil {
			log.Printf("[control] server stopped: %v", err)
		}
	}
	cpEff := *controlPort
	if v := os.Getenv("WLB_CONTROL_PORT"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			cpEff = n
		}
	}
	if cpEff > 0 {
		go serveControl(fmt.Sprintf("127.0.0.1:%d", cpEff))
	}

	if *mode == "" {
		fmt.Fprintf(os.Stderr, "Usage: relay --mode dc-joiner|dc-creator|vk-video-joiner|vk-video-creator|telemost-video-joiner|telemost-video-creator\n")
		os.Exit(1)
	}

	cb := stdLogger{}

	type signalingClient interface {
		HandleSignaling(http.ResponseWriter, *http.Request)
	}

	startVideo := func(name string, client signalingClient, onConnected func(tunnel.DataTunnel)) {
		mux := http.NewServeMux()
		mux.HandleFunc("/signaling", client.HandleSignaling)
		addr := fmt.Sprintf("127.0.0.1:%d", *wsPort)
		log.Printf("%s: signaling on %s", name, addr)
		log.Fatal(http.ListenAndServe(addr, mux))
	}

	startJoinerBridge := func(tun tunnel.DataTunnel, readBuf int) {
		rb := tunnel.NewRelayBridgeWithAuth(tun, "joiner", readBuf, log.Printf, *socksUser, *socksPass)
		rb.MarkReady()
		go rb.ListenSOCKS(fmt.Sprintf("127.0.0.1:%d", *socksPort))
	}

	joinerCallback := func(tun tunnel.DataTunnel) {
		startJoinerBridge(tun, common.VP8BufSize)
	}

	creatorCallback := func(tun tunnel.DataTunnel) {
		tunnel.NewRelayBridge(tun, "creator", common.VP8BufSize, log.Printf)
	}

	newPersistentJoinerBridge := func(onConfigAck func()) func(tunnel.DataTunnel) {
		var (
			bridge   *tunnel.RelayBridge
			bridgeMu sync.Mutex
		)
		return func(tun tunnel.DataTunnel) {
			readBuf := common.VP8BufSize
			if onConfigAck != nil {
				readBuf = tunnel.PCRelayReadBuffer()
			}
			if _, ok := tun.(*tunnel.DCTunnel); ok {
				readBuf = common.DCBufSize
			}
			bridgeMu.Lock()
			defer bridgeMu.Unlock()
			if bridge == nil {
				bridge = tunnel.NewRelayBridgeWithAuth(tun, "joiner", readBuf, log.Printf, *socksUser, *socksPass)
				bridge.SetPersistentListener(true)
				// Cold-start hardening (WB Stream): when the joiner has a config-ack
				// signal, hold SOCKS until the carrier is proven warm both ways (peer
				// acked our vp8 config) so the first MsgConnect isn't lost in the vp8
				// keyframe-warmup window. Disable with WLB_WARMUP_GATE=0. Joiners with
				// no config handshake (onConfigAck==nil: dion/vk/telemost) open at once.
				if onConfigAck != nil {
					bridge.SetOnConfigAck(onConfigAck)
					if os.Getenv("WLB_WARMUP_GATE") != "0" {
						bridge.MarkReadyOnConfigAck(12 * time.Second)
					} else {
						bridge.MarkReady()
					}
				} else {
					bridge.MarkReady()
				}
				hrPublish(tun, bridge)
				addr := fmt.Sprintf("127.0.0.1:%d", *socksPort)
				go func() {
					if err := bridge.ListenSOCKS(addr); err != nil {
						log.Printf("relay: SOCKS listen failed: %v", err)
					}
				}()
				return
			}
			bridge.SwapTunnel(tun)
			hrPublish(tun, bridge)
			if onConfigAck != nil {
				bridge.SetOnConfigAck(onConfigAck)
			}
			log.Printf("relay: tunnel swapped after reconnect")
		}
	}

	if *mode == "gen-kf" {
		// Test harness: emit our AssembleKeyframeSkip as a 1-frame IVF for
		// ffmpeg/vpxdec validation (does our VP8 bitstream decode cleanly?).
		w, h := 320, 180
		frame := tunnel.AssembleKeyframeSkip(w, h)
		var b []byte
		put16 := func(v int) { b = append(b, byte(v), byte(v>>8)) }
		put32 := func(v int) { b = append(b, byte(v), byte(v>>8), byte(v>>16), byte(v>>24)) }
		b = append(b, 'D', 'K', 'I', 'F')
		put16(0)  // version
		put16(32) // header length
		b = append(b, 'V', 'P', '8', '0')
		put16(w)
		put16(h)
		put32(24)
		put32(1)          // framerate num/den
		put32(1)          // frame count
		put32(0)          // unused
		put32(len(frame)) // frame size
		put32(0)
		put32(0) // timestamp u64
		b = append(b, frame...)
		os.WriteFile("/tmp/kf.ivf", b, 0644)
		fmt.Printf("wrote /tmp/kf.ivf: frame=%dB total=%dB\n", len(frame), len(b))
		return
	}

	if *mode == "gen-kf-data" {
		// Test harness: emit a DATA-CARRYING VP8 keyframe as a 1-frame IVF for
		// ffmpeg/vpxdec validation. If ffmpeg decodes it without error, our
		// coefficient tokens are valid VP8 (-> SFU forwards full-rate). Also
		// self-checks the encode/decode round-trip in-process.
		w, h := 320, 180
		payload := make([]byte, 4000)
		for i := range payload {
			payload[i] = byte(i*131 + 7)
		}
		frame, err := tunnel.AssembleKeyframeData(w, h, payload)
		if err != nil {
			fmt.Println("assemble error:", err)
			return
		}
		back, err := tunnel.DecodeKeyframeData(frame)
		if err != nil {
			fmt.Println("decode error:", err)
			return
		}
		ok := len(back) == len(payload)
		for i := range payload {
			if !ok || back[i] != payload[i] {
				ok = false
				break
			}
		}
		out := "kfdata.ivf"
		if flag.NArg() > 0 {
			out = flag.Arg(0)
		}
		var b []byte
		put16 := func(v int) { b = append(b, byte(v), byte(v>>8)) }
		put32 := func(v int) { b = append(b, byte(v), byte(v>>8), byte(v>>16), byte(v>>24)) }
		b = append(b, 'D', 'K', 'I', 'F')
		put16(0)
		put16(32)
		b = append(b, 'V', 'P', '8', '0')
		put16(w)
		put16(h)
		put32(24)
		put32(1)
		put32(1)
		put32(0)
		put32(len(frame))
		put32(0)
		put32(0)
		b = append(b, frame...)
		os.WriteFile(out, b, 0644)
		fmt.Printf("wrote %s: frame=%dB total=%dB roundtrip_ok=%v payload=%dB\n", out, len(frame), len(b), ok, len(payload))
		return
	}

	switch *mode {
	case "dc-joiner":
		log.Fatal(androidbind.StartJoiner(*wsPort, *socksPort, *socksUser, *socksPass, cb))
	case "dc-creator":
		log.Fatal(startDCCreator(*wsPort))
	case "vk-video-joiner":
		c := pion.NewVKClient(log.Printf)
		c.OnConnected = joinerCallback
		startVideo(*mode, c, joinerCallback)
	case "vk-headless-joiner":
		c := android.NewVKHeadlessJoiner(log.Printf)
		c.OnConnected = newPersistentJoinerBridge(nil)
		c.Run()
	case "vk-video-creator":
		c := pion.NewVKClient(log.Printf)
		c.OnConnected = creatorCallback
		startVideo(*mode, c, creatorCallback)
	case "telemost-headless-joiner":
		c := android.NewTelemostHeadlessJoiner(log.Printf)
		c.OnConnected = newPersistentJoinerBridge(nil)
		c.Run()
	case "telemost-video-joiner":
		c := pion.NewTelemostClient(log.Printf)
		c.OnConnected = joinerCallback
		startVideo(*mode, c, joinerCallback)
	case "telemost-video-creator":
		c := pion.NewTelemostClient(log.Printf)
		c.OnConnected = creatorCallback
		startVideo(*mode, c, creatorCallback)
	case "wbstream-headless-joiner":
		c := android.NewWBStreamHeadlessJoiner(log.Printf)
		c.OnConnected = newPersistentJoinerBridge(c.MarkConfigAcked)
		c.Run()
	case "dion-headless-joiner":
		c := android.NewDionHeadlessJoiner(log.Printf)
		c.OnConnected = newPersistentJoinerBridge(nil)
		c.Run()
	default:
		fmt.Fprintf(os.Stderr, "Unknown mode: %s\n", *mode)
		os.Exit(1)
	}
}
