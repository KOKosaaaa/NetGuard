package tunnel

import (
	"testing"
	"time"
)

func TestPCQAProbeRequiresFreshDeliveredLowLossCohort(t *testing.T) {
	t.Setenv("WLB_PC_RATE_MODE", "probe")
	for _, tc := range []struct {
		name              string
		cur, cap          int64
		sent, lost, acked uint64
		want              int64
	}{
		{"clean growth", 4000, 12000, 100, 0, 100, 6000},
		{"clean cap", 11900, 12000, 100, 0, 100, 12000},
		{"sparse fresh sends", 4000, 12000, 15, 0, 100, 4300},
		{"ACK only", 4000, 12000, 0, 0, 100, 4000},
		{"delivery deficit", 4000, 12000, 100, 0, 80, 4000},
		{"two percent boundary", 4000, 12000, 100, 2, 100, 4300},
		{"repaired ten percent", 4000, 12000, 100, 10, 100, 4300},
		{"unrepaired congestion", 4000, 12000, 100, 30, 60, 3000},
		{"small provider cap", 100, 100, 100, 0, 100, 100},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var controller pcRecoveryRate
			now := time.Now()
			first := controller.step(now, tc.cur, tc.cap, tc.sent, tc.lost, tc.acked, 250*time.Millisecond)
			if first != tc.cur {
				t.Fatalf("acted before cohort complete: %d", first)
			}
			second := controller.step(now.Add(time.Second), first, tc.cap, tc.sent, tc.lost, tc.acked, 250*time.Millisecond)
			if second != tc.want {
				t.Fatalf("got %d want %d", second, tc.want)
			}
		})
	}
}

func TestPCQALargeReadsRequireCohortController(t *testing.T) {
	t.Setenv("WLB_PC_CHUNK", "4096")
	for _, mode := range []string{"", "legacy", "recovery", "probe"} {
		t.Setenv("WLB_PC_RATE_MODE", mode)
		want := 1126
		if mode == "probe" {
			want = 4096
		}
		if got := PCRelayReadBuffer(); got != want {
			t.Fatalf("mode %q: got %d want %d", mode, got, want)
		}
	}
	t.Setenv("WLB_PC_CHUNK", "8192")
	if PCRelayReadBuffer() != 1126 {
		t.Fatal("untransmittable large chunk enabled")
	}
}
