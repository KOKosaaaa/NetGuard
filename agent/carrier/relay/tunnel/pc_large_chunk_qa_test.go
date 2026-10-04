package tunnel

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

// No format/version extension is needed for 4KiB: preserve the existing
// uint32 frame length, connection ID, message kind and per-connection sequence.
// This checks the wire contract, not interoperability with an old APK binary.
func TestPCQAFourKiBExistingWireContractAndDelivery(t *testing.T) {
	for _, bundle := range []bool{false, true} {
		t.Run(fmt.Sprint("bundle=", bundle), func(t *testing.T) {
			sender, receiver := pcTestTunnel(t), pcTestTunnel(t)
			want := make([]byte, 4096)
			for i := range want {
				want[i] = byte(i*31 + i/127)
			}
			input := EncodeFrame(37, MsgData, want)
			sender.pcEnqueue(input)
			wire := sender.pcNextData(5000, time.Now())
			if len(wire) != 4110 || wire[0] != pcData || binary.BigEndian.Uint32(wire[1:5]) != 1 || binary.BigEndian.Uint32(wire[5:9]) != 4101 || binary.BigEndian.Uint32(wire[9:13]) != 37 || wire[13] != MsgData || !bytes.Equal(wire[14:], want) {
				t.Fatal("4KiB frame changed existing wire format")
			}
			got := make(chan []byte, 1)
			receiver.OnData = func(frame []byte) {
				DecodeFrames(frame, func(id uint32, kind byte, p []byte) {
					if id != 37 || kind != MsgData {
						return
					}
					got <- append([]byte(nil), p...)
				})
			}
			if bundle {
				wire = pcPack([][]byte{pcHelloPacket(), wire})
			}
			receiver.pcHandle(wire)
			select {
			case p := <-got:
				if !bytes.Equal(p, want) {
					t.Fatal("4KiB payload changed")
				}
			case <-time.After(time.Second):
				t.Fatal("4KiB was not delivered")
			}
		})
	}
}

func TestPCQAFourKiBFitsFloorCreditButEightKiBCannot(t *testing.T) {
	for _, fps := range []int{24, 96, 120} {
		t.Run(fmt.Sprint(fps), func(t *testing.T) {
			const floor = 200
			capBytes := floor*1000/8/fps*2 + 4096
			sender := pcTestTunnel(t)
			sender.pcEnqueue(EncodeFrame(7, MsgData, make([]byte, 4096)))
			// One ordinary ACK, periodic HELLO, optional small NACK, bundle headers.
			control := len(qaPCSignal(pcAck, 7, 1)) + len(pcHelloPacket()) + len(qaPCSignal(pcNack, 9, 1)) + (1 + 4*4)
			wire := sender.pcNextData(capBytes-control, time.Now())
			if len(wire) != 4110 || len(wire)+control > capBytes {
				t.Fatalf("4KiB cannot progress at floor/fps%d: credit=%d control=%d wire=%d", fps, capBytes, control, len(wire))
			}
			sender.pcEnqueue(EncodeFrame(8, MsgData, make([]byte, 8192)))
			sender.pcEnqueue(EncodeFrame(9, MsgData, []byte("healthy")))
			sender.pcNextData(capBytes-control, time.Now())
			if sender.pcSendSeq[8] != 0 {
				t.Fatal("8KiB incorrectly fits low-rate credit")
			}
			if sender.pcSendSeq[9] != 1 {
				t.Fatal("oversized indivisible frame blocked independent connection")
			}
		})
	}
}

func TestPCQAFourKiBRetains128And512UnitWindows(t *testing.T) {
	for _, limit := range []int{128, 512} {
		t.Run(fmt.Sprint(limit), func(t *testing.T) {
			sender := pcTestTunnel(t)
			sender.pcOnAck([]byte{pcAck, 0, 0})
			if limit == 512 {
				sender.pcHandle(pcHelloPacket())
			}
			sender.dynRateKbps.Store(12000)
			sender.pcFlow.srtt = 500 * time.Millisecond
			now := time.Now()
			for offered := 0; offered < limit+64; {
				for i := 0; i < 32 && offered < limit+64; i++ {
					sender.pcEnqueue(EncodeFrame(7, MsgData, make([]byte, 4096)))
					offered++
				}
				sender.pcNextData(129096, now)
			}
			if int(sender.pcSendSeq[7]) != limit || len(sender.pcSendBuf) != limit {
				t.Fatalf("peer capacity not respected: seq=%d debt=%d limit=%d", sender.pcSendSeq[7], len(sender.pcSendBuf), limit)
			}
			retained := 0
			for _, unit := range sender.pcSendBuf {
				retained += len(unit)
			}
			if retained != limit*(4096+13) {
				t.Fatalf("unexpected retransmit bytes %d", retained)
			}
			sender.pcOnAck(qaPCSignal(pcAck, 7, uint32(limit)))
			if len(sender.pcSendBuf) != 0 {
				t.Fatal("ACK did not release large retained units")
			}
			sender.pcNextData(129096, now)
			if sender.pcSendSeq[7] <= uint32(limit) {
				t.Fatal("window stayed blocked after ACK")
			}
			t.Logf("peerUnits=%d retransmitPayloadAndHeaders=%d bytes", limit, retained)
		})
	}
}

