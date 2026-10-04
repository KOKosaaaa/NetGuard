package common

import (
	"encoding/binary"
	"net"
	"testing"
)

func TestDomainAddressAcceptsLiteralIPv6(t *testing.T) {
	for _, host := range []string{"example.com", "127.0.0.1", "2001:b28:f23d:f001::a"} {
		request := []byte{5, 1, 0, AtypDomain, byte(len(host))}
		request = append(request, host...)
		request = binary.BigEndian.AppendUint16(request, 443)
		address, consumed, err := ParseAddress(request, len(request))
		if err != nil || consumed != len(request) {
			t.Fatalf("parse: %v %d", err, consumed)
		}
		actual, port, err := net.SplitHostPort(address)
		if err != nil || actual != host || port != "443" {
			t.Fatalf("not dialable: %q: %v", address, err)
		}
	}
}
