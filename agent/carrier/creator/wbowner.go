package main

import (
	"encoding/json"
	"github.com/pion/webrtc/v4"
	"os"
	"path/filepath"
	"strings"
	"time"
	"whitelist-bypass/relay/tunnel"
	"whitelist-bypass/relay/wbstream"
)

func runWBOwner(room, statePath, statusPath string) {
	room = wbstream.ParseRoomID(room)
	if room == "" || statusPath == "" {
		os.Exit(2)
	}
	report := func(state, phase string) {
		raw, _ := json.Marshal(map[string]any{"state": state, "phase": phase, "updated": time.Now().Unix()})
		// The service owns this private directory; no login material is in status.
		f, err := os.CreateTemp(filepath.Dir(statusPath), ".owner-status-")
		if err != nil {
			return
		}
		name := f.Name()
		defer os.Remove(name)
		if _, err = f.Write(raw); err == nil {
			err = f.Sync()
		}
		f.Close()
		if err == nil {
			_ = os.Rename(name, statusPath)
		}
	}
	failures := 0
	for {
		report("reconnecting", "refresh_login")
		owner, err := wbstream.LoadOwnerState(statePath)
		var token string
		if err == nil {
			token, err = owner.Refresh()
		}
		if err != nil {
			state := "reconnecting"
			if strings.Contains(err.Error(), "status 401") || strings.Contains(err.Error(), "status 403") || strings.Contains(err.Error(), "renewal") {
				state = "needs_login"
			}
			if strings.Contains(err.Error(), "status 498") {
				state = "wb_blocked"
			}
			report(state, "refresh_login")
			failures++
			delay := 5 * time.Second << min(failures-1, 6)
			if delay > 300*time.Second {
				delay = 300 * time.Second
			}
			time.Sleep(delay)
			continue
		}
		report("reconnecting", "join_room")
		_, roomToken, _, serverURL, err := owner.Join(token, room, "NetGuard owner")
		if err != nil {
			report("reconnecting", "join_room")
			time.Sleep(30 * time.Second)
			continue
		}
		obf, _ := tunnel.NewTunnelObfuscator(tunnel.DeriveSecretFromJoinLink(room + "/owner-keeper"))
		session := wbstream.NewSession(wbstream.SessionConfig{RoomToken: roomToken, ServerURL: serverURL, DisplayName: "NetGuard owner", TunnelMode: wbstream.TunnelModeVideo, Obfuscator: obf, LogFn: func(string, ...any) {}})
		session.OnConnected = func(tunnel.DataTunnel) { report("hosting", "connected") }
		if err = session.Start(); err != nil {
			session.Close()
			report("reconnecting", "connect_media")
			time.Sleep(10 * time.Second)
			continue
		}
		failures = 0
		misses := 0
		tick := time.NewTicker(15 * time.Second)
		refresh := time.NewTicker(10 * time.Minute)
		alive := true
		for alive {
			select {
			case <-session.Done():
				alive = false
			case <-tick.C:
				if session.PublisherConnectionState() == webrtc.PeerConnectionStateConnected {
					misses = 0
					report("hosting", "connected")
				} else {
					misses++
					if misses >= 3 {
						alive = false
					}
				}
			case <-refresh.C:
				if _, err = owner.Refresh(); err != nil {
					report("reconnecting", "refresh_login")
				}
			}
		}
		tick.Stop()
		refresh.Stop()
		session.Close()
		report("reconnecting", "connect_media")
		time.Sleep(5 * time.Second)
	}
}