// At200kbps only6-ish full4KiB units fit per second. The legacy8ACK/s gate
// cannot recover from its floor;4KiB must therefore remain restricted to a
// controller which gathers a longer delivery cohort. Include real unit+AEAD/
// RTP overhead conservatively rather than gifting the replay extra ACKs.
func TestPCQAFourKiBCleanFloorRecoveryRequiresFeedbackCohort(t *testing.T) {
	const wireBytes = 4096 + 13 + 16 + 64 + 5*40
	legacy, cohort := int64(200), int64(200)
	var ewma float64
	var legacyCredit, cohortCredit int64
	var controller pcRecoveryRate
	start := time.Now()
	for second := 1; second <= 30; second++ {
		legacyCredit += legacy * 1000 / 8
		cohortCredit += cohort * 1000 / 8
		oldUnits, newUnits := uint64(legacyCredit/wireBytes), uint64(cohortCredit/wireBytes)
		legacyCredit %= wireBytes
		cohortCredit %= wireBytes
		legacy, ewma = pcRateStep(legacy, 12000, oldUnits, 0, oldUnits, ewma)
		cohort = controller.step(start.Add(time.Duration(second)*time.Second), cohort, 12000, newUnits, 0, newUnits, 500*time.Millisecond)
	}
	if legacy != 200 {
		t.Fatalf("legacy limitation changed; reassess candidate gate: got %d", legacy)
	}
	if cohort <= 200 || cohort > 12000 {
		t.Fatalf("clean4KiBcohorts failed bounded recovery: %d", cohort)
	}
	t.Logf("30sec clean floor recovery: unsupported legacy=%d cohort=%d kbps", legacy, cohort)
}

// A connection with already-lost data must not consume every indivisible
// low-rate slot forever. This is a candidate safety gate, not a Mbps target.
func TestPCQAFourKiBRetryDebtMustNotStarveIndependentBulkAtFloor(t *testing.T) {
	sender := pcTestTunnel(t)
	sender.pcOnAck([]byte{pcAck, 0, 0})
	sender.dynRateKbps.Store(200)
	for i := 0; i < 40; i++ {
		sender.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, make([]byte, 4096))})
		sender.pcOnNack(qaPCSignal(pcNack, 7, uint32(i+1)))
	}
	sender.pcEnqueue(EncodeFrame(8, MsgData, make([]byte, 4096)))
	const budget = (200*1000/8/96)*2 + 4096
	start := time.Now().Add(time.Second)
	for tick := 0; tick < 20; tick++ {
		packet := sender.pcNextData(budget, start.Add(time.Duration(tick)*200*time.Millisecond))
		if len(packet) > budget {
			t.Fatal("candidate escaped aggregate credit bound")
		}
		if sender.pcSendSeq[8] > 0 {
			return
		}
	}
	t.Fatal("4KiB healthy bulk starved for4seconds by unrelated retry debt at floor")
}

func TestPCQAFourKiBFairnessCannotStarveRetriesOrExceedCredit(t *testing.T) {
	sender := pcTestTunnel(t)
	sender.pcOnAck([]byte{pcAck, 0, 0})
	sender.dynRateKbps.Store(200)
	sender.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, make([]byte, 4096))})
	const budget = (200*1000/8/96)*2 + 4096
	start := time.Now().Add(time.Second)
	retries, fresh := 0, 0
	for tick := 0; tick < 20; tick++ {
		sender.pcOnNack(qaPCSignal(pcNack, 7, 1))
		sender.pcEnqueue(EncodeFrame(8, MsgData, make([]byte, 4096)))
		packet := sender.pcNextData(budget, start.Add(time.Duration(tick)*200*time.Millisecond))
		if len(packet) != 4110 || len(packet) > budget {
			t.Fatalf("expected exactly one indivisible unit, got %d", len(packet))
		}
		id := binary.BigEndian.Uint32(packet[9:13])
		switch id {
		case 7:
			retries++
			if binary.BigEndian.Uint32(packet[1:5]) != 1 {
				t.Fatal("retry allocated a new sequence")
			}
		case 8:
			fresh++
		default:
			t.Fatalf("unexpected connection %d", id)
		}
	}
	if retries < 5 || fresh < 5 {
		t.Fatalf("one direction starved: retries=%d fresh=%d", retries, fresh)
	}
	t.Logf("20 low-rate slots: retries=%d fresh=%d", retries, fresh)
}

