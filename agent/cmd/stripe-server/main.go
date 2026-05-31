// Command stripe-server is the exit-side endpoint of NetGuard's Telemost
// room-striping mux. It is embedded in the agent and deployed to the exit
// server as /usr/local/bin/netguard-stripe-server, listening on loopback.
//
// The Telemost creator dials 127.0.0.1:<port> for each room pipe, so this
// binary only ever needs to listen on loopback; it dials the real flow
// destinations outbound. See agent/internal/stripe for the protocol.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os/signal"
	"syscall"

	"github.com/KOKosaaaa/NetGuard/agent/internal/stripe"
)

func main() {
	port := flag.Int("port", 38500, "loopback TCP port to accept room pipes on")
	flag.Parse()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	addr := fmt.Sprintf("127.0.0.1:%d", *port)
	l, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("stripe-server: listen %s: %v", addr, err)
	}
	log.Printf("netguard-stripe-server listening on %s", addr)

	srv := stripe.NewServer(log.Printf)
	if err := srv.Serve(ctx, l); err != nil && ctx.Err() == nil {
		log.Fatalf("stripe-server: serve: %v", err)
	}
	log.Printf("netguard-stripe-server shut down")
}
