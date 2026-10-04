package livekit

import (
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/logging"
	"github.com/pion/transport/v4/vnet"
	"github.com/pion/webrtc/v4"
)

func qaDTLSWait(t *testing.T, what string, ready func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if ready() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timeout waiting for %s", what)
}

func qaDTLSNegotiate(t *testing.T, offerer, answerer *webrtc.PeerConnection) {
	t.Helper()
	offer, err := offerer.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gather := webrtc.GatheringCompletePromise(offerer)
	if err = offerer.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gather:
	case <-time.After(3 * time.Second):
		t.Fatal("offer gathering timeout")
	}
	if err = answerer.SetRemoteDescription(*offerer.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	answer, err := answerer.CreateAnswer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gather = webrtc.GatheringCompletePromise(answerer)
	if err = answerer.SetLocalDescription(answer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gather:
	case <-time.After(3 * time.Second):
		t.Fatal("answer gathering timeout")
	}
	if err = offerer.SetRemoteDescription(*answerer.LocalDescription()); err != nil {
		t.Fatal(err)
	}
}

// All datagrams stay in Pion's virtual network. ICE/STUN remains available;
// only publisher DTLS is blackholed, independently of the healthy subscriber.
func TestQADTLLifetimeStalledPublisherClosesSignalingWithHealthySubscriber(t *testing.T) {
	qaDTLSStalledPublisher(t, false)
}

func TestQADTLSExplicitCloseCancelsStalledHandshake(t *testing.T) {
	qaDTLSStalledPublisher(t, true)
}

func qaDTLSStalledPublisher(t *testing.T, explicitClose bool) {
	router, err := vnet.NewRouter(&vnet.RouterConfig{CIDR: "10.44.0.0/24", LoggerFactory: logging.NewDefaultLoggerFactory()})
	if err != nil {
		t.Fatal(err)
	}
	var dropped atomic.Int64
	router.AddChunkFilter(func(c vnet.Chunk) bool {
		p := c.UserData()
		publisher := strings.HasPrefix(c.SourceAddr().String(), "10.44.0.2:") || strings.HasPrefix(c.DestinationAddr().String(), "10.44.0.2:")
		if publisher && len(p) > 0 && p[0] >= 20 && p[0] <= 63 {
			dropped.Add(1)
			return false
		}
		return true
	})
	engines := make([]webrtc.SettingEngine, 3)
	for i := range engines {
		nw, e := vnet.NewNet(&vnet.NetConfig{StaticIPs: []string{fmt.Sprintf("10.44.0.%d", i+1)}})
		if e != nil {
			t.Fatal(e)
		}
		if e = router.AddNet(nw); e != nil {
			t.Fatal(e)
		}
		engines[i].SetNet(nw)
		engines[i].SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	}
	if err = router.Start(); err != nil {
		t.Fatal(err)
	}
	defer router.Stop()
	client := NewClient(Config{SettingEngine: &engines[0]})
	client.dtlsHandshakeTimeout = 1500 * time.Millisecond
	if explicitClose {
		client.dtlsHandshakeTimeout = 20 * time.Second
	}
	defer client.Close()
	if err = client.buildPeerConnections(); err != nil {
		t.Fatal(err)
	}
	peers := make([]*webrtc.PeerConnection, 2)
	for i := range peers {
		peers[i], err = webrtc.NewAPI(webrtc.WithSettingEngine(engines[i+1])).NewPeerConnection(webrtc.Configuration{})
		if err != nil {
			t.Fatal(err)
		}
		defer peers[i].Close()
	}
	// A real local signaling socket must be unblocked, not just a failed-state log.
	upgrader := websocket.Upgrader{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, e := upgrader.Upgrade(w, r, nil)
		if e != nil {
			return
		}
		defer ws.Close()
		for {
			if _, _, e = ws.ReadMessage(); e != nil {
				return
			}
		}
	}))
	defer server.Close()
	client.ws, _, err = websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	readDone := make(chan error, 1)
	go func() { readDone <- client.ReadLoop() }()
	received := make(chan string, 1)
	client.OnDataChannel = func(dc *webrtc.DataChannel) {
		dc.OnOpen(func() {
			raw, e := dc.Detach()
			if e != nil {
				return
			}
			go func() {
				b := make([]byte, 64)
				n, e := raw.Read(b)
				if e == nil {
					received <- string(b[:n])
				}
			}()
		})
	}
	dc, err := peers[1].CreateDataChannel("healthy-subscriber", nil)
	if err != nil {
		t.Fatal(err)
	}
	dc.OnOpen(func() { _ = dc.SendText("subscriber-delivered") })
	qaDTLSNegotiate(t, peers[1], client.subPC)
	select {
	case got := <-received:
		if got != "subscriber-delivered" {
			t.Fatal(got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("subscriber never delivered actual data")
	}
	if _, err = client.pubPC.CreateDataChannel("_reliable", nil); err != nil {
		t.Fatal(err)
	}
	qaDTLSNegotiate(t, client.pubPC, peers[0])
	qaDTLSWait(t, "publisher ICE connected while DTLS connecting", func() bool {
		return client.pubPC.ICEConnectionState() == webrtc.ICEConnectionStateConnected && client.pubPC.SCTP().Transport().State() == webrtc.DTLSTransportStateConnecting
	})
	if client.subPC.ConnectionState() != webrtc.PeerConnectionStateConnected {
		t.Fatal("subscriber was not healthy during publisher stall")
	}
	if explicitClose {
		done := make(chan struct{})
		go func() { client.Close(); close(done) }()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Fatal("explicit Close failed to cancel active DTLS handshake promptly")
		}
	}
	select {
	case err := <-readDone:
		if err == nil {
			t.Fatal("closed signaling returned nil")
		}
	case <-time.After(4 * time.Second):
		buf := make([]byte, 1<<20)
		n := runtime.Stack(buf, true)
		t.Logf("state pub=%s dtls=%s\n%s", client.pubPC.ConnectionState(), client.pubPC.SCTP().Transport().State(), buf[:n])
		t.Fatal("DTLS timeout did not terminate stalled session/signaling")
	}
	if dropped.Load() == 0 {
		t.Fatal("fixture never blocked a DTLS datagram")
	}
	qaDTLSWait(t, "both PCs closed", func() bool {
		return client.pubPC.ConnectionState() == webrtc.PeerConnectionStateClosed && client.subPC.ConnectionState() == webrtc.PeerConnectionStateClosed
	})
}