func TestPCQAFairnessNeverReservesForUnsendableOrBlockedNewUnits(t *testing.T) {
	for _, blocked := range []bool{false, true} {
		t.Run(fmt.Sprint("windowBlocked=", blocked), func(t *testing.T) {
			sender := pcTestTunnel(t)
			sender.pcOnAck([]byte{pcAck, 0, 0})
			sender.pcWrapFrames([][]byte{EncodeFrame(7, MsgData, make([]byte, 4096))})
			size := 8192
			if blocked {
				size = 4096
				sender.pcSendSeq[8] = uint32(sender.pcWindowLocked())
			}
			sender.pcEnqueue(EncodeFrame(8, MsgData, make([]byte, size)))
			sender.pcFlow.preferNew = true
			sender.pcOnNack(qaPCSignal(pcNack, 7, 1))
			packet := sender.pcNextData(4616, time.Now().Add(time.Second))
			if len(packet) != 4110 || binary.BigEndian.Uint32(packet[9:13]) != 7 {
				t.Fatal("unsendable new unit suppressed eligible retry")
			}
		})
	}
}

// Real production packetization, AEAD, reassembly and ARQ. The network fault
// injector is independent of relay unit IDs:100ms propagation,300ms sparse
// extra delay, and one permanently lost RTP datagram per509datagrams.
func TestPCQAFourKiBFileThroughActualDelayedLossyRTP(t *testing.T) {
	if !carrierMode || !carrierPCARQ {
		t.Skip("requires WB PCARQ environment")
	}
	for _, fps := range []int{24, 96} {
		t.Run(fmt.Sprint(fps), func(t *testing.T) {
			t.Setenv("WLB_PC_FPS", fmt.Sprint(fps))
			t.Setenv("WLB_PC_RATE_MODE", "")
			t.Setenv("WLB_CARRIER_KBPS", "12000")
			var peers [2]*VP8DataTunnel
			var links [2]*qaDelayedRTP
			for i := range peers {
				track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "qa-large", "qa")
				if err != nil {
					t.Fatal(err)
				}
				links[i] = &qaDelayedRTP{input: make(chan qaDelayedPacket, 4096), stop: make(chan struct{}), done: make(chan struct{})}
				if _, err = track.Bind(qaDelayedContext{link: links[i]}); err != nil {
					t.Fatal(err)
				}
				obf, _ := NewTunnelObfuscator([]byte("qa-large-file"))
				peers[i] = NewVP8DataTunnel(track, obf, pcNoop)
			}
			links[0].peer, links[1].peer = peers[1], peers[0]
			want := make([]byte, 1024*1024)
			for i := range want {
				want[i] = byte(i*37 + i/1126)
			}
			deliveries := make(chan []byte, 1024)
			peers[1].OnData = func(frame []byte) {
				DecodeFrames(frame, func(id uint32, kind byte, p []byte) {
					if id == 7 && kind == MsgData {
						deliveries <- append([]byte(nil), p...)
					}
				})
			}
			for i := range peers {
				go links[i].run()
				peers[i].Start(fps, 1)
			}
			defer func() {
				for i := range peers {
					peers[i].Stop()
					close(links[i].stop)
				}
				for i := range links {
					<-links[i].done
				}
			}()
			producerDone := make(chan struct{})
			go func() {
				defer close(producerDone)
				for off := 0; off < len(want); off += 4096 {
					peers[0].SendData(EncodeFrame(7, MsgData, want[off:off+4096]))
				}
			}()
			start := time.Now()
			deadline := time.NewTimer(45 * time.Second)
			defer deadline.Stop()
			var got bytes.Buffer
			for got.Len() < len(want) {
				select {
				case p := <-deliveries:
					got.Write(p)
				case <-deadline.C:
					t.Fatalf("4KiB file stalled %d/%d", got.Len(), len(want))
				}
			}
			<-producerDone
			if !bytes.Equal(got.Bytes(), want) {
				t.Fatal("4KiB file bytes corrupted/reordered")
			}
			if links[0].late.Load() == 0 || links[0].dropped.Load() == 0 {
				t.Fatal("missing real delay/loss fault coverage")
			}
			peers[0].pcSendMu.Lock()
			debt := len(peers[0].pcSendBuf)
			window := peers[0].pcWindowLocked()
			retained := 0
			for _, u := range peers[0].pcSendBuf {
				retained += len(u)
			}
			peers[0].pcSendMu.Unlock()
			if debt > window || retained > 512*(4096+13) {
				t.Fatal("large frames exceeded per-flow negotiated bound")
			}
			t.Logf("FPS=%d chunk=4096 bytes=%d SHA=%x elapsed=%s late=%d lost=%d debt=%d retained=%d", fps, got.Len(), sha256.Sum256(got.Bytes()), time.Since(start), links[0].late.Load(), links[0].dropped.Load(), debt, retained)
		})
	}
}
