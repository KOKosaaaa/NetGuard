package wbstream

import "time"

// Protected by Session.receiveMu. Tracks are registered only after successful
// carrier authentication; ordinary WB participants never enter this map.
type carrierPeerGate struct {
	epoch    uint32
	lastData time.Time
	tracks   map[uint32]int
}

func (g *carrierPeerGate) accept(epoch uint32, trackEpoch *uint32, now time.Time) (bool, bool) {
	if epoch == 0 || (*trackEpoch != 0 && *trackEpoch != epoch) {
		return false, false
	}
	if *trackEpoch == 0 {
		if g.tracks == nil {
			g.tracks = make(map[uint32]int)
		}
		*trackEpoch = epoch
		g.tracks[epoch]++
	}
	if g.epoch != 0 && g.epoch != epoch && g.tracks[g.epoch] > 0 && now.Sub(g.lastData) < 30*time.Second {
		return false, false
	}
	replaced := g.epoch != 0 && g.epoch != epoch
	g.epoch = epoch
	g.lastData = now
	return true, replaced
}

func (g *carrierPeerGate) closeTrack(epoch uint32) {
	if g.tracks[epoch] <= 1 {
		delete(g.tracks, epoch)
	} else {
		g.tracks[epoch]--
	}
}