func TestQADTLSEarlyReadersAndCloseBeforeHandshake(t *testing.T) {
	for _, count := range []int{1, 2} {
		t.Run(fmt.Sprintf("tracks%d", count), func(t *testing.T) {
			client := NewClient(Config{})
			if err := client.buildPeerConnections(); err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			var readers sync.WaitGroup
			for i := 0; i < count; i++ {
				track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, fmt.Sprint(i), "qa")
				if err != nil {
					t.Fatal(err)
				}
				trx, err := client.pubPC.AddTransceiverFromTrack(track, webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionSendonly})
				if err != nil {
					t.Fatal(err)
				}
				readers.Add(1)
				go func() { defer readers.Done(); _, _, _ = trx.Sender().ReadRTCP() }()
			}
			// Readers waiting on sendCalled must not prevent pre-handshake shutdown.
			done := make(chan struct{})
			go func() { client.Close(); readers.Wait(); close(done) }()
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("Close deadlocked with early RTCP readers")
			}
			if client.lifetimeCtx.Err() == nil {
				t.Fatal("Close did not cancel handshake context")
			}
		})
	}
}

// Same ordering as WB onLKReady: create sender, start ReadRTCP immediately,
// add the reliable data channel, and only then negotiate. The reader cannot
// be postponed until Connected, since that would miss the suspected race.
func TestQADTLSEarlyReadersDoNotBlockSuccessfulPublisherHandshake(t *testing.T) {
	for _, count := range []int{1, 2} {
		t.Run(fmt.Sprintf("tracks%d", count), func(t *testing.T) {
			var se webrtc.SettingEngine
			se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
			se.SetIncludeLoopbackCandidate(true)
			se.SetIPFilter(func(ip net.IP) bool { return ip.IsLoopback() })
			client := NewClient(Config{SettingEngine: &se})
			defer client.Close()
			if err := client.buildPeerConnections(); err != nil {
				t.Fatal(err)
			}
			peer, err := webrtc.NewAPI(webrtc.WithSettingEngine(se)).NewPeerConnection(webrtc.Configuration{})
			if err != nil {
				t.Fatal(err)
			}
			defer peer.Close()
			var readers sync.WaitGroup
			for i := 0; i < count; i++ {
				track, e := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, fmt.Sprint(i), "qa")
				if e != nil {
					t.Fatal(e)
				}
				trx, e := client.pubPC.AddTransceiverFromTrack(track, webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionSendonly})
				if e != nil {
					t.Fatal(e)
				}
				started := make(chan struct{})
				readers.Add(1)
				go func() {
					defer readers.Done()
					close(started)
					for {
						if _, _, e := trx.Sender().ReadRTCP(); e != nil {
							return
						}
					}
				}()
				<-started
			}
			ordered := true
			dc, err := client.pubPC.CreateDataChannel("_reliable", &webrtc.DataChannelInit{Ordered: &ordered})
			if err != nil {
				t.Fatal(err)
			}
			received := make(chan string, 1)
			dc.OnOpen(func() {
				raw, e := dc.Detach()
				if e != nil {
					return
				}
				go func() {
					b := make([]byte, 64)
					n, e := raw.Read(b)
					if e == nil {
						received <- string(b[:n])
					}
				}()
			})
			peer.OnDataChannel(func(other *webrtc.DataChannel) {
				other.OnOpen(func() { _ = other.SendText("early-RTCP-handshake-ok") })
			})
			qaDTLSNegotiate(t, client.pubPC, peer)
			select {
			case got := <-received:
				if got != "early-RTCP-handshake-ok" {
					t.Fatal(got)
				}
			case <-time.After(5 * time.Second):
				t.Fatalf("early RTCP handshake stuck: ice=%s dtls=%s pc=%s", client.pubPC.ICEConnectionState(), client.pubPC.SCTP().Transport().State(), client.pubPC.ConnectionState())
			}
			client.Close()
			done := make(chan struct{})
			go func() { readers.Wait(); close(done) }()
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("early RTCP readers did not exit after close")
			}
		})
	}
}
