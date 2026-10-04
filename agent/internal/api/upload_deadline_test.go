package api

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/auth"
	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
)

// A local DB and synthetic bearer only. Never invokes pairing, deployment, systemctl or self-install.
func uploadQAAuth(t *testing.T) (*Deps, string) {
	t.Helper()
	db, err := storage.Open(filepath.Join(t.TempDir(), "auth.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	token := "local-upload-deadline-test-only"
	now := time.Now()
	if err := db.InsertBearer(&storage.Bearer{Token: token, DeviceName: "fixture", AppVersion: "fixture", CreatedAt: now, ExpiresAt: now.Add(time.Hour), LastSeenAt: now}); err != nil {
		t.Fatal(err)
	}
	return &Deps{Auth: auth.New(db), DB: db}, token
}

type uploadQARead struct {
	count   int
	err     error
	elapsed time.Duration
	hash    [32]byte
}

func TestUploadDeadlineSlowBodyThroughAuthenticatedLoggingWrapper(t *testing.T) {
	for _, tc := range []struct {
		name   string
		extend time.Duration
		wantOK bool
	}{
		{"old_global_deadline_reproduces_failure", 0, false},
		{"authenticated_extension_allows_complete_upload", 2 * time.Second, true},
		{"extension_still_has_finite_deadline", 300 * time.Millisecond, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			d, token := uploadQAAuth(t)
			result := make(chan uploadQARead, 1)
			entered := make(chan struct{})
			h := authenticated(d, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				start := time.Now()
				if tc.extend > 0 {
					if err := setUploadReadDeadline(w, tc.extend); err != nil {
						result <- uploadQARead{err: err}
						close(entered)
						http.Error(w, "deadline helper failed", 500)
						return
					}
				}
				close(entered)
				body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 4096))
				result <- uploadQARead{len(body), err, time.Since(start), sha256.Sum256(body)}
				if err != nil {
					http.Error(w, "incomplete upload", 400)
					return
				}
				w.WriteHeader(200)
			}))
			s := httptest.NewUnstartedServer(logMiddleware(h))
			s.Config.ReadHeaderTimeout = time.Second
			s.Config.ReadTimeout = 150 * time.Millisecond
			s.Config.WriteTimeout = 3 * time.Second
			s.Start()
			defer s.Close()
			conn, err := net.DialTimeout("tcp", s.Listener.Addr().String(), time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
			payload := bytes.Repeat([]byte("verified-payload-"), 64)
			_, err = fmt.Fprintf(conn, "POST /v1/agent/update-upload HTTP/1.1\r\nHost: local\r\nAuthorization: Bearer %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", token, len(payload))
			if err != nil {
				t.Fatal(err)
			}
			select {
			case <-entered:
			case <-time.After(2 * time.Second):
				t.Fatal("handler never started")
			}
			if _, err = conn.Write(payload[:1]); err != nil {
				t.Fatal(err)
			}
			// Longer than the inherited read budget, shorter than the valid upload budget.
			time.Sleep(500 * time.Millisecond)
			_, writeErr := conn.Write(payload[1:])
			if tc.wantOK && writeErr != nil {
				t.Fatal(writeErr)
			}
			resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			_, _ = io.Copy(io.Discard, resp.Body)
			var got uploadQARead
			select {
			case got = <-result:
			case <-time.After(time.Second):
				t.Fatal("body read never finished")
			}
			if tc.wantOK {
				if got.err != nil || resp.StatusCode != 200 || got.count != len(payload) || got.hash != sha256.Sum256(payload) {
					t.Fatalf("complete upload not preserved: status=%d bytes=%d error=%v", resp.StatusCode, got.count, got.err)
				}
			} else {
				if got.err == nil || resp.StatusCode != 400 || got.count >= len(payload) {
					t.Fatalf("negative control did not timeout: status=%d bytes=%d err=%v", resp.StatusCode, got.count, got.err)
				}
				if got.elapsed > time.Second {
					t.Fatalf("body deadline not bounded: %s", got.elapsed)
				}
			}
		})
	}
}

