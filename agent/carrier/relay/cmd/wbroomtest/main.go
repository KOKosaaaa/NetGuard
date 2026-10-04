// wbroomtest probes whether a given WB cookie jar can do the logged-in
// slide-v3 -> CreateRoom flow (auto-create rooms). It does a RAW slide-v3 call so
// headers (User-Agent, etc.) can be tuned to match the browser that minted the
// antibot x_wbaas_token (which is bound to IP + UA). Prints the exact failure.
//
//	go run ./cmd/wbroomtest <cookies.json> <deviceId> [userAgent]
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"

	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/wbstream"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Println("usage: wbroomtest <cookies.json> <deviceId> [userAgent]")
		os.Exit(1)
	}
	raw := common.LoadCookies(os.Args[1])
	deviceID := os.Args[2]
	// Default UA matches the browser that minted the token (Chrome/149), NOT the
	// package default (148) — the antibot binds x_wbaas_token to the UA.
	ua := "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
	if len(os.Args) > 3 {
		ua = os.Args[3]
	}
	cookieHeader := common.FilterCookies(raw, wbstream.WBStreamCookieAllowlist)
	fmt.Printf("cookie header: %s\ndeviceId: %s\nUA: %s\n\n", cookieHeader, deviceID, ua)

	// Raw slide-v3, mimicking the browser fetch (no X-Real-IP; browser UA).
	fmt.Println("[1] slide-v3 (raw, browser-mimic) ...")
	req, _ := http.NewRequest(http.MethodPost, "https://auth-stream.wb.ru/v2/auth/slide-v3", bytes.NewReader(nil))
	req.Header.Set("wb-apptype", "web")
	req.Header.Set("deviceId", deviceID)
	req.Header.Set("X-Request-ID", "00000000-0000-4000-8000-000000000000")
	req.Header.Set("Origin", "https://stream.wb.ru")
	req.Header.Set("Referer", "https://stream.wb.ru/")
	req.Header.Set("Cookie", cookieHeader)
	req.Header.Set("User-Agent", ua)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		fmt.Printf("    transport error: %v\n", err)
		os.Exit(2)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 {
		fmt.Printf("    HTTP %d: %s\n", resp.StatusCode, string(body))
		os.Exit(2)
	}
	var sr struct {
		Payload struct {
			AccessToken string `json:"access_token"`
		} `json:"payload"`
	}
	json.Unmarshal(body, &sr)
	if sr.Payload.AccessToken == "" {
		fmt.Printf("    no access_token: %s\n", string(body))
		os.Exit(2)
	}
	access := sr.Payload.AccessToken
	fmt.Printf("    OK access token (%d chars)\n\n", len(access))

	fmt.Println("[2] CreateRoom ...")
	roomID, err := wbstream.CreateRoom(nil, access)
	if err != nil {
		fmt.Printf("    FAILED: %v\n", err)
		os.Exit(3)
	}
	fmt.Printf("    OK room: %s\n\n=> SUCCESS https://stream.wb.ru/room/%s\n", roomID, roomID)
}
