// tmturncreds - добывает TURN-креды Telemost (Яндекс) для turnprobe.
// Повторяет рабочий путь creator'а: fetchConfig (appVersion со страницы) +
// getConnection (REST). НЕ открывает websocket -> реальным участником комнаты
// не появляется (зарезервированный peer отваливается по таймауту).
//
// anon (без кук, существующая постоянная комната):
//   tmturncreds -tm-link https://telemost.yandex.ru/j/XXXX
// cookie (своя одноразовая комната):
//   tmturncreds -cookies /path/cookies-yandex.json
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"net/url"
	"regexp"
	"strings"

	"whitelist-bypass/relay/common"
	tmapi "whitelist-bypass/relay/telemost"
)

func fetchAppVersion() string {
	page, err := common.HttpGet("https://telemost.yandex.ru/")
	if err != nil {
		fmt.Println("fetch page err:", err)
		return ""
	}
	re := regexp.MustCompile(`<script[^>]*id="preloaded-state"[^>]*>([\s\S]*?)</script>`)
	m := re.FindSubmatch(page)
	if m == nil {
		return ""
	}
	var state struct {
		Config struct {
			AppVersion string `json:"appVersion"`
		} `json:"config"`
		AppVersion string `json:"appVersion"`
	}
	json.Unmarshal(m[1], &state)
	if state.Config.AppVersion != "" {
		return state.Config.AppVersion
	}
	return state.AppVersion
}

func main() {
	cookieStr := flag.String("cookie-string", "", "raw cookie string")
	cookiesPath := flag.String("cookies", "", "path to cookies-yandex.json")
	tmLink := flag.String("tm-link", "", "existing conference URL (anon mode)")
	name := flag.String("name", "Гоша", "display name")
	flag.Parse()

	cookie := *cookieStr
	if cookie == "" && *cookiesPath != "" {
		cookie = common.LoadCookies(*cookiesPath)
	}

	appVer := fetchAppVersion()
	fmt.Println("appVersion:", appVer)
	c := tmapi.Client{Cookie: cookie, AppVersion: appVer}

	var confURI string
	if *tmLink != "" {
		confURI = strings.TrimSpace(*tmLink) // полный URL, как делает joinExistingConference
	} else {
		r, status, err := c.Do("POST", "/conferences?next_gen_media_platform_allowed=true", struct{}{})
		if err != nil || (status != 200 && status != 201) {
			fmt.Printf("create conf FAILED: status=%d err=%v body=%s\n", status, err, string(r))
			return
		}
		var conf struct {
			URI string `json:"uri"`
		}
		json.Unmarshal(r, &conf)
		confURI = conf.URI
		fmt.Println("created conf:", confURI)
	}

	path := "/conferences/" + url.QueryEscape(confURI) +
		"/connection?next_gen_media_platform_allowed=true&display_name=" +
		url.QueryEscape(*name) + "&waiting_room_supported=true"
	r, status, err := c.Do("GET", path, nil)
	if err != nil || status != 200 {
		fmt.Printf("getConnection FAILED: status=%d err=%v body=%s\n", status, err, string(r))
		return
	}
	var conn struct {
		PeerID       string `json:"peer_id"`
		ClientConfig struct {
			MediaServerURL string          `json:"media_server_url"`
			ICEServers     json.RawMessage `json:"ice_servers"`
		} `json:"client_configuration"`
	}
	json.Unmarshal(r, &conn)
	fmt.Println("peer_id:", conn.PeerID)
	fmt.Println("media_server:", conn.ClientConfig.MediaServerURL)
	fmt.Println("=== ICE SERVERS (raw) ===")
	fmt.Println(string(conn.ClientConfig.ICEServers))

	// удобный вывод turn-строк для turnprobe
	var ice []struct {
		URLs       []string `json:"urls"`
		Username   string   `json:"username"`
		Credential string   `json:"credential"`
	}
	json.Unmarshal(conn.ClientConfig.ICEServers, &ice)
	fmt.Println("=== TURN candidates for turnprobe ===")
	for _, s := range ice {
		if s.Username == "" {
			continue
		}
		for _, u := range s.URLs {
			if strings.HasPrefix(u, "turn:") {
				fmt.Printf("turnprobe.exe -turn %q -user %q -pass %q -peer 62.60.159.174:39999\n", u, s.Username, s.Credential)
			}
		}
	}
}
