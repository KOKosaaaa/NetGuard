// netguard-agent — control-plane daemon for self-hosted NetGuard VPN.
//
// Phase 1 (current): pair + bearer + rotate + revoke, xray deploy with
// idempotency / rollback / healthcheck, task queue persisted in SQLite,
// status endpoint with /proc-derived host telemetry.
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
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
	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
	"github.com/KOKosaaaa/NetGuard/agent/internal/tlsutil"
)

const agentVersion = "0.4.1"

func main() {
	listen := flag.String("listen", ":9443", "address to serve HTTPS on")
	stateDir := flag.String("state", "/var/lib/netguard-agent",
		"directory for state.db, TLS material, pair-token file")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	// --version is the smoke-test the upload self-update runs on a freshly
	// uploaded binary before swapping it in (verifies it executes here).
	if *showVersion {
		fmt.Println(agentVersion)
		return
	}

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

	db, err := storage.Open(filepath.Join(*stateDir, "state.db"))
	if err != nil {
		log.Fatalf("storage: %v", err)
	}
	defer db.Close()

	// Mark anything left pending/running by a previous crash as failed.
	// We do NOT auto-resume — too many half-finished states to reason
	// about; the operator hits Retry from the Android UI.
	if n, err := db.FailRunningTasksOnStartup(); err != nil {
		log.Printf("WARN fail-on-startup: %v", err)
	} else if n > 0 {
		log.Printf("flagged %d tasks left over from previous run as failed", n)
	}

	authMgr := auth.New(db)
	if _, err := authMgr.EnsurePairToken(); err != nil {
		log.Fatalf("pair token: %v", err)
	}
	log.Printf("pair token ready at %s (TTL %s)",
		auth.PairTokenPath, auth.PairTokenTTL)

	taskMgr := tasks.NewManager(db)

	cert, err := tls.X509KeyPair(identity.CertPEM, identity.KeyPEM)
	if err != nil {
		log.Fatalf("load x509 keypair: %v", err)
	}

	handler := api.Mount(&api.Deps{
		Auth:          authMgr,
		Tasks:         taskMgr,
		DB:            db,
		AgentVersion:  agentVersion,
		AgentStarted:  time.Now(),
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
		// xray deploy + future telemost deploy can stream long responses
		// (poll-style logs are out of scope; we use task IDs). Keep this
		// generous so unrelated long-running PROXY requests don't timeout.
		WriteTimeout: 5 * time.Minute,
		IdleTimeout:  120 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(),
		os.Interrupt, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() {
		log.Printf("listening on https://%s", *listen)
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
