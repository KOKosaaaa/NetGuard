// Package api wires HTTP handlers to the auth/sysinfo/storage/tasks layers.
//
// Phase-1 surface:
//
//	GET    /v1/health        — no auth, bootstrap heartbeat
//	GET    /v1/status        — host telemetry (bearer)
//	POST   /v1/auth/pair     — exchange pair-token for bearer
//	POST   /v1/auth/rotate   — issue new bearer if old one is close to expiry
//	POST   /v1/auth/revoke   — kill the current bearer
//	POST   /v1/xray/deploy   — async install xray, returns task_id
//	POST   /v1/xray/profile  — add VLESS inbound, returns vless:// URI
//	DELETE /v1/xray/profile/{id} — remove inbound
//	GET    /v1/xray/inbounds — list current inbounds
//	GET    /v1/tasks/{id}    — poll task status
//
// Error envelope: {"error":{"code":"E_...","message":"..."}} + non-200.
package api

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/auth"
	"github.com/KOKosaaaa/NetGuard/agent/internal/deploy"
	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
	"github.com/KOKosaaaa/NetGuard/agent/internal/sysinfo"
	"github.com/KOKosaaaa/NetGuard/agent/internal/tasks"
)

type Deps struct {
	Auth          *auth.Manager
	Tasks         *tasks.Manager
	DB            *storage.DB
	AgentVersion  string
	AgentStarted  time.Time
	KnownServices []string
}

func Mount(d *Deps) http.Handler {
	mux := http.NewServeMux()

	// --- unauth ---
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
		b, err := d.Auth.Pair(req.PairToken, req.DeviceName, req.AppVersion)
		if err != nil {
			writePairError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"bearer":     b.Token,
			"expires_at": b.ExpiresAt.Format(time.RFC3339),
		})
	})

	// --- authed ---
	mux.Handle("GET /v1/status", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			s := sysinfo.Gather(d.AgentVersion, d.AgentStarted, d.KnownServices)
			writeJSON(w, http.StatusOK, s)
		},
	)))

	mux.Handle("POST /v1/auth/rotate", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			tok := extractBearer(r)
			nb, err := d.Auth.Rotate(tok)
			if err != nil {
				writeError(w, http.StatusUnauthorized, "E_ROTATE", err.Error())
				return
			}
			if nb == nil {
				writeJSON(w, http.StatusOK, map[string]any{
					"rotated": false,
					"message": "current bearer still has plenty of time",
				})
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{
				"rotated":    true,
				"bearer":     nb.Token,
				"expires_at": nb.ExpiresAt.Format(time.RFC3339),
			})
		},
	)))

	mux.Handle("POST /v1/auth/revoke", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			tok := extractBearer(r)
			if err := d.Auth.Revoke(tok); err != nil {
				writeError(w, http.StatusInternalServerError, "E_REVOKE", err.Error())
				return
			}
			w.WriteHeader(http.StatusNoContent)
		},
	)))

	// --- xray ---
	mux.Handle("POST /v1/xray/deploy", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.DeployXrayRequest
			if !decodeJSON(w, r, &req) {
				return
			}
			id, err := d.Tasks.Spawn("xray.deploy", deploy.XrayDeploy(d.DB, &req))
			if err != nil {
				writeError(w, http.StatusInternalServerError, "E_SPAWN", err.Error())
				return
			}
			writeJSON(w, http.StatusAccepted, map[string]any{
				"task_id": id,
				"status":  tasks.StatusPending,
			})
		},
	)))

	mux.Handle("POST /v1/xray/profile", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.XrayAddProfileRequest
			if !decodeJSON(w, r, &req) {
				return
			}
			res, err := deploy.XrayAddProfile(d.DB, &req)
			if err != nil {
				writeError(w, http.StatusBadRequest, "E_XRAY_ADD_PROFILE", err.Error())
				return
			}
			writeJSON(w, http.StatusOK, res)
		},
	)))

	mux.Handle("DELETE /v1/xray/profile/{id}", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			id := r.PathValue("id")
			if err := deploy.XrayDeleteProfile(d.DB, id); err != nil {
				writeError(w, http.StatusBadRequest, "E_XRAY_DEL_PROFILE", err.Error())
				return
			}
			w.WriteHeader(http.StatusNoContent)
		},
	)))

	mux.Handle("POST /v1/xray/uninstall", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			id, err := d.Tasks.Spawn("xray.uninstall", deploy.XrayUninstall(d.DB))
			if err != nil {
				writeError(w, http.StatusInternalServerError, "E_SPAWN", err.Error())
				return
			}
			writeJSON(w, http.StatusAccepted, map[string]any{
				"task_id": id,
				"status":  tasks.StatusPending,
			})
		},
	)))

	mux.Handle("GET /v1/xray/inbounds", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			list, err := d.DB.ListXrayInbounds()
			if err != nil {
				writeError(w, http.StatusInternalServerError, "E_LIST_INBOUNDS", err.Error())
				return
			}
			// Shape for the client — hide config blob, keep what the UI shows.
			type row struct {
				InboundID  string    `json:"inbound_id"`
				Protocol   string    `json:"protocol"`
				Port       int       `json:"port"`
				ProfileURI string    `json:"profile_uri"`
				CreatedAt  time.Time `json:"created_at"`
			}
			out := make([]row, 0, len(list))
			for _, x := range list {
				out = append(out, row{x.ID, x.Protocol, x.Port, x.ProfileURI, x.CreatedAt})
			}
			writeJSON(w, http.StatusOK, map[string]any{"inbounds": out})
		},
	)))

	// --- tasks ---
	mux.Handle("GET /v1/tasks/{id}", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			id := r.PathValue("id")
			t, err := d.Tasks.Get(id)
			if err != nil {
				writeError(w, http.StatusNotFound, "E_TASK_NOT_FOUND", err.Error())
				return
			}
			writeJSON(w, http.StatusOK, t)
		},
	)))

	return logMiddleware(mux)
}

// authenticated wraps h to require a valid bearer.
//
// Side-effect: if the bearer is within RotateWindow of expiry we add
// X-Bearer-Rotate-Available: true so the client knows to call rotate.
func authenticated(d *Deps, h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := extractBearer(r)
		b, err := d.Auth.Verify(token)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "E_UNAUTHORIZED", err.Error())
			return
		}
		if time.Until(b.ExpiresAt) < auth.RotateWindow {
			w.Header().Set("X-Bearer-Rotate-Available", "true")
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
