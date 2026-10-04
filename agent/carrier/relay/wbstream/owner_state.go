package wbstream

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// OwnerState keeps the existing phone login in the private server state file.
// Tokens never appear in process arguments or diagnostic output.
type OwnerState struct {
	path   string
	state  map[string]json.RawMessage
	device string
	client *http.Client
}

type stateCookie struct {
	Name     string  `json:"name"`
	Value    string  `json:"value"`
	Domain   string  `json:"domain"`
	Path     string  `json:"path"`
	Expires  float64 `json:"expires"`
	HTTPOnly bool    `json:"httpOnly"`
	Secure   bool    `json:"secure"`
	SameSite string  `json:"sameSite"`
}
type stateStorage struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}
type stateOrigin struct {
	Origin       string         `json:"origin"`
	LocalStorage []stateStorage `json:"localStorage"`
}

func LoadOwnerState(path string) (*OwnerState, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("owner state unavailable")
	}
	s := &OwnerState{path: path}
	if json.Unmarshal(raw, &s.state) != nil {
		return nil, fmt.Errorf("owner state invalid")
	}
	var origins []stateOrigin
	var cookies []stateCookie
	if json.Unmarshal(s.state["origins"], &origins) != nil || json.Unmarshal(s.state["cookies"], &cookies) != nil {
		return nil, fmt.Errorf("owner state invalid")
	}
	for _, origin := range origins {
		if origin.Origin == Origin {
			for _, item := range origin.LocalStorage {
				if item.Name == "wb_auth_api_device_id" {
					s.device = item.Value
				}
			}
		}
	}
	if s.device == "" {
		return nil, fmt.Errorf("owner device missing")
	}
	jar, _ := cookiejar.New(nil)
	for _, cookie := range cookies {
		domain := strings.TrimPrefix(cookie.Domain, ".")
		if domain != "wb.ru" && domain != "stream.wb.ru" && domain != "auth-stream.wb.ru" {
			continue
		}
		if !ownerCookieAllowed(cookie.Name) {
			continue
		}
		endpoint, _ := url.Parse("https://" + domain + "/")
		value := &http.Cookie{Name: cookie.Name, Value: cookie.Value, Domain: cookie.Domain, Path: cookie.Path, Secure: true, HttpOnly: true}
		if cookie.Expires > 0 {
			value.Expires = time.Unix(int64(cookie.Expires), 0)
		}
		jar.SetCookies(endpoint, []*http.Cookie{value})
	}
	s.client = &http.Client{Jar: jar, Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	return s, nil
}

func ownerCookieAllowed(name string) bool {
	return name == "wbx-refresh" || name == "_wbauid" || name == "wbx-validation-key"
}

func (s *OwnerState) Refresh() (string, error) {
	token, err := RefreshAccessToken(s.client, "", s.device)
	// A response can rotate a refresh cookie even if its body is invalid. Persist
	// immediately so a restart never reuses the old refresh cookie.
	if saveErr := s.save(token); saveErr != nil {
		return "", saveErr
	}
	return token, err
}

func (s *OwnerState) Join(token, room, name string) (string, string, string, string, error) {
	if ParseRoomID(room) == "" {
		return "", "", "", "", fmt.Errorf("existing room required")
	}
	return AuthAsLoggedIn(s.client, "", token, room, name)
}

func (s *OwnerState) save(token string) error {
	endpoint, _ := url.Parse("https://auth-stream.wb.ru/v2/auth/slide-v3")
	var cookies []stateCookie
	refresh := false
	seen := map[string]bool{}
	for _, cookie := range s.client.Jar.Cookies(endpoint) {
		if !ownerCookieAllowed(cookie.Name) || seen[cookie.Name] {
			continue
		}
		seen[cookie.Name] = true
		if cookie.Name == "wbx-refresh" && cookie.Value != "" {
			refresh = true
		}
		cookies = append(cookies, stateCookie{Name: cookie.Name, Value: cookie.Value, Domain: ".wb.ru", Path: "/", Expires: -1, HTTPOnly: true, Secure: true, SameSite: "None"})
	}
	if !refresh {
		return fmt.Errorf("owner login needs renewal")
	}
	s.state["cookies"], _ = json.Marshal(cookies)
	if token != "" {
		var origins []stateOrigin
		if json.Unmarshal(s.state["origins"], &origins) != nil {
			return fmt.Errorf("owner storage invalid")
		}
		for i := range origins {
			if origins[i].Origin == Origin {
				for j := range origins[i].LocalStorage {
					item := &origins[i].LocalStorage[j]
					if item.Name == "wb_auth_auth_slice" {
						var slice map[string]any
						if json.Unmarshal([]byte(item.Value), &slice) != nil || slice == nil {
							slice = map[string]any{}
						}
						slice["accessToken"] = token
						b, _ := json.Marshal(slice)
						item.Value = string(b)
					}
				}
			}
		}
		s.state["origins"], _ = json.Marshal(origins)
	}
	raw, _ := json.Marshal(s.state)
	f, err := os.CreateTemp(filepath.Dir(s.path), ".owner-session-")
	if err != nil {
		return fmt.Errorf("cannot persist owner login")
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	_, err = f.Write(raw)
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(tmp, s.path)
	}
	if err != nil {
		return fmt.Errorf("cannot persist owner login")
	}
	// Replace older root-path duplicates with the selected (most specific)
	// rotated cookie, so the next refresh cannot submit an obsolete token too.
	jar, _ := cookiejar.New(nil)
	for _, cookie := range cookies {
		jar.SetCookies(endpoint, []*http.Cookie{{Name: cookie.Name, Value: cookie.Value, Domain: cookie.Domain, Path: cookie.Path, Secure: true, HttpOnly: true}})
	}
	s.client.Jar = jar
	return nil
}
