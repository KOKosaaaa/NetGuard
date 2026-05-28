// Package api wires HTTP handlers to the auth/sysinfo/storage layers.
//
// Phase-0 surface:
//
//	GET  /v1/health        — unauthenticated. Bootstrap heartbeat.
//	GET  /v1/status        — auth required. Host telemetry.
//	POST /v1/auth/pair     — exchange one-shot pair-token for bearer.
//
// All non-trivial errors map to {"error":{"code":"E_...","message":"..."}}
// with an HTTP status. The Android client switches on the code string —
// the human-readable message is logged but never translated client-side.
package api

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/auth"
	"github.com/KOKosaaaa/NetGuard/agent/internal/sysinfo"
)

// Deps is the runtime state every handler reaches into.
type Deps struct {
	Auth          *auth.Manager
	AgentVersion  string
	AgentStarted  time.Time
	KnownServices []string
}

// Mount returns an http.Handler for /v1/* with d as backing state.
func Mount(d *Deps) http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":         true,
			"version":    d.AgentVersion,
			"started_at": d.AgentStarted.Format(time.RFC3339),
		})
	})

	mux.HandleFunc("POST /v1/auth/pair", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			PairToken  string `json:"pair_token"`
			DeviceName string `json:"device_name"`
			AppVersion string `json:"app_version"`
		}
		if !decodeJSON(w, r, &req) {
			return
		}
		bearer, token, err := d.Auth.Pair(req.PairToken, req.DeviceName, req.AppVersion)
		if err != nil {
			writePairError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"bearer":     token,
			"expires_at": bearer.ExpiresAt.Format(time.RFC3339),
		})
	})

	mux.Handle("GET /v1/status", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			s := sysinfo.Gather(d.AgentVersion, d.AgentStarted, d.KnownServices)
			writeJSON(w, http.StatusOK, s)
		},
	)))

	return logMiddleware(mux)
}

// authenticated wraps h to require a valid bearer token.
func authenticated(d *Deps, h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := extractBearer(r)
		if _, err := d.Auth.Verify(token); err != nil {
			writeError(w, http.StatusUnauthorized, "E_UNAUTHORIZED", err.Error())
			return
		}
		h.ServeHTTP(w, r)
	})
}

func extractBearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		return ""
	}
	return strings.TrimPrefix(h, "Bearer ")
}

func writePairError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, auth.ErrPairTokenMissing):
		writeError(w, http.StatusGone, "E_PAIR_NO_TOKEN",
			"no pair token outstanding; re-run bootstrap")
	case errors.Is(err, auth.ErrPairTokenExpired):
		writeError(w, http.StatusGone, "E_PAIR_EXPIRED",
			"pair token expired; re-run bootstrap")
	case errors.Is(err, auth.ErrPairTokenWrong):
		writeError(w, http.StatusUnauthorized, "E_PAIR_WRONG",
			"pair token mismatch")
	default:
		writeError(w, http.StatusInternalServerError, "E_INTERNAL", err.Error())
	}
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]any{
		"error": map[string]any{"code": code, "message": msg},
	})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		log.Printf("encode response: %v", err)
	}
}

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 64*1024)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		writeError(w, http.StatusBadRequest, "E_BAD_JSON", err.Error())
		return false
	}
	return true
}

// logMiddleware emits one short line per request for journald. Adds a
// timing field so /v1/status latency regressions show up immediately.
func logMiddleware(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: 200}
		h.ServeHTTP(sw, r)
		log.Printf("%s %s -> %d (%s) %s", r.Method, r.URL.Path,
			sw.status, time.Since(start).Round(time.Microsecond), r.RemoteAddr)
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (s *statusWriter) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}
