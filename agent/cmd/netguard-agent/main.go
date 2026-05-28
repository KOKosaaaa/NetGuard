// netguard-agent — control-plane daemon for self-hosted NetGuard VPN.
//
// Phase 0 (MVP): serves /v1/health, /v1/status, /v1/auth/pair over
// HTTPS:9443 with a self-signed cert (SPKI-pinned by the Android client).
//
// Run: netguard-agent [-listen :9443] [-state /var/lib/netguard-agent]
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/api"
	"github.com/KOKosaaaa/NetGuard/agent/internal/auth"
	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
	"github.com/KOKosaaaa/NetGuard/agent/internal/tlsutil"
)

// Bumped on each public release. Surfaced via /v1/health and /v1/status so
// the Android client can decide whether to nudge the user to update.
const agentVersion = "0.1.0"

func main() {
	listen := flag.String("listen", ":9443", "address to serve HTTPS on")
	stateDir := flag.String("state", "/var/lib/netguard-agent",
		"directory for state.json, TLS material, pair-token file")
	flag.Parse()

	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	log.Printf("netguard-agent %s starting (listen=%s state=%s)",
		agentVersion, *listen, *stateDir)

	if err := os.MkdirAll(*stateDir, 0o700); err != nil {
		log.Fatalf("mkdir state dir: %v", err)
	}

	identity, err := tlsutil.LoadOrGenerate(filepath.Join(*stateDir, "tls"))
	if err != nil {
		log.Fatalf("tls: %v", err)
	}
	log.Printf("TLS SPKI pin (sha256): %x", identity.SPKIHash)

	store, err := storage.Open(filepath.Join(*stateDir, "state.json"))
	if err != nil {
		log.Fatalf("storage: %v", err)
	}
	authMgr := auth.New(store)
	tok, err := authMgr.EnsurePairToken()
	if err != nil {
		log.Fatalf("pair token: %v", err)
	}
	log.Printf("pair token ready at %s (TTL %s)", auth.PairTokenPath, auth.PairTokenTTL)
	_ = tok // token is on disk; we don't echo it to the log on purpose

	cert, err := tls.X509KeyPair(identity.CertPEM, identity.KeyPEM)
	if err != nil {
		log.Fatalf("load x509 keypair: %v", err)
	}

	handler := api.Mount(&api.Deps{
		Auth:          authMgr,
		AgentVersion:  agentVersion,
		AgentStarted:  time.Now(),
		// Names match the systemd units the agent will eventually deploy.
		// They show up in /v1/status as inactive until something runs.
		KnownServices: []string{"xray", "sing-box", "headless-telemost-creator"},
	})

	srv := &http.Server{
		Addr:    *listen,
		Handler: handler,
		TLSConfig: &tls.Config{
			Certificates: []tls.Certificate{cert},
			MinVersion:   tls.VersionTLS12,
		},
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(),
		os.Interrupt, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() {
		log.Printf("listening on https://%s", *listen)
		// Empty file args because cert+key are in TLSConfig already.
		errCh <- srv.ListenAndServeTLS("", "")
	}()

	select {
	case <-ctx.Done():
		log.Print("signal received, shutting down")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			log.Printf("graceful shutdown failed: %v", err)
		}
	case err := <-errCh:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("server: %v", err)
		}
	}
	log.Print("bye")
}
