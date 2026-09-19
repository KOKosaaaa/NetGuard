package stripe

import "time"

type retransmissionTimer struct {
	smooth, variance float64
	timeout          time.Duration
}

func (r *retransmissionTimer) current() time.Duration {
	if r.timeout == 0 {
		return 2500 * time.Millisecond
	}
	return r.timeout
}
func (r *retransmissionTimer) sample(d time.Duration) {
	v := float64(d)
	if r.smooth == 0 {
		r.smooth = v
		r.variance = v / 2
	} else {
		diff := r.smooth - v
		if diff < 0 {
			diff = -diff
		}
		r.variance = .75*r.variance + .25*diff
		r.smooth = .875*r.smooth + .125*v
	}
	r.timeout = time.Duration(r.smooth+4*r.variance) + 700*time.Millisecond
	if r.timeout < 1500*time.Millisecond {
		r.timeout = 1500 * time.Millisecond
	}
	if r.timeout > 30*time.Second {
		r.timeout = 30 * time.Second
	}
}
func (r *retransmissionTimer) backoff() {
	r.timeout = r.current() * 2
	if r.timeout > 30*time.Second {
		r.timeout = 30 * time.Second
	}
}
