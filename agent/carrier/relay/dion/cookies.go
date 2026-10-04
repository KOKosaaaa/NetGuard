package dion

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"strings"
)

type CookieEntry struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

func (s *Session) LoadCookiesFromFile(path string) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read cookies: %w", err)
	}
	var entries []CookieEntry
	if err := json.Unmarshal(raw, &entries); err != nil {
		return fmt.Errorf("parse cookies: %w", err)
	}
	if err := s.LoadCookies(entries); err != nil {
		return err
	}
	s.cookiesPath = path
	s.seedAccessTokenFromCookies(entries)
	return nil
}

func (s *Session) seedAccessTokenFromCookies(entries []CookieEntry) {
	for _, entry := range entries {
		if entry.Name != "vc-access-token" || entry.Value == "" {
			continue
		}
		exp, err := parseJWTExpiry(entry.Value)
		if err != nil {
			return
		}
		s.AccessToken = entry.Value
		s.AccessTokenExp = exp
		return
	}
}

func (s *Session) SaveCookiesToFile(path string) error {
	if s.HTTPClient == nil || s.HTTPClient.Jar == nil {
		return fmt.Errorf("no cookiejar")
	}
	seen := make(map[string]string)
	for _, target := range []string{WebBase, APIBase, APIClientsBase} {
		parsed, err := url.Parse(target)
		if err != nil {
			continue
		}
		for _, c := range s.HTTPClient.Jar.Cookies(parsed) {
			if _, exists := seen[c.Name]; exists {
				continue
			}
			seen[c.Name] = c.Value
		}
	}
	if existing, err := os.ReadFile(path); err == nil {
		var prev []CookieEntry
		if json.Unmarshal(existing, &prev) == nil {
			for _, entry := range prev {
				if _, exists := seen[entry.Name]; !exists {
					seen[entry.Name] = entry.Value
				}
			}
		}
	}
	entries := make([]CookieEntry, 0, len(seen))
	for name, value := range seen {
		entries = append(entries, CookieEntry{Name: name, Value: value})
	}
	raw, err := json.MarshalIndent(entries, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal cookies: %w", err)
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return fmt.Errorf("write cookies tmp: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return fmt.Errorf("rename cookies tmp: %w", err)
	}
	return nil
}

func (s *Session) SetCookieInJar(name, value string) {
	if s.HTTPClient == nil || s.HTTPClient.Jar == nil {
		return
	}
	cookie := &http.Cookie{Name: name, Value: value, Path: "/"}
	for _, target := range []string{WebBase, APIBase, APIClientsBase} {
		parsed, err := url.Parse(target)
		if err != nil {
			continue
		}
		s.HTTPClient.Jar.SetCookies(parsed, []*http.Cookie{cookie})
	}
}

func (s *Session) LoadCookieString(cookieStr string) error {
	cookieStr = strings.TrimSpace(cookieStr)
	if cookieStr == "" {
		return fmt.Errorf("empty cookie string")
	}
	var entries []CookieEntry
	for _, piece := range strings.Split(cookieStr, ";") {
		piece = strings.TrimSpace(piece)
		if piece == "" {
			continue
		}
		eq := strings.IndexByte(piece, '=')
		if eq <= 0 {
			continue
		}
		entries = append(entries, CookieEntry{Name: piece[:eq], Value: piece[eq+1:]})
	}
	return s.LoadCookies(entries)
}

func (s *Session) LoadCookies(entries []CookieEntry) error {
	if s.HTTPClient.Jar == nil {
		jar, err := cookiejar.New(nil)
		if err != nil {
			return fmt.Errorf("cookiejar: %w", err)
		}
		s.HTTPClient.Jar = jar
	}
	targets := []string{WebBase, APIBase, APIClientsBase}
	for _, target := range targets {
		u, err := url.Parse(target)
		if err != nil {
			return fmt.Errorf("parse target %s: %w", target, err)
		}
		cookies := make([]*http.Cookie, 0, len(entries))
		for _, entry := range entries {
			if entry.Name == "" {
				continue
			}
			cookies = append(cookies, &http.Cookie{
				Name:  entry.Name,
				Value: entry.Value,
				Path:  "/",
			})
		}
		s.HTTPClient.Jar.SetCookies(u, cookies)
	}
	return nil
}

func (s *Session) PrimeCookies(slug string) error {
	target := WebBase + "/"
	if slug != "" {
		target = fmt.Sprintf("%s/event/%s?showWeb=true", WebBase, slug)
	}
	req, err := http.NewRequest(http.MethodGet, target, nil)
	if err != nil {
		return err
	}
	s.setBaseHeaders(req, "")
	resp, err := s.HTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("prime cookies: %w", err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)
	if resp.StatusCode >= 400 {
		return fmt.Errorf("prime cookies: status %d", resp.StatusCode)
	}
	return nil
}
