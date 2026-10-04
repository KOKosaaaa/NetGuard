// Package desktoptun brings up a TUN adapter, configures system-wide
// routing through it, and runs xjasonlyu/tun2socks as the engine that
// forwards every IP packet to a local SOCKS5 proxy.
//
// The package is the desktop counterpart to relay/androidbind. Android
// uses a VpnService fd plus addDisallowedApplication for the joiner's
// own traffic; on desktop OSes there is no per-process exclusion, so
// the joiner's signaling and SFU media flows are kept off the tunnel
// by installing /32 bypass routes through the original default gateway.
//
// Implementations live in desktoptun_windows.go (wintun), in
// desktoptun_linux.go (/dev/net/tun + iproute2), and in
// desktoptun_darwin.go (utun + route(8) + ifconfig). stub_other.go is
// a build stub for any platform that none of those cover.
package desktoptun
