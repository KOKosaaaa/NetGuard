package tunnel

import (
	"bytes"
	"fmt"
	"sync"
	"testing"
	"time"
)

// ACKs follow socket consumption. A blocked socket on one connection must not
// prevent a second stream from progressing, even when the first advertises no
// consumption for an entire sender window. Uses real receiver consumers and
// wire parsing; virtual scheduler ticks are not a throughput benchmark.
func TestPCQASlowConsumerDoesNotBlockIndependentFileAndRecovers(t *testing.T) {
	for _, chunk := range []int{1126, 4096} {
		t.Run(fmt.Sprint(chunk), func(t *testing.T) {
			sender, receiver := pcTestTunnel(t), pcTestTunnel(t)
			sender.pcHandle(pcHelloPacket())
			receiver.pcHandle(pcHelloPacket())
			sender.dynRateKbps.Store(200)
			gate := make(chan struct{})
			var release sync.Once
			defer release.Do(func() { close(gate) })
			want := make([]byte, 200*chunk)
			for i := range want {
				want[i] = byte(i*41 + i/chunk)
			}
			var mu sync.Mutex
			var slow, healthy bytes.Buffer
			receiver.OnData = func(fr []byte) {
				DecodeFrames(fr, func(id uint32, kind byte, p []byte) {
					if kind != MsgData {
						return
					}
					if id == 7 {
						<-gate
					}
					mu.Lock()
					defer mu.Unlock()
					if id == 7 {
						slow.Write(p)
					} else if id == 8 {
						healthy.Write(p)
					}
				})
			}
			for off := 0; off < len(want); off += chunk {
				sender.pcEnqueue(EncodeFrame(7, MsgData, want[off:off+chunk]))
				sender.pcEnqueue(EncodeFrame(8, MsgData, want[off:off+chunk]))
			}
			const budget = (200*1000/8/96)*2 + 4096
			start := time.Now()
			released := false
			for tick := 0; tick < 1600; tick++ {
				wire := sender.pcNextData(budget, start.Add(time.Duration(tick)*200*time.Millisecond))
				if len(wire) > budget {
					t.Fatal("slow consumer escaped credit cap")
				}
				if len(wire) > 0 {
					receiver.pcHandle(wire)
				}
				time.Sleep(time.Millisecond) // let the real per-connection consumers run
				sender.pcOnAckAt(receiver.pcAckPacket(), start.Add(time.Duration(tick)*200*time.Millisecond+50*time.Millisecond))
				mu.Lock()
				nSlow, nHealthy := slow.Len(), healthy.Len()
				mu.Unlock()
				if !released && nHealthy == len(want) {
					if nSlow != 0 {
						t.Fatal("fixture slow socket did not remain blocked")
					}
					if sender.pcSendSeq[7] != pcWindow {
						t.Fatalf("blocked socket did not fill conservative window: %d", sender.pcSendSeq[7])
					}
					released = true
					release.Do(func() { close(gate) })
				}
				if released && nSlow == len(want) {
					mu.Lock()
					equal := bytes.Equal(slow.Bytes(), want) && bytes.Equal(healthy.Bytes(), want)
					mu.Unlock()
					if !equal {
						t.Fatal("slow-consumer recovery changed ordered bytes")
					}
					t.Logf("chunk=%d bothfiles=%d bytes preserved after blocked-consumer release", chunk, len(want))
					return
				}
			}
			mu.Lock()
			nSlow, nHealthy := slow.Len(), healthy.Len()
			mu.Unlock()
			t.Fatalf("consumer isolation/recovery stalled: slow=%d healthy=%d want=%d released=%v", nSlow, nHealthy, len(want), released)
		})
	}
}
