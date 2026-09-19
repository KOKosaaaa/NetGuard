package stripe

import (
	"errors"
	"time"
)

var errPipeBusy = errors.New("stripe pipe unavailable or queue full")

// Each physical pipe has its own bounded writer. A slow destination never
// blocks the shared reader or another pipe's ACKs.
func (p *pipe) write(f Frame) error {
	if p.dead.Load() {
		return errPipeBusy
	}
	q := p.control
	if f.Type == FrameData {
		q = p.data
	}
	select {
	case q <- f:
		return nil
	default:
		if f.Type != FrameData {
			p.close()
		}
		return errPipeBusy
	}
}

func (p *pipe) close() {
	p.closeOnce.Do(func() {
		p.dead.Store(true)
		close(p.done)
		_ = p.conn.Close()
	})
}

func (p *pipe) writeLoop() {
	defer p.close()
	controls := 0
	for {
		select {
		case <-p.done:
			return
		default:
		}
		var f Frame
		if controls < 8 {
			select {
			case f = <-p.control:
				controls++
			default:
			}
		}
		if f.Type == 0 {
			select {
			case <-p.done:
				return
			case f = <-p.control:
				controls++
			case f = <-p.data:
				controls = 0
			}
		}
		wire := f.Encode(nil)
		_ = p.conn.SetWriteDeadline(time.Now().Add(8 * time.Second))
		for len(wire) > 0 {
			n, err := p.conn.Write(wire)
			if err != nil || n == 0 {
				return
			}
			p.wrote.Add(int64(n))
			wire = wire[n:]
		}
		if f.Type == FrameData {
			// Pacing happens only in this pipe's writer, never in route().
			t := time.NewTimer(time.Duration(float64(HeaderSize+len(f.Payload)) / pipeRateBytesPerSec * float64(time.Second)))
			select {
			case <-p.done:
				t.Stop()
				return
			case <-t.C:
			}
		}
	}
}
