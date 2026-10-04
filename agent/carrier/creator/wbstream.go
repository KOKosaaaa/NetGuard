package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"runtime/debug"
	"time"

	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/tunnel"
	"whitelist-bypass/relay/wbstream"
)

func wbstreamMain() {
	cookiesPath := flag.String("cookies", "", "path to cookies-wbstream.json")
	roomFlag := flag.String("room", "", "WB Stream room id, wbstream://<id>, or https://stream.wb.ru/room/<id> (empty = create new)")
	displayName := flag.String("name", "Headless", "display name in the room")
	resources := flag.String("resources", "default", "resource mode: default, moderate, unlimited, custom")
	customReadBuf := flag.Int("read-buf", 0, "DC read buffer size in bytes, used with -resources custom")
	customMemLimit := flag.Int64("mem-limit", 0, "memory limit in bytes, used with -resources custom")
	writeFile := flag.String("write-file", "", "path to file where active room id is appended")
	guest := flag.Bool("guest", false, "join an existing room as guest instead of logged-in (requires --room; no cookies)")
	readyFile := flag.String("ready-file", "", "publisher-ready marker, removed on disconnect")
	holdRoom := flag.Bool("hold-room", false, "keep meeting alive during a creator update without forwarding traffic")
	ownerState := flag.String("owner-state", "", "private persistent owner login file")
	ownerStatus := flag.String("owner-status", "", "owner recovery status file")
	flag.Parse()
	if *ownerState != "" {
		runWBOwner(*roomFlag, *ownerState, *ownerStatus)
		return
	}

	var readBuf int
	var memLimit int64
	switch *resources {
	case "moderate":
		readBuf = 16384
		memLimit = 64 << 20
	case "default":
		readBuf = common.DCBufSize
		memLimit = 128 << 20
	case "unlimited":
		readBuf = common.RTPBufSize
		memLimit = 256 << 20
	case "custom":
		readBuf = *customReadBuf
		if readBuf == 0 {
			readBuf = common.RTPBufSize
		}
		memLimit = *customMemLimit
		if memLimit == 0 {
			memLimit = 256 << 20
		}
	default:
		log.Fatalf("[config] unknown resources mode: %s (use moderate, default, unlimited, custom)", *resources)
	}
	if memLimit > 0 {
		debug.SetMemoryLimit(memLimit)
	}
	common.MaskingEnabled = true
	log.Printf("[config] resources=%s read-buf=%d mem-limit=%d", *resources, readBuf, memLimit)

	requestedRoom := wbstream.ParseRoomID(*roomFlag)
	var cookieHeader, deviceID string
	if *guest {
		if requestedRoom == "" {
			log.Fatalf("[auth] --guest requires --room (guests cannot create rooms)")
		}
	} else {
		if *cookiesPath == "" {
			log.Fatalf("[auth] --cookies is required (or use --guest --room)")
		}
		rawCookies := common.LoadCookies(*cookiesPath)
		deviceID = common.CookieValue(rawCookies, "__wb_device_id")
		if deviceID == "" {
			log.Fatalf("[auth] cookies file is missing __wb_device_id; re-export via creator-app's 'Export Cookies' button")
		}
		cookieHeader = common.FilterCookies(rawCookies, wbstream.WBStreamCookieAllowlist)
	}

	// authenticate establishes (or re-establishes) a session token for the given
	// room: guest mode joins an existing room anonymously, otherwise the logged-in
	// slide-v3 -> connection-details flow runs.
	authenticate := func(room string) (string, string, string, string, error) {
		if *guest {
			return wbstream.AuthAndGetToken(nil, room, *displayName)
		}
		bearer, rerr := wbstream.RefreshAccessToken(nil, cookieHeader, deviceID)
		if rerr != nil {
			return "", "", "", "", fmt.Errorf("slide-v3 refresh: %w", rerr)
		}
		return wbstream.AuthAsLoggedIn(nil, cookieHeader, bearer, room, *displayName)
	}

	roomID, roomToken, accessToken, serverURL, err := authenticate(requestedRoom)
	for delay := 5 * time.Second; err != nil; {
		log.Printf("[auth] room unavailable; waiting for owner/network, retrying in %s", delay)
		time.Sleep(delay)
		roomID, roomToken, accessToken, serverURL, err = authenticate(requestedRoom)
		if delay < 60*time.Second {
			delay *= 2
			if delay > 60*time.Second {
				delay = 60 * time.Second
			}
		}
	}
	log.Printf("[auth] room=%s server=%s guest=%v", roomID, serverURL, *guest)

	if *writeFile != "" {
		f, err := os.OpenFile(*writeFile, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err != nil {
			log.Fatalf("Failed to open write-file: %v", err)
		}
		fmt.Fprintln(f, "wbstream://"+roomID)
		f.Close()
		log.Printf("[config] Wrote join link to %s", *writeFile)
	}

	secretRoom := roomID
	if *holdRoom {
		secretRoom += "/upgrade-keeper"
	}

	var activeBridge *tunnel.RelayBridge
	makeSession := func(token, access, server string) *wbstream.Session {
		obf, err := tunnel.NewTunnelObfuscator(tunnel.DeriveSecretFromJoinLink(secretRoom))
		if err != nil {
			log.Fatalf("[obf] init failed: %v", err)
		}
		log.Printf("[obf] key-source=%q localEpoch=0x%08x", roomID, obf.LocalEpoch())
		sess := wbstream.NewSession(wbstream.SessionConfig{
			RoomToken:   token,
			ServerURL:   server,
			DisplayName: *displayName,
			TunnelMode:  wbstream.TunnelModeVideo,
			Obfuscator:  obf,
			LogFn:       log.Printf,
			RoomID:      roomID,
			AccessToken: access,
			ReadBuf:     readBuf,
		})
		sess.OnConnected = func(tun tunnel.DataTunnel) {
			if *holdRoom {
				if *readyFile != "" {
					_ = os.WriteFile(*readyFile, []byte("ready\n"), 0600)
				}
				return
			}
			if activeBridge != nil {
				activeBridge.Reset()
			}
			bridgeReadBuf := tunnel.PCRelayReadBuffer()
			mode := "video"
			if _, ok := tun.(*tunnel.DCTunnel); ok {
				bridgeReadBuf = readBuf
				mode = "dc"
			}
			activeBridge = tunnel.NewRelayBridge(tun, "creator", bridgeReadBuf, log.Printf)
			activeBridge.SetOnPeerConfig(func(fps, batch, trackCount int) {
				sess.AdaptTrackCount(trackCount)
			})
			fmt.Printf("\n  TUNNEL CONNECTED mode=%s\n", mode)
			if *readyFile != "" {
				_ = os.WriteFile(*readyFile, []byte("ready\n"), 0600)
			}
		}
		sess.OnPeerRestart = func() {
			if activeBridge != nil {
				log.Printf("[creator] new peer detected, resetting relay bridge")
				activeBridge.Reset()
			}
		}
		return sess
	}

	fmt.Println("")
	fmt.Println("  CALL CREATED")
	fmt.Println("  join_link: wbstream://" + roomID)
	fmt.Println("")

	for {
		if *readyFile != "" {
			_ = os.Remove(*readyFile)
		}
		sess := makeSession(roomToken, accessToken, serverURL)
		if err := sess.Start(); err != nil {
			log.Printf("[session] start failed: %v, retrying in 5s", err)
			sess.Close()
			time.Sleep(5 * time.Second)
		} else {
			<-sess.Done()
			if *readyFile != "" {
				_ = os.Remove(*readyFile)
			}
			log.Printf("[session] ended, rejoining in 3s")
			sess.Close()
		}

		if activeBridge != nil {
			activeBridge.Reset()
		}
		time.Sleep(3 * time.Second)

		// Never start another session with stale credentials after refresh fails.
		// A closed guest room may need its owner; keep retrying authentication.
		for delay := 5 * time.Second; ; {
			_, newRoomToken, newAccessToken, newServerURL, rerr := authenticate(roomID)
			if rerr == nil {
				roomToken, accessToken, serverURL = newRoomToken, newAccessToken, newServerURL
				break
			}
			log.Printf("[rejoin] authentication unavailable; retrying in %s", delay)
			time.Sleep(delay)
			if delay < 60*time.Second {
				delay *= 2
				if delay > 60*time.Second {
					delay = 60 * time.Second
				}
			}
		}
		log.Printf("[rejoin] refreshed token for room=%s server=%s", roomID, serverURL)
	}
}
