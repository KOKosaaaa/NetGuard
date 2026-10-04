package livekit

import (
	"io"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

func TestQADTLSSuccessfulBothPeersRemainUsablePastHandshakeDeadlines(t *testing.T) {
	var se webrtc.SettingEngine
	se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	se.SetIncludeLoopbackCandidate(true)
	se.SetIPFilter(func(ip net.IP) bool { return ip.IsLoopback() })
	var timedOut atomic.Bool
	client := NewClient(Config{SettingEngine: &se, LogFn: func(format string, _ ...any) {
		if strings.Contains(format, "DTLS handshake timed out") {
			timedOut.Store(true)
		}
	}})
	client.dtlsHandshakeTimeout = time.Second
	defer client.Close()
	if err := client.buildPeerConnections(); err != nil {
		t.Fatal(err)
	}
	var peers [2]*webrtc.PeerConnection
	for i := range peers {
		var err error
		peers[i], err = webrtc.NewAPI(webrtc.WithSettingEngine(se)).NewPeerConnection(webrtc.Configuration{})
		if err != nil {
			t.Fatal(err)
		}
		defer peers[i].Close()
	}
	pubWriter := make(chan io.ReadWriteCloser, 1)
	pubReceived := make(chan string, 1)
	pubDC, err := client.pubPC.CreateDataChannel("_reliable", nil)
	if err != nil {
		t.Fatal(err)
	}
	pubDC.OnOpen(func() {
		if raw, e := pubDC.Detach(); e == nil {
			pubWriter <- raw
		}
	})
	peers[0].OnDataChannel(func(dc *webrtc.DataChannel) {
		dc.OnMessage(func(m webrtc.DataChannelMessage) { pubReceived <- string(m.Data) })
	})
	subReceived := make(chan string, 1)
	client.OnDataChannel = func(dc *webrtc.DataChannel) {
		dc.OnOpen(func() {
			if raw, e := dc.Detach(); e == nil {
				go func() {
					buf := make([]byte, 64)
					n, e := raw.Read(buf)
					if e == nil {
						subReceived <- string(buf[:n])
					}
				}()
			}
		})
	}
	subOpen := make(chan struct{})
	subDC, err := peers[1].CreateDataChannel("subscriber", nil)
	if err != nil {
		t.Fatal(err)
	}
	subDC.OnOpen(func() { close(subOpen) })
	qaDTLSNegotiate(t, client.pubPC, peers[0])
	qaDTLSNegotiate(t, peers[1], client.subPC)
	var writer io.ReadWriteCloser
	select {
	case writer = <-pubWriter:
	case <-time.After(3 * time.Second):
		t.Fatal("publisher did not open")
	}
	select {
	case <-subOpen:
	case <-time.After(3 * time.Second):
		t.Fatal("subscriber did not open")
	}
	// Both handshake contexts have already been canceled by Pion. Their former
	// deadline must never later reclassify cancellation as DeadlineExceeded.
	time.Sleep(2500 * time.Millisecond)
	if timedOut.Load() || client.closed.Load() || client.pubPC.ConnectionState() != webrtc.PeerConnectionStateConnected || client.subPC.ConnectionState() != webrtc.PeerConnectionStateConnected {
		t.Fatal("completed handshake later closed healthy connection")
	}
	if _, err = writer.Write([]byte("publisher-after-deadline")); err != nil {
		t.Fatal(err)
	}
	if err = subDC.SendText("subscriber-after-deadline"); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-pubReceived:
		if got != "publisher-after-deadline" {
			t.Fatal(got)
		}
	case <-time.After(time.Second):
		t.Fatal("publisher stopped delivering after deadline")
	}
	select {
	case got := <-subReceived:
		if got != "subscriber-after-deadline" {
			t.Fatal(got)
		}
	case <-time.After(time.Second):
		t.Fatal("subscriber stopped delivering after deadline")
	}
}
