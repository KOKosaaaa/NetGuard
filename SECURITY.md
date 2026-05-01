# Security Policy

## Reporting a Vulnerability

If you find a security issue in NetGuard, please **do not** open a public GitHub issue. Email the maintainer instead (address listed on the GitHub profile) or use GitHub's private "Report a vulnerability" flow on this repository.

Expected first response: **within 7 days**.
Expected resolution target for P0 issues: **30 days** from confirmation.

Please include:

- A short description of the issue.
- Steps to reproduce or a PoC (no real user data, please).
- Affected version (git commit or APK `versionName` from the About screen).
- Your preferred attribution (name / handle / anonymous) for the fix notes.

## In scope

- Information disclosure of the real VPN server IP to another app on the device (the April 2026 local-SOCKS leak class).
- Disclosure of VLESS/Trojan UUIDs, Shadowsocks passwords, or Hysteria2 `auth` strings from memory, logs, or shared storage.
- Authentication bypass on the local SOCKS5 / HTTP bridges (ports printed in `adb logcat`).
- DNS leaks — traffic to port 53 or DoH resolvers bypassing the tunnel.
- SSRF via subscription URLs, deep-link import, or profile parsing (including DNS-rebinding variants).
- Privilege boundary crossings (exported components, PendingIntent hijacking, intent redirection).
- Any cryptographic misuse.

## Out of scope

- Issues that require the attacker to already have root on the device.
- Social-engineering of the user ("install this fake NetGuard").
- Physical-access attacks.
- Denial of service (crashing the app via malformed input is in-scope *if* it persists across restarts; transient crashes are best-effort).
- The two honest caveats already documented in the README *Known limitations* section (tun2socks creds in argv, plain Room DB). These are tracked, non-urgent items — reports that only restate the caveat will be acknowledged but are not eligible for attribution.

## Supported versions

Only the latest `versionName` on `main` is supported. Backporting fixes to older tags is not offered.
