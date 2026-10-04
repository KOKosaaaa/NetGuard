package tunnel

import (
	"strconv"
	"testing"
	"time"
)

func TestPCQAExperimentalFPSDefaultsAndRejectsUnsafeValues(t *testing.T) {
	for _, raw := range []string{"", "0", "-1", "23", "121", "999999999999999999999", "fast", "96.0", " 96", "96\n"} {
		t.Run(strconv.Quote(raw), func(t *testing.T) {
			t.Setenv("WLB_PC_FPS", raw)
			if got := pcCarrierFPS(); got != 24 {
				t.Fatalf("invalid/unset FPS %q activated experimental cadence %d", raw, got)
			}
		})
	}
	for _, fps := range []int{24, 25, 48, 96, 120} {
		t.Run(strconv.Itoa(fps), func(t *testing.T) {
			t.Setenv("WLB_PC_FPS", strconv.Itoa(fps))
			if got := pcCarrierFPS(); got != fps {
				t.Fatalf("requested %d FPS got %d", fps, got)
			}
			// The accepted range must never permit a saved-credit sample larger
			// than the already verified 24FPS maximum at the unchanged 12M ceiling.
			credit := (12000*1000/8/pcCarrierFPS())*2 + 4096
			if credit > 129096 || time.Second/time.Duration(pcCarrierFPS()) <= 0 {
				t.Fatal("experimental cadence exceeded sample/timer bounds")
			}
		})
	}
}

func TestPCQARecoveryHistoricLossAfterIdleCannotCutOnTinyCurrentLoss(t *testing.T) {
	var c pcRecoveryRate
	now := time.Unix(6000, 0)
	rate := int64(6000)
	for second := 0; second < 6; second++ {
		rate = c.step(now.Add(time.Duration(second)*time.Second), rate, 12000, 100, 100, 20, 400*time.Millisecond)
	}
	for second := 6; second < 66; second++ {
		rate = c.step(now.Add(time.Duration(second)*time.Second), rate, 12000, 0, 0, 0, 400*time.Millisecond)
	}
	before := rate
	for second := 66; second < 76; second++ {
		// ACK lag alone plus a rare fresh report must not revive old loss cuts.
		rate = c.step(now.Add(time.Duration(second)*time.Second), rate, 12000, 100, 1, 20, 400*time.Millisecond)
		if rate != before {
			t.Fatalf("historic EWMA changed rate on 1%% current loss: %d -> %d", before, rate)
		}
	}
}
