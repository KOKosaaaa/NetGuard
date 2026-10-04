package wbstream

import (
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type ownerTransport func(*http.Request) (*http.Response, error)

func (f ownerTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestOwnerRefreshPersistsRotationBeforeRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	fixture := `{"cookies":[{"name":"wbx-refresh","value":"old","domain":".wb.ru","path":"/","expires":-1},{"name":"x_wbaas_token","value":"phone-ip","domain":".wb.ru","path":"/"}],"origins":[{"origin":"https://stream.wb.ru","localStorage":[{"name":"wb_auth_api_device_id","value":"device"},{"name":"wb_auth_auth_slice","value":"{\"accessToken\":\"old-access\"}"}]}]}`
	if err := os.WriteFile(path, []byte(fixture), 0600); err != nil {
		t.Fatal(err)
	}
	owner, err := LoadOwnerState(path)
	if err != nil {
		t.Fatal(err)
	}
	calls := 0
	owner.client.Transport = ownerTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		want := "old"
		if calls > 1 {
			want = "rotated"
		}
		cookies := r.Cookies()
		if len(cookies) != 1 || cookies[0].Value != want {
			t.Fatalf("wrong refresh cookie count/value at attempt %d", calls)
		}
		if r.Header.Get("deviceId") != "device" {
			t.Fatal("device missing")
		}
		return &http.Response{StatusCode: 200, Header: http.Header{"Set-Cookie": []string{"wbx-refresh=rotated; Domain=.wb.ru; Path=/v2/auth; Secure; HttpOnly"}}, Body: io.NopCloser(strings.NewReader(`{"payload":{"access_token":"new-access"}}`)), Request: r}, nil
	})
	for i := 0; i < 2; i++ {
		token, err := owner.Refresh()
		if err != nil || token != "new-access" {
			t.Fatalf("refresh failed: %v", err)
		}
	}
	reloaded, err := LoadOwnerState(path)
	if err != nil {
		t.Fatal(err)
	}
	reloaded.client.Transport = owner.client.Transport
	if _, err = reloaded.Refresh(); err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(path)
	if strings.Contains(string(raw), "phone-ip") || strings.Contains(string(raw), "old-access") {
		t.Fatal("obsolete state retained")
	}
}

func TestOwnerMissingStateErrorsDoNotExposeTokens(t *testing.T) {
	if _, err := LoadOwnerState(filepath.Join(t.TempDir(), "absent")); err == nil {
		t.Fatal("missing state accepted")
	}
}
