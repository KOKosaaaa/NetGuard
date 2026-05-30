package deploy

import (
	"context"
	"errors"
	"fmt"
	"net"
	"testing"
)

// TestPortInUse occupies a real port and asserts the preflight detects it.
func TestPortInUse(t *testing.T) {
	l, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer l.Close()
	busy := l.Addr().(*net.TCPAddr).Port

	if !portInUse(busy) {
		t.Errorf("portInUse(%d) = false, want true (we are holding it)", busy)
	}

	// A port we just closed should read free.
	l2, _ := net.Listen("tcp", "0.0.0.0:0")
	free := l2.Addr().(*net.TCPAddr).Port
	l2.Close()
	if portInUse(free) {
		t.Errorf("portInUse(%d) = true, want false (just released)", free)
	}
}

// TestResolveInboundPort covers the three branches of improvements #1/#2.
func TestResolveInboundPort(t *testing.T) {
	ctx := context.Background()

	// 1) explicit busy port → ErrPortInUse
	l, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer l.Close()
	busy := l.Addr().(*net.TCPAddr).Port
	if _, err := resolveInboundPort(ctx, busy, false); !errors.Is(err, ErrPortInUse) {
		t.Errorf("explicit busy port: err = %v, want ErrPortInUse", err)
	}

	// 2) explicit free port → returned as-is
	l2, _ := net.Listen("tcp", "0.0.0.0:0")
	free := l2.Addr().(*net.TCPAddr).Port
	l2.Close()
	if got, err := resolveInboundPort(ctx, free, false); err != nil || got != free {
		t.Errorf("explicit free port: got %d err %v, want %d nil", got, err, free)
	}

	// 3) auto (port 0): always returns a non-zero, free port.
	got, err := resolveInboundPort(ctx, 0, false)
	if err != nil || got == 0 || portInUse(got) {
		t.Errorf("auto port: got %d err %v (want non-zero free port)", got, err)
	}
	fmt.Sprintf("%d", got) // silence any unused in odd build tags
}
