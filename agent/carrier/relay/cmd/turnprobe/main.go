// turnprobe - проверяет, можно ли использовать TURN-сервер Telemost/VK как
// тупой релей до ПРОИЗВОЛЬНОГО пира (наш udpecho), без SFU/звонка.
//
// Вопрос на $1М: permissive coturn (relay на любой IP) vs locked (только SFU).
//   - Allocate провалился            -> проблема auth/кред (протухли?).
//   - WriteTo/permission провалился   -> coturn LOCKED на свои SFU-пиры. Путь мёртв.
//   - Эхо вернулось                   -> PERMISSIVE relay до произвольного пира. Дальше throughput.
//   - throughput высокий и down~=up   -> JACKPOT: нерезаный generic-релей через белый IP.
//   - throughput капается ~512кбит    -> coturn рейт-лимитит -> мультиплекс (striping).
//
// Тест НЕ требует whitelist-режима (permission+cap меряются с обычного канала).
//
// Usage:
//   turnprobe -turn turn:HOST:3478 -user USER -pass CRED -peer FI_IP:39999 [-dur 5s] [-size 1200]
package main

import (
	"flag"
	"fmt"
	"net"
	"strings"
	"time"

	"github.com/pion/logging"
	"github.com/pion/turn/v4"
)

// normalizeTurnAddr приводит "turn:host:3478?transport=tcp" -> "host:3478".
func normalizeTurnAddr(s string) string {
	for _, p := range []string{"turns:", "turn:", "stuns:", "stun:"} {
		s = strings.TrimPrefix(s, p)
	}
	if i := strings.Index(s, "?"); i >= 0 {
		s = s[:i]
	}
	return s
}

func main() {
	turnURL := flag.String("turn", "", "turn url или host:port (напр. turn:turnhost:3478)")
	user := flag.String("user", "", "turn username")
	pass := flag.String("pass", "", "turn credential")
	peer := flag.String("peer", "", "echo-сервер host:port для релея (наш udpecho)")
	dur := flag.Duration("dur", 5*time.Second, "длительность throughput-залива")
	pktSize := flag.Int("size", 1200, "размер UDP payload")
	verbose := flag.Bool("v", false, "debug-логи pion")
	flag.Parse()

	if *turnURL == "" || *peer == "" {
		fmt.Println("need -turn and -peer")
		return
	}

	turnAddr := normalizeTurnAddr(*turnURL)
	peerAddr, err := net.ResolveUDPAddr("udp4", *peer)
	if err != nil {
		fmt.Printf("bad -peer: %v\n", err)
		return
	}

	conn, err := net.ListenPacket("udp4", "0.0.0.0:0")
	if err != nil {
		fmt.Printf("listen local udp: %v\n", err)
		return
	}
	defer conn.Close()

	logf := logging.NewDefaultLoggerFactory()
	if *verbose {
		logf.DefaultLogLevel = logging.LogLevelDebug
	}

	client, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr: turnAddr,
		TURNServerAddr: turnAddr,
		Conn:           conn,
		Username:       *user,
		Password:       *pass,
		Realm:          "", // long-term cred: realm/nonce подтянутся из 401 автоматически
		LoggerFactory:  logf,
	})
	if err != nil {
		fmt.Printf("turn.NewClient: %v\n", err)
		return
	}
	defer client.Close()

	if err := client.Listen(); err != nil {
		fmt.Printf("client.Listen: %v\n", err)
		return
	}

	fmt.Printf("=== Allocate @ %s ===\n", turnAddr)
	relayConn, err := client.Allocate()
	if err != nil {
		fmt.Printf("ALLOCATE FAILED: %v\n  => auth/кред (протухли?) или TURN недоступен.\n", err)
		return
	}
	defer relayConn.Close()
	fmt.Printf("Allocate OK. relayed addr = %s\n", relayConn.LocalAddr())

	fmt.Printf("=== CreatePermission + first packet -> %s ===\n", peerAddr)
	if _, err := relayConn.WriteTo([]byte("TURNPROBE-HELLO"), peerAddr); err != nil {
		fmt.Printf("PERMISSION/WRITE FAILED: %v\n  => coturn LOCKED на свои SFU-пиры. Путь мёртв.\n", err)
		return
	}

	relayConn.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 65535)
	n, from, err := relayConn.ReadFrom(buf)
	if err != nil {
		fmt.Printf("NO ECHO BACK: %v\n  => permission отклонён или пир недостижим. Вероятно coturn LOCKED.\n", err)
		return
	}
	fmt.Printf("ECHO OK: %d bytes from %s: %q\n  => PERMISSIVE relay до произвольного пира!\n", n, from, string(buf[:n]))

	// --- throughput ---
	fmt.Printf("=== Throughput %s, payload %d ===\n", *dur, *pktSize)
	payload := make([]byte, *pktSize)
	var sent, recv uint64
	done := make(chan struct{})
	go func() {
		rbuf := make([]byte, 65535)
		for {
			relayConn.SetReadDeadline(time.Now().Add(time.Second))
			n, _, err := relayConn.ReadFrom(rbuf)
			if err != nil {
				select {
				case <-done:
					return
				default:
					continue
				}
			}
			recv += uint64(n)
		}
	}()

	start := time.Now()
	deadline := start.Add(*dur)
	for time.Now().Before(deadline) {
		if _, err := relayConn.WriteTo(payload, peerAddr); err != nil {
			fmt.Printf("write err: %v\n", err)
			break
		}
		sent += uint64(*pktSize)
	}
	time.Sleep(1500 * time.Millisecond) // дать дренироваться эхо
	close(done)

	el := time.Since(start).Seconds()
	fmt.Printf("\nsent    = %d bytes  (%.2f Mbit/s up)\n", sent, float64(sent*8)/el/1e6)
	fmt.Printf("echoed  = %d bytes  (%.2f Mbit/s down)\n", recv, float64(recv*8)/el/1e6)
	fmt.Printf("over %.1fs\n", el)
	fmt.Println("\nИнтерпретация: down ~= up и высоко -> нерезаный релей (ДЖЕКПОТ).")
	fmt.Println("               down капается ~0.5 Mbit -> coturn рейт-лимитит -> мультиплекс.")
}