type uploadQADeadlineWriter struct {
	*httptest.ResponseRecorder
	deadlines []time.Time
}

func (w *uploadQADeadlineWriter) SetReadDeadline(deadline time.Time) error {
	w.deadlines = append(w.deadlines, deadline)
	return nil
}

func TestUploadDeadlineMountedRouteRequiresAuthAndWrapperUnwrap(t *testing.T) {
	d, token := uploadQAAuth(t)
	for _, tc := range []struct {
		name, path, bearer string
		status, calls      int
	}{
		{"unauthorized", "/v1/agent/update-upload?sha256=invalid", "", 401, 0},
		{"authenticated_upload", "/v1/agent/update-upload?sha256=invalid", token, 400, 1},
		{"unrelated_route", "/v1/health", token, 200, 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			method := "POST"
			if tc.name == "unrelated_route" {
				method = "GET"
			}
			req := httptest.NewRequest(method, tc.path, strings.NewReader("not-an-executable"))
			if tc.bearer != "" {
				req.Header.Set("Authorization", "Bearer "+tc.bearer)
			}
			w := &uploadQADeadlineWriter{ResponseRecorder: httptest.NewRecorder()}
			before := time.Now()
			// Invalid hash deliberately fails before Install creates a file, reads body, or swaps anything.
			Mount(d).ServeHTTP(w, req)
			if w.Code != tc.status || len(w.deadlines) != tc.calls {
				t.Fatalf("status=%d deadline calls=%d", w.Code, len(w.deadlines))
			}
			if tc.calls > 0 {
				remaining := w.deadlines[0].Sub(before)
				if remaining < 4*time.Minute || remaining > 4*time.Minute+time.Second {
					t.Fatalf("unexpected upload budget %s", remaining)
				}
			}
		})
	}
	recorder := httptest.NewRecorder()
	wrapper := &statusWriter{ResponseWriter: recorder, status: 200}
	if wrapper.Unwrap() != recorder {
		t.Fatal("logging wrapper did not expose original writer")
	}
	if err := setUploadReadDeadline(wrapper, time.Second); err == nil {
		t.Fatal("unsupported deadline silently succeeded")
	}
}

func TestUploadDeadlineDoesNotLeakToNextKeepAliveRequest(t *testing.T) {
	d, token := uploadQAAuth(t)
	var requests atomic.Int32
	h := authenticated(d, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := requests.Add(1)
		if n == 1 {
			if err := setUploadReadDeadline(w, 2*time.Second); err != nil {
				http.Error(w, "deadline", 500)
				return
			}
		}
		_, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "timed out", 400)
			return
		}
		w.Header().Set("Content-Length", "0")
		w.WriteHeader(200)
	}))
	s := httptest.NewUnstartedServer(logMiddleware(h))
	s.Config.ReadTimeout = 150 * time.Millisecond
	s.Config.WriteTimeout = 3 * time.Second
	s.Start()
	defer s.Close()
	conn, err := net.DialTimeout("tcp", s.Listener.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	reader := bufio.NewReader(conn)
	for n := 0; n < 2; n++ {
		_, err = fmt.Fprintf(conn, "POST / HTTP/1.1\r\nHost: local\r\nAuthorization: Bearer %s\r\nContent-Length: 2\r\n\r\na", token)
		if err != nil {
			t.Fatal(err)
		}
		if n == 0 {
			time.Sleep(400 * time.Millisecond)
			if _, err = conn.Write([]byte("b")); err != nil {
				t.Fatal(err)
			}
		}
		resp, err := http.ReadResponse(reader, nil)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = io.Copy(io.Discard, resp.Body)
		_ = resp.Body.Close()
		want := 200
		if n == 1 {
			want = 400
		}
		if resp.StatusCode != want {
			t.Fatalf("request %d: got%d want%d", n, resp.StatusCode, want)
		}
	}
}
