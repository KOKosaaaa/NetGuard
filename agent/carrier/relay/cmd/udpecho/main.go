// udpecho - минимальный UDP эхо-сервер для TURN-проба.
// Ставится на внешний (НЕ-VK) хост c публичным IP (наш FI), чтобы проверить,
// дотянется ли coturn Telemost/VK релеем до произвольного пира.
// Если TURN-проб получает свои пакеты обратно отсюда -> coturn permissive.
package main

import (
	"flag"
	"fmt"
	"net"
	"time"
)

func main() {
	addr := flag.String("listen", ":39999", "udp listen addr")
	flag.Parse()

	pc, err := net.ListenPacket("udp", *addr)
	if err != nil {
		panic(err)
	}
	defer pc.Close()
	fmt.Printf("udp echo listening on %s\n", *addr)

	buf := make([]byte, 65535)
	var pkts, bytes uint64
	last := time.Now()
	for {
		n, raddr, err := pc.ReadFrom(buf)
		if err != nil {
			continue
		}
		pkts++
		bytes += uint64(n)
		// эхо обратно отправителю (= relayed-адрес на coturn)
		pc.WriteTo(buf[:n], raddr)
		if time.Since(last) >= time.Second {
			fmt.Printf("echoed total=%d pkts, %d bytes, last from %s\n", pkts, bytes, raddr)
			last = time.Now()
		}
	}
}
