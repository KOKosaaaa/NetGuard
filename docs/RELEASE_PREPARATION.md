# Release preparation

The user authorized publication of 3.0.0 on 2026-10-04. The final version is
3.0.0 / versionCode 114; see [release notes](releases/3.0.0.md).
This document retains the preparation procedure and validation limits.

## Build inputs

- JDK 17 or a compatible JDK; checked-in Gradle wrapper and Android SDK 34.
- ARM64 native input libraries and the local libXray AAR described in
  [app/libs/README.md](../app/libs/README.md). Geodata assets are separate inputs.
- Rebuild `agent/carrier/relay` for Android ARM64, both Linux headless creator
  executables embedded under `agent/internal/deploy/embed`, then both Linux
  agents. Updating Go source without rebuilding bundled binaries is insufficient.
- Agent asset compression is internal XZ; `BundledAgent` accepts both XZ and raw
  ELF and checks the decoded architecture. Record compressed and decoded hashes.
- Keep `keystore.properties`, signing keys and service credentials outside Git.
  Release builds require the existing release signing key and have no debug-key
  fallback. Preserve the certificate so upgrades retain user data.

## Verification

Run `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease` with the final
inputs. Full Lint is required; `lintVitalRelease` alone is not a substitute.
Retain the XML test reports and all failures/skips. Review remaining warnings.

Run the Go relay tests with the same carrier environment as the packaged Android
transport. Environment flags must be absent when disabled: a value of `0` may
still enable flags implemented as presence checks. Run Go vet and cross-build
both agent architectures; Linux-specific agent tests need Linux execution.

On an owned Android emulator, run the instrumentation cases for TLS speed,
notifications, best-server cancellation, bypass/diagnostics, WB recovery,
localization and all themes/viewport variants. Fixtures that replace transport
state verify application behavior, not measured VPN throughput.

Check the actual signed APK: package, version, ARM64 ABI, signing certificate,
archive integrity, release debuggability, native payload hashes and decoded agent
hashes. Test keys and instrumentation-only components must be absent. Upgrade an
existing installation without clearing its database. Scan that exact signed APK
with Play Protect on an owned Google Play emulator and retain its hash and scan
result; this does not guarantee that every phone will omit the installer prompt.

Physical-device checks still required before claiming broad stability: upgrade
with real profiles, Wi-Fi/mobile changes, background/lock-screen behavior,
long-running WB/Telemost transfer, room cleanup and uninterrupted Remote sessions.

## Final publication

The final agent identifies itself as 0.5.13 / capability revision 14. Existing
revision-13 installations now qualify for the WB updater. Real ELF upgrade from
0.5.12 and byte-exact rollback passed in a temporary Linux directory. The HTTPS
upgrade on HEL also confirmed version 0.5.13, revision 14 and a new process.
Older servers with the former 30-second body limit can require an initial SSH
bootstrap when the phone-to-server upload is slow; release notes document this.

On 2026-10-04, the user's request to repair HEL authorized a targeted exception:
HEL received the authenticated-upload deadline fix via verified SSH. A complete
26,972,320-byte HTTPS self-update took 43.99 seconds and confirmed a new process
and matching binary hash. Development APK 2.0.34 / 113 embeds that same fixed
agent plus the arm64 build. Other existing agents are not implicitly upgraded;
the final release migration checks above subsequently closed this gate.

After draft approval, assign the final versionCode/versionName, rebuild and
repeat artifact checks on that exact APK. Generate a checksum asset and fill
validation/install details in the release notes. Publish only the reviewed final
artifact; preparation builds retain their development version and are not 3.0.0.
