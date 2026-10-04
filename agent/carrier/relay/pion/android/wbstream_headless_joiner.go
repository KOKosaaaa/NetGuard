package android

import (
	"log"
	"strings"

	"whitelist-bypass/relay/common"
	joiner "whitelist-bypass/relay/pion/headless-joiner-common"
	"whitelist-bypass/relay/tunnel"
)

type WBStreamHeadlessJoiner struct {
	inner       *joiner.WBStreamHeadlessJoiner
	OnConnected func(tunnel.DataTunnel)
}

func NewWBStreamHeadlessJoiner(logFn func(string, ...any)) *WBStreamHeadlessJoiner {
	if logFn == nil {
		logFn = log.Printf
	}
	inner := joiner.NewWBStreamHeadlessJoiner(logFn, RequestResolve, StatusEmitter{}, PCConfigurer{})
	wrapper := &WBStreamHeadlessJoiner{inner: inner}
	inner.OnConnected = func(tun tunnel.DataTunnel) {
		if wrapper.OnConnected != nil {
			wrapper.OnConnected(tun)
		}
	}
	return wrapper
}

func (j *WBStreamHeadlessJoiner) MarkConfigAcked() { j.inner.MarkConfigAcked() }

func (j *WBStreamHeadlessJoiner) Run() {
	j.inner.Status.EmitStatus(common.StatusReady)
	for {
		line, err := ReadStdinLine()
		if err != nil {
			log.Printf("wbstream-joiner: stdin closed: %v", err)
			return
		}
		if strings.HasPrefix(line, "JOIN:") {
			j.inner.RunWithParams(strings.TrimPrefix(line, "JOIN:"))
			return
		}
	}
}
