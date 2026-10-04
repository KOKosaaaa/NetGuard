# Bundled relay creator

MIT-licensed source copied from the local kulikov0/whitelist-bypass checkout (base commit 9510bf8, including local carrier/PCARQ changes), 2026-09-20. See LICENSE. The creator shares WebRTC code between Telemost and WB to avoid shipping duplicate runtimes. Default entry point and flags remain Telemost; NETGUARD_CARRIER=wbstream selects WB guest hosting. WB adds a publisher-ready marker that is removed on disconnect.

Build from creator with CGO_ENABLED=0, GOOS=linux and GOARCH=amd64/arm64:
`go build -trimpath -ldflags="-s -w" -o ../../internal/deploy/embed/headless-telemost-creator-<arch> .`

Only source/module files and required .bin resources are vendored. No user cookies, room URLs or credentials are included.

NetGuard 2.0.3 adds a WB cumulative-ACK extension (PCARQ type 0x12). Receivers
ACK after socket delivery. A bounded per-connection window, bounded producer
queues, fair scheduling, shared retry/data pacing and tail probes prevent one
upload from flooding or blocking the entire channel. Older peers ignore the new
message and retain the NACK fallback; update both ends for flow control. Telemost
keeps its existing global-ARQ path. Tests cover an 11.26 MB upload with burst,
initial, ACK and final-packet loss, blocked sockets, timeout isolation and restart.

Build the Android executable from `relay` with GOOS=linux, GOARCH=arm64,
CGO_ENABLED=0 and `go build -trimpath -ldflags="-s -w" -o librelay.so .`.
This is an executable process; the filename matches Android's native extraction.

The managed-server agent exposes WB update and deletion operations. An update
temporarily starts a guest with `-hold-room` and a separate carrier key, keeping
the meeting alive without forwarding traffic. Deletion disables/stops both the
publisher and any update keeper before removing units and the room record.

2.0.4 negotiates a larger receive window with a versioned hello (0x13). The
sender sizes its window from measured RTT and its current pacing target, between
128 and 512 units; old peers remain limited to 128. RTT sampling excludes
retransmitted units, and the tail retry timer follows RTT/variance. Updated peers
bundle ACK/NACK/data into one VP8 sample (0x14); legacy sample durations still sum
to one wall-clock tick, avoiding the previous doubled RTP clock. Diagnostics log
negotiation, RTT, retry timer, window and queue sizes every five seconds.

The virtual-clock speed regression at 900 ms RTT and 2800 kbit/s pacing measures
1.223 Mbit/s with the legacy window versus 2.499 Mbit/s with the negotiated window
(4.504 MB transfer including startup and final ACK). This is a controlled model,
not a measurement of the user's WB/SFU connection.
