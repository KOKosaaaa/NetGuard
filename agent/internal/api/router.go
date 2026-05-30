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
	"strconv"
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

	mux.Handle("POST /v1/xray/refresh-geo-dat", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			id, err := d.Tasks.Spawn("xray.refresh_geo_dat", deploy.XrayRefreshGeoDat())
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

	// Pre-download release archives into the agent's cache so the
	// first real /xray/deploy (etc.) finishes in seconds. Invoked from
	// the install.sh script the Android bootstrap leaves behind, but
	// also exposed as a regular endpoint so any client can kick it
	// off manually (e.g. after an agent upgrade bumped a pinned
	// version).
	mux.Handle("POST /v1/agent/warmup", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			id, err := d.Tasks.Spawn("agent.warmup", deploy.Warmup())
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
			// chain_to_* are non-empty when this inbound forwards into a
			// next-hop VLESS outbound (multi-hop chain). UI uses these
			// to render "🇫🇮 → 🇩🇪 → 🇺🇸" badges.
			type row struct {
				InboundID  string    `json:"inbound_id"`
				Protocol   string    `json:"protocol"`
				Port       int       `json:"port"`
				ProfileURI string    `json:"profile_uri"`
				CreatedAt  time.Time `json:"created_at"`
				ChainToTag string    `json:"chain_to_tag,omitempty"`
				ChainToURI string    `json:"chain_to_uri,omitempty"`
			}
			out := make([]row, 0, len(list))
			for _, x := range list {
				out = append(out, row{
					InboundID:  x.ID,
					Protocol:   x.Protocol,
					Port:       x.Port,
					ProfileURI: x.ProfileURI,
					CreatedAt:  x.CreatedAt,
					ChainToTag: x.ChainToTag,
					ChainToURI: x.ChainToURI,
				})
			}
			writeJSON(w, http.StatusOK, map[string]any{"inbounds": out})
		},
	)))

	// --- bypass rules ---
	mux.Handle("GET /v1/bypass/rules", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			rules, err := d.DB.ListBypassRules()
			if err != nil {
				writeError(w, http.StatusInternalServerError, "E_LIST_RULES", err.Error())
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{"rules": rules})
		},
	)))

	mux.Handle("POST /v1/bypass/rules", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.AddBypassRuleRequest
			if !decodeJSON(w, r, &req) {
				return
			}
			rule, err := deploy.AddBypassRule(d.DB, &req)
			if err != nil {
				writeError(w, http.StatusBadRequest, "E_ADD_RULE", err.Error())
				return
			}
			writeJSON(w, http.StatusCreated, rule)
		},
	)))

	mux.Handle("PUT /v1/bypass/rules", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.PutBypassRulesRequest
			if !decodeJSON(w, r, &req) {
				return
			}
			rules, err := deploy.ReplaceBypassRules(d.DB, req.Rules)
			if err != nil {
				writeError(w, http.StatusBadRequest, "E_PUT_RULES", err.Error())
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{"rules": rules})
		},
	)))

	mux.Handle("DELETE /v1/bypass/rules/{id}", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			id := r.PathValue("id")
			if err := deploy.DeleteBypassRule(d.DB, id); err != nil {
				writeError(w, http.StatusNotFound, "E_DEL_RULE", err.Error())
				return
			}
			w.WriteHeader(http.StatusNoContent)
		},
	)))

	// --- bypass outbounds (user-defined upstream proxies) ---
	mux.Handle("GET /v1/bypass/outbounds", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			outs, err := d.DB.ListBypassOutbounds()
			if err != nil {
				writeError(w, http.StatusInternalServerError, "E_LIST_OUTBOUNDS", err.Error())
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{"outbounds": outs})
		},
	)))

	mux.Handle("POST /v1/bypass/outbounds", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.AddBypassOutboundRequest
			if !decodeJSON(w, r, &req) {
				return
			}
			o, err := deploy.AddBypassOutbound(d.DB, &req)
			if err != nil {
				writeError(w, http.StatusBadRequest, "E_ADD_OUTBOUND", err.Error())
				return
			}
			writeJSON(w, http.StatusCreated, o)
		},
	)))

	mux.Handle("DELETE /v1/bypass/outbounds/{id}", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			id := r.PathValue("id")
			if err := deploy.DeleteBypassOutbound(d.DB, id); err != nil {
				writeError(w, http.StatusNotFound, "E_DEL_OUTBOUND", err.Error())
				return
			}
			w.WriteHeader(http.StatusNoContent)
		},
	)))

	// --- services (restart/start/stop/logs/status of whitelisted units) ---
	mux.Handle("POST /v1/services/{name}/{action}", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			name := r.PathValue("name")
			action := r.PathValue("action")
			out, err := deploy.ServiceAction(r.Context(), name, action)
			if err != nil {
				code := "E_SERVICE"
				switch {
				case errors.Is(err, deploy.ErrServiceUnknown):
					code = "E_SERVICE_UNKNOWN"
					writeError(w, http.StatusNotFound, code, err.Error())
					return
				case errors.Is(err, deploy.ErrServiceFailed):
					code = "E_SERVICE_FAILED"
				}
				writeError(w, http.StatusBadRequest, code, err.Error())
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{
				"service": name, "action": action,
				"output": string(out),
			})
		},
	)))

	mux.Handle("GET /v1/services/{name}/logs", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			name := r.PathValue("name")
			lines := 200
			if v := r.URL.Query().Get("lines"); v != "" {
				if n, err := strconv.Atoi(v); err == nil {
					lines = n
				}
			}
			out, err := deploy.ServiceLogs(r.Context(), name, lines)
			if err != nil {
				if errors.Is(err, deploy.ErrServiceUnknown) {
					writeError(w, http.StatusNotFound, "E_SERVICE_UNKNOWN", err.Error())
					return
				}
				writeError(w, http.StatusInternalServerError, "E_LOGS", err.Error())
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{
				"service": name, "lines": lines,
				"log": string(out),
			})
		},
	)))

	mux.Handle("GET /v1/services/{name}/status", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			name := r.PathValue("name")
			st, err := deploy.ServiceStatus(r.Context(), name)
			if err != nil {
				if errors.Is(err, deploy.ErrServiceUnknown) {
					writeError(w, http.StatusNotFound, "E_SERVICE_UNKNOWN", err.Error())
					return
				}
				writeError(w, http.StatusInternalServerError, "E_STATUS", err.Error())
				return
			}
			writeJSON(w, http.StatusOK, st)
		},
	)))

	// --- telemost ---
	mux.Handle("GET /v1/telemost/health", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			writeJSON(w, http.StatusOK, map[string]any{
				"available": true,
				"note":      "Telemost deploy live; POST /v1/telemost/deploy {count, cookies_json}",
			})
		},
	)))
	mux.Handle("POST /v1/telemost/deploy", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.DeployTelemostRequest
			if !decodeJSON(w, r, &req) {
				return
			}
			id, err := d.Tasks.Spawn("telemost.deploy", deploy.DeployTelemost(&req))
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
	// GET rooms — sync read of provisioned rooms + live systemd state.
	mux.Handle("GET /v1/telemost/rooms", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			res, err := deploy.TelemostRooms(r.Context())
			if err != nil {
				writeError(w, http.StatusInternalServerError, "E_TELEMOST_ROOMS", err.Error())
				return
			}
			writeJSON(w, http.StatusOK, res)
		},
	)))

	// POST scale — async; change number of running instances (0..12).
	mux.Handle("POST /v1/telemost/scale", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.ScaleTelemostRequest
			if !decodeJSON(w, r, &req) {
				return
			}
			id, err := d.Tasks.Spawn("telemost.scale", deploy.ScaleTelemost(&req))
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

	// POST cookies — async; refresh the Yandex session + restart instances.
	mux.Handle("POST /v1/telemost/cookies", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.UpdateCookiesRequest
			if !decodeJSON(w, r, &req) {
				return
			}
			id, err := d.Tasks.Spawn("telemost.cookies", deploy.UpdateTelemostCookies(&req))
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

	// --- agent self-update ---
	mux.Handle("POST /v1/agent/update", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.UpdateAgentRequest
			if !decodeJSON(w, r, &req) {
				return
			}
			id, err := d.Tasks.Spawn("agent.update", deploy.AgentUpdate(d.DB, &req))
			if err != nil {
				writeError(w, http.StatusInternalServerError, "E_SPAWN", err.Error())
				return
			}
			writeJSON(w, http.StatusAccepted, map[string]any{
				"task_id": id,
				"status":  tasks.StatusPending,
				"note":    "agent will exit briefly when update completes; poll /v1/health to confirm reboot",
			})
		},
	)))

	// --- swap setup (low-RAM VPS helper for Telemost) ---
	mux.Handle("POST /v1/agent/swap-setup", authenticated(d, http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			var req deploy.SwapSetupRequest
			// Body is optional (size_mb defaults to 512); tolerate an empty POST.
			if r.ContentLength != 0 {
				if !decodeJSON(w, r, &req) {
					return
				}
			}
			id, err := d.Tasks.Spawn("agent.swap_setup", deploy.SwapSetup(&req))
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
