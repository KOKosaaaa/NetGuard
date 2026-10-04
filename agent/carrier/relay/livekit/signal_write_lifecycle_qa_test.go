package livekit

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type qaObservedSignalConn struct {
	net.Conn
	active  atomic.Bool
	once    sync.Once
	writing chan struct{}
}

func (c *qaObservedSignalConn) Write(p []byte) (int, error) {
	if c.active.Load() && len(p) > 1024 {
		c.once.Do(func() { close(c.writing) })
	}
	return c.Conn.Write(p)
}

func TestQASignalWriteAndCloseBoundedOnNonReadingPeer(t *testing.T) {
	for _, explicitClose := range []bool{false, true} {
		name := "write_timeout"
		if explicitClose {
			name = "close_during_write"
		}
		t.Run(name, func(t *testing.T) {
			release := make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				ws, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
				if err != nil {
					return
				}
				defer ws.Close()
				if tcp, ok := ws.UnderlyingConn().(*net.TCPConn); ok {
					_ = tcp.SetReadBuffer(1024)
				}
				<-release // real TCP peer deliberately never consumes websocket data
			}))
			var observed *qaObservedSignalConn
			client := NewClient(Config{ServerURL: "ws" + strings.TrimPrefix(server.URL, "http"), NetDialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
				conn, err := (&net.Dialer{}).DialContext(ctx, network, address)
				if err != nil {
					return nil, err
				}
				if tcp, ok := conn.(*net.TCPConn); ok {
					_ = tcp.SetWriteBuffer(1024)
				}
				observed = &qaObservedSignalConn{Conn: conn, writing: make(chan struct{})}
				return observed, nil
			}})
			t.Cleanup(func() { close(release); client.Close(); server.Close() })
			client.writeTimeout = 250 * time.Millisecond
			if err := client.Connect(); err != nil {
				t.Fatal(err)
			}
			observed.active.Store(true)
			wrote := make(chan error, 1)
			go func() { wrote <- client.SendOffer(strings.Repeat("a", 8*1024*1024)) }()
			select {
			case <-observed.writing:
			case <-time.After(3 * time.Second):
				t.Fatal("large write never reached TCP")
			}
			closed := make(chan struct{})
			if explicitClose {
				go func() { client.Close(); close(closed) }()
			}
			select {
			case err := <-wrote:
				if err == nil {
					t.Fatal("nonreading TCP peer unexpectedly accepted complete SDP")
				}
				if !explicitClose {
					if ne, ok := err.(net.Error); !ok || !ne.Timeout() {
						t.Fatalf("expected write deadline, got %T", err)
					}
				}
			case <-time.After(3 * time.Second):
				t.Fatal("signaling write retained wsMu indefinitely")
			}
			if explicitClose {
				select {
				case <-closed:
				case <-time.After(3 * time.Second):
					t.Fatal("Close stayed blocked after write deadline")
				}
			} else {
				select {
				case <-client.lifetimeCtx.Done():
				case <-time.After(3 * time.Second):
					t.Fatal("terminal write failure left session lifetime alive")
				}
			}
		})
	}
}
