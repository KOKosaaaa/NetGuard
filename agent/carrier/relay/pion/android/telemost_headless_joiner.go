package android

import (
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/pion"
	joiner "whitelist-bypass/relay/pion/headless-joiner-common"
	"whitelist-bypass/relay/tunnel"
)

type TelemostHeadlessJoiner struct {
	inner       *joiner.TelemostHeadlessJoiner
	OnConnected func(tunnel.DataTunnel)
}

func NewTelemostHeadlessJoiner(logFn func(string, ...any)) *TelemostHeadlessJoiner {
	if logFn == nil {
		logFn = log.Printf
	}
	inner := joiner.NewTelemostHeadlessJoiner(logFn, RequestResolve, StatusEmitter{}, PCConfigurer{}, pion.AddTunnelTracks, pion.ReadTrack)
	wrapper := &TelemostHeadlessJoiner{inner: inner}
	inner.OnConnected = func(tun tunnel.DataTunnel) {
		if wrapper.OnConnected != nil {
			wrapper.OnConnected(tun)
		}
	}
	return wrapper
}

func (j *TelemostHeadlessJoiner) Run() {
	// Graceful leave: NetGuard's stop() closes our stdin and then SIGTERMs
	// the process. While a JOIN is active we're blocked in RunWithParams (not
	// reading stdin), so the SIGTERM is the path that actually fires on stop.
	// Catch it and call inner.Close(), which self-kicks us off the SFU so we
	// don't leave a ghost in the room. inner.Close() is idempotent. NetGuard
	// gives a short grace window before SIGKILL so the self-kick HTTP lands.
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigCh
		log.Printf("telemost-joiner: signal received, graceful leave")
		j.inner.Close()
		os.Exit(0)
	}()
	j.inner.Status.EmitStatus(common.StatusReady)
	for {
		line, err := ReadStdinLine()
		if err != nil {
			log.Printf("telemost-joiner: stdin closed: %v", err)
			j.inner.Close()
			return
		}
		if strings.HasPrefix(line, "JOIN:") {
			j.inner.RunWithParams(strings.TrimPrefix(line, "JOIN:"))
			j.inner.Close()
			return
		}
	}
}
