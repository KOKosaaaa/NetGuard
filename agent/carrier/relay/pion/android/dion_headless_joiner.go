package android

import (
	"log"
	"strings"

	"whitelist-bypass/relay/common"
	joiner "whitelist-bypass/relay/pion/headless-joiner-common"
	"whitelist-bypass/relay/tunnel"
)

type DionHeadlessJoiner struct {
	inner       *joiner.DionHeadlessJoiner
	OnConnected func(tunnel.DataTunnel)
}

func NewDionHeadlessJoiner(logFn func(string, ...any)) *DionHeadlessJoiner {
	if logFn == nil {
		logFn = log.Printf
	}
	inner := joiner.NewDionHeadlessJoiner(logFn, RequestResolve, StatusEmitter{}, PCConfigurer{})
	wrapper := &DionHeadlessJoiner{inner: inner}
	inner.OnConnected = func(tun tunnel.DataTunnel) {
		if wrapper.OnConnected != nil {
			wrapper.OnConnected(tun)
		}
	}
	return wrapper
}

func (j *DionHeadlessJoiner) Run() {
	j.inner.Status.EmitStatus(common.StatusReady)
	for {
		line, err := ReadStdinLine()
		if err != nil {
			log.Printf("dion-joiner: stdin closed: %v", err)
			return
		}
		if strings.HasPrefix(line, "JOIN:") {
			j.inner.RunWithParams(strings.TrimPrefix(line, "JOIN:"))
			return
		}
	}
}
