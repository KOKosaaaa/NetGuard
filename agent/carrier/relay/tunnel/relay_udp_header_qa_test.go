package tunnel

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

// Exercise the production UDP association reader with real loopback packets.
// The carrier captures requests; no destination or external service is dialed.
func TestRelayQAUDPDelayedReplyRetainsItsOriginalDestinationHeader(t *testing.T) {
	carrier := &qaPendingTunnel{frames: make(chan []byte, 8)}
	bridge := NewRelayBridge(carrier, "joiner", 4096, pcNoop)
	t.Cleanup(bridge.Close)
	serverControl, clientControl := net.Pipe()
	t.Cleanup(func() { serverControl.Close(); clientControl.Close() })
	if err := clientControl.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	go bridge.handleUDPAssociate(serverControl)
	bound := make([]byte, 10)
	if _, err := io.ReadFull(clientControl, bound); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(bound[:8], []byte{5, 0, 0, 1, 127, 0, 0, 1}) {
		t.Fatalf("unexpected UDP association reply: %x", bound)
	}
	clientUDP, err := net.DialUDP("udp4", nil, &net.UDPAddr{
		IP: net.IPv4(127, 0, 0, 1), Port: int(binary.BigEndian.Uint16(bound[8:])),
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { clientUDP.Close() })
	if err := clientUDP.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	firstHeader := []byte{0, 0, 0, 1, 127, 0, 0, 1, 0x2e, 0xe1}  // 127.0.0.1:12001
	secondHeader := []byte{0, 0, 0, 1, 127, 0, 0, 2, 0x2e, 0xe2} // 127.0.0.2:12002
	writeRequest := func(header []byte, payload string) uint32 {
		t.Helper()
		packet := append(append([]byte(nil), header...), payload...)
		if _, err := clientUDP.Write(packet); err != nil {
			t.Fatal(err)
		}
		var frame []byte
		select {
		case frame = <-carrier.frames:
		case <-time.After(2 * time.Second):
			t.Fatal("association did not send the loopback UDP request to the carrier")
		}
		var id uint32
		DecodeFrames(frame, func(connID uint32, kind byte, body []byte) {
			if kind != MsgUDP || len(body) < 2 || int(body[0])+1 > len(body) || string(body[1+int(body[0]):]) != payload {
				t.Fatalf("unexpected captured UDP request: kind=%d body=%x", kind, body)
			}
			id = connID
		})
		if id == 0 {
			t.Fatal("captured request has no connection ID")
		}
		return id
	}
	firstID := writeRequest(firstHeader, "first request")
	secondID := writeRequest(secondHeader, "second request")
	if firstID == secondID {
		t.Fatal("distinct UDP requests reused a connection ID")
	}
	// Capturing the second request synchronizes after its ReadFromUDP and
	// udpClients.Store. Only now release the delayed first carrier response.
	carrierReply := []byte("delayed first reply")
	bridge.Feed(EncodeFrame(firstID, MsgUDPReply, carrierReply))
	response := make([]byte, 4096)
	n, err := clientUDP.Read(response)
	if err != nil {
		t.Fatal(err)
	}
	want := append(append([]byte(nil), firstHeader...), carrierReply...)
	if !bytes.Equal(response[:n], want) {
		t.Fatalf("delayed first UDP reply changed destination header: got=%x want=%x secondHeader=%x", response[:n], want, secondHeader)
	}
}

type qaUDPAssociationControl struct {
	net.Conn
	once   sync.Once
	closed chan struct{}
}

func (c *qaUDPAssociationControl) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() { close(c.closed) })
	return err
}

func TestRelayQAUDPAssociationCloseReleasesOnlyItsOutstandingRequests(t *testing.T) {
	carrier := &qaPendingTunnel{frames: make(chan []byte, 8)}
	bridge := NewRelayBridge(carrier, "joiner", 4096, pcNoop)
	t.Cleanup(bridge.Close)
	server, client := net.Pipe()
	control := &qaUDPAssociationControl{Conn: server, closed: make(chan struct{})}
	t.Cleanup(func() { control.Close(); client.Close() })
	if err := client.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	go bridge.handleUDPAssociate(control)
	bound := make([]byte, 10)
	if _, err := io.ReadFull(client, bound); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(bound[:8], []byte{5, 0, 0, 1, 127, 0, 0, 1}) {
		t.Fatalf("unexpected UDP association reply: %x", bound)
	}
	udp, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(binary.BigEndian.Uint16(bound[8:]))})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	var association *net.UDPConn
	for i := 0; i < 2; i++ {
		if _, err := udp.Write([]byte{0, 0, 0, 1, 127, 0, 0, 1, 0x2e, byte(0xe1 + i), 'x'}); err != nil {
			t.Fatal(err)
		}
		select {
		case frame := <-carrier.frames:
			DecodeFrames(frame, func(id uint32, kind byte, _ []byte) {
				if kind != MsgUDP {
					t.Fatalf("unexpected carrier message: %d", kind)
				}
				entry, ok := bridge.udpClients.Load(id)
				if !ok {
					t.Fatal("association did not register its pending request")
				}
				association = entry.(*udpClient).udpConn
			})
		case <-time.After(2 * time.Second):
			t.Fatal("association did not send its request to the carrier")
		}
	}
	other, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { other.Close() })
	const unrelatedID = uint32(999)
	bridge.udpClients.Store(unrelatedID, &udpClient{udpConn: other})
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	// The production reader's deferred TCP Close proves it observed the UDP
	// socket closing. Allow adjacent deferred cleanup to finish in either order.
	select {
	case <-control.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("UDP association reader did not exit after its control connection closed")
	}
	deadline := time.Now().Add(time.Second)
	remaining := 0
	for {
		remaining = 0
		bridge.udpClients.Range(func(_, value any) bool {
			if value.(*udpClient).udpConn == association {
				remaining++
			}
			return true
		})
		if remaining == 0 || time.Now().After(deadline) {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if remaining != 0 {
		t.Errorf("closed UDP association retains %d outstanding requests after its reader exited", remaining)
	}
	if entry, ok := bridge.udpClients.Load(unrelatedID); !ok || entry.(*udpClient).udpConn != other {
		t.Error("closing one UDP association removed another association's request")
	}
}
