package joiner

import (
	"crypto/x509"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync/atomic"
	"testing"
)

func TestSignalingTLSQARejectsUntrustedBeforeSendingCredentials(t *testing.T) {
	var requests atomic.Int32
	s := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests.Add(1) }))
	defer s.Close()
	j := &TelemostHeadlessJoiner{ResolveFn: func(string) (string, error) { return "127.0.0.1", nil }}
	client := j.makeHTTPClient()
	defer client.CloseIdleConnections()
	request, _ := http.NewRequest("POST", s.URL, nil)
	request.Header.Set("Authorization", "Bearer synthetic-test-only")
	if response, err := client.Do(request); err == nil {
		response.Body.Close()
		t.Fatal("untrusted peer received a signaling request")
	}
	if requests.Load() != 0 {
		t.Fatal("credentials crossed an unauthenticated TLS connection")
	}
}

func TestSignalingTLSQACustomResolverRetainsHostVerification(t *testing.T) {
	s := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "verified") }))
	defer s.Close()
	roots := x509.NewCertPool()
	roots.AddCert(s.Certificate())
	for _, tc := range []struct {
		hostname string
		ok       bool
	}{{"example.com", true}, {"wrong.invalid", false}} {
		t.Run(tc.hostname, func(t *testing.T) {
			j := &TelemostHeadlessJoiner{ResolveFn: func(string) (string, error) { return "127.0.0.1", nil }}
			client := j.makeHTTPClient()
			defer client.CloseIdleConnections()
			transport := client.Transport.(*http.Transport)
			transport.TLSClientConfig.RootCAs = roots // test CA only; production system roots remain intact
			u, _ := url.Parse(s.URL)
			u.Host = tc.hostname + ":" + u.Port()
			r, err := client.Get(u.String())
			if err != nil {
				if tc.ok {
					t.Fatal(err)
				}
				return
			}
			defer r.Body.Close()
			if !tc.ok {
				t.Fatal("wrong hostname accepted despite valid CA")
			}
			body, err := io.ReadAll(r.Body)
			if err != nil || string(body) != "verified" {
				t.Fatalf("body=%q err=%v", body, err)
			}
		})
	}
}
