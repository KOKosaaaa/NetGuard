package tunnel

import (
	"testing"
	"time"
)

func TestPCQARecoveryDoesNotCutAgainBeforeDelayedFeedbackCanArrive(t *testing.T) {
	for _, rto := range []time.Duration{300 * time.Millisecond, 1500 * time.Millisecond, 4 * time.Second} {
		var c pcRecoveryRate
		now := time.Unix(1000, 0)
		rate := int64(12000)
		gap := 2 * rto
		if gap < 2*time.Second {
			gap = 2 * time.Second
		}
		if gap > 5*time.Second {
			gap = 5 * time.Second
		}
		var lastCut time.Time
		cuts := 0
		for i := 0; i < 100; i++ {
			// Production feeds one-second counter deltas, not overlapping samples.
			at := now.Add(time.Duration(i) * time.Second)
			next := c.step(at, rate, 12000, 100, 30, 20, rto)
			if next < rate {
				if !lastCut.IsZero() && at.Sub(lastCut) < gap {
					t.Fatalf("RTO=%s: repeated cut after only %s, feedback horizon=%s", rto, at.Sub(lastCut), gap)
				}
				lastCut = at
				cuts++
			}
			rate = next
		}
		if cuts == 0 || rate >= 6000 {
			t.Fatalf("sustained true delivery deficit never backed off: rto=%s cuts=%d rate=%d", rto, cuts, rate)
		}
	}
}

func TestPCQARecoveryRepairedLossWithFullDeliveryMustNotCollapseForever(t *testing.T) {
	var c pcRecoveryRate
	now := time.Unix(2000, 0)
	rate := int64(2000)
	lowest := rate
	for second := 0; second < 60; second++ {
		// Constant 10% delayed/repaired reports, with every original unit
		// cumulatively delivered. This describes reliability work, not an
		// accumulating network backlog. No artificial zero-loss seconds.
		rate = c.step(now.Add(time.Duration(second)*time.Second), rate, 12000, 200, 20, 200, 400*time.Millisecond)
		if rate < lowest {
			lowest = rate
		}
	}
	if lowest < 500 || rate <= 2000 {
		t.Fatalf("productive repaired delivery pinned rate: min=%d final=%d", lowest, rate)
	}
}

func TestPCQARecoveryOldAckOnlyCannotIncreaseRate(t *testing.T) {
	var c pcRecoveryRate
	rate := int64(1000)
	now := time.Unix(3000, 0)
	for i := 0; i < 60; i++ {
		rate = c.step(now.Add(time.Duration(i)*time.Second), rate, 12000, 0, 0, 100, 750*time.Millisecond)
		if rate != 1000 {
			t.Fatalf("old ACK-only recovery raised rate to%d without new traffic", rate)
		}
	}
}

func TestPCQARecoveryRateAlwaysRespectsConfiguredCeiling(t *testing.T) {
	for _, max := range []int64{100, 200, 2800, 12000} {
		var c pcRecoveryRate
		rate := max
		now := time.Unix(4000, 0)
		for i := 0; i < 90; i++ {
			sent, nack, acked := uint64(100), uint64(0), uint64(100)
			if i%12 < 6 {
				nack, acked = 30, 10
			}
			rate = c.step(now.Add(time.Duration(i)*time.Second), rate, max, sent, nack, acked, time.Second)
			floor := int64(200)
			if max < floor {
				floor = max
			}
			if rate < floor || rate > max {
				t.Fatalf("configured ceiling %d violated: %d", max, rate)
			}
		}
	}
}

func TestPCQARecoveryReplayPhoneRepairedBacklogCanLeaveFloor(t *testing.T) {
	// Sanitized real phone trace 2026-09-29 17:43MSK. Original pacing spent
	// 42/53 logged intervals at200kbps despite1350sent/1349ACKed and only
	// 4..19unacked of128window. Freeze observations to compare reactions;
	// this replay is NOT a simulation or prediction of counterfactual Mbps.
	trace := [][3]uint64{
		{35, 2, 40}, {26, 5, 27}, {34, 4, 31}, {28, 1, 32}, {27, 0, 25}, {40, 7, 37}, {37, 8, 28},
		{24, 2, 40}, {26, 2, 20}, {28, 1, 27}, {26, 3, 32}, {27, 0, 26}, {46, 5, 44}, {42, 12, 36},
		{32, 9, 30}, {28, 1, 35}, {24, 4, 19}, {23, 6, 33}, {27, 0, 26}, {48, 5, 41}, {34, 4, 38},
		{23, 2, 23}, {25, 1, 22}, {24, 2, 27}, {23, 5, 22}, {23, 4, 28}, {28, 2, 22}, {23, 2, 25},
		{24, 4, 25}, {26, 3, 21}, {21, 4, 29}, {22, 4, 12}, {20, 3, 22}, {24, 3, 29}, {24, 0, 25},
		{29, 9, 23}, {23, 5, 24}, {18, 3, 20}, {17, 5, 18}, {14, 7, 12}, {20, 1, 15}, {19, 4, 25},
		{18, 5, 21}, {17, 3, 18}, {21, 0, 22}, {34, 4, 26}, {18, 8, 17}, {20, 2, 21}, {20, 3, 27},
		{23, 2, 20}, {21, 1, 23}, {18, 4, 18}, {8, 0, 0},
	}
	var c pcRecoveryRate
	rate := int64(200)
	now := time.Unix(5000, 0)
	floorIntervals := 0
	for i, v := range trace {
		rate = c.step(now.Add(time.Duration(i)*time.Second), rate, 12000, v[0], v[1], v[2], 600*time.Millisecond)
		if rate == 200 {
			floorIntervals++
		}
	}
	t.Logf("observed phone replay: final=%dkbps floor=%d/%d intervals", rate, floorIntervals, len(trace))
	if rate < 800 || floorIntervals > len(trace)/2 {
		t.Fatal("repaired phone delivery remained trapped at floor")
	}
}
