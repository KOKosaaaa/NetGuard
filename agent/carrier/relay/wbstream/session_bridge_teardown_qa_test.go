package wbstream

import (
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/tunnel"
)

func TestWBQAActualSessionCloseAndWatchdogRetireBridgeSockets(t *testing.T) {
	for _, cause := range []string{"session-close", "media-watchdog"} {
		t.Run(cause, func(t *testing.T) {
			obf, err := tunnel.NewTunnelObfuscator([]byte("session-bridge-teardown-qa"))
			if err != nil {
				t.Fatal(err)
			}
			track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "camera", "qa")
			if err != nil {
				t.Fatal(err)
			}
			child := tunnel.NewVP8DataTunnel(track, obf, func(string, ...any) {})
			m := tunnel.NewMultiTrackTunnel([]*tunnel.VP8DataTunnel{child})
			started := make(chan struct{})
			var once sync.Once
			s := NewSession(SessionConfig{IsJoiner: true, TunnelMode: TunnelModeVideo, Obfuscator: obf, LogFn: func(f string, a ...any) {
				if strings.Contains(fmt.Sprintf(f, a...), "probing media") {
					once.Do(func() { close(started) })
				}
			}})
			s.vp8tun = m
			rb := tunnel.NewRelayBridge(m, "joiner", 4096, func(string, ...any) {})
			rb.SetPersistentListener(true)
			rb.MarkReady()
			m.Start(24, 1)
			t.Cleanup(func() { s.Close(); rb.Close(); m.Stop() })
			reserve, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			addr := reserve.Addr().String()
			reserve.Close()
			listenDone := make(chan error, 1)
			go func() { listenDone <- rb.ListenSOCKS(addr) }()
			connect := func() net.Conn {
				t.Helper()
				deadline := time.Now().Add(time.Second)
				var c net.Conn
				for {
					c, err = net.DialTimeout("tcp", addr, 100*time.Millisecond)
					if err == nil {
						break
					}
					select {
					case e := <-listenDone:
						t.Fatalf("listener failed: %v", e)
					default:
					}
					if time.Now().After(deadline) {
						t.Fatal(err)
					}
					time.Sleep(5 * time.Millisecond)
				}
				t.Cleanup(func() { c.Close() })
				c.SetDeadline(time.Now().Add(6 * time.Second))
				if _, err = c.Write([]byte{5, 1, 0}); err != nil {
					t.Fatal(err)
				}
				greet := make([]byte, 2)
				if _, err = io.ReadFull(c, greet); err != nil || greet[0] != 5 || greet[1] != 0 {
					t.Fatalf("greeting %v %v", greet, err)
				}
				if _, err = c.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, 1, 187}); err != nil {
					t.Fatal(err)
				}
				return c
			}
			waitCount := func(n int) {
				t.Helper()
				deadline := time.Now().Add(time.Second)
				for {
					count, _, _ := rb.Stats()
					if count == n {
						return
					}
					if time.Now().After(deadline) {
						t.Fatalf("expected %d bridge sockets, got%d", n, count)
					}
					time.Sleep(time.Millisecond)
				}
			}
			active := connect()
			waitCount(1)
			rb.Feed(tunnel.EncodeFrame(1, tunnel.MsgConnectOK, nil))
			answer := make([]byte, 10)
			if _, err = io.ReadFull(active, answer); err != nil || answer[1] != 0 {
				t.Fatalf("establish fixture: %v %v", answer, err)
			}
			pending := connect()
			waitCount(2)
			if cause == "session-close" {
				s.Close()
			} else {
				// Run the actual health goroutine and actual Session.Close branch.
				// Move only its observed last-success time; no substitute callback
				// or direct bridge Reset is permitted in this regression.
				go s.watchMediaHealth(m)
				select {
				case <-started:
				case <-time.After(time.Second):
					t.Fatal("watcher did not start")
				}
				s.health.mu.Lock()
				s.health.confirmed = true
				s.health.lastSuccess = time.Now().Add(-25 * time.Second)
				s.health.mu.Unlock()
			}
			select {
			case <-s.Done():
			case <-time.After(4 * time.Second):
				t.Fatal("session did not finish closing")
			}
			if count, _, _ := rb.Stats(); count != 0 {
				t.Errorf("Session.Done closed with %d old bridge sockets still retained", count)
			}
			for name, c := range map[string]net.Conn{"established": active, "pending": pending} {
				c.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
				var b [16]byte
				_, err = c.Read(b[:])
				if err == nil {
					_, err = c.Read(b[:])
				}
				if err == nil {
					t.Errorf("%s socket remained open", name)
				} else if e, ok := err.(net.Error); ok && e.Timeout() {
					t.Errorf("%s socket was not closed by actual %s", name, cause)
				}
			}
		})
	}
}
