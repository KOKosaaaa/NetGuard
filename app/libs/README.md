# app/libs — native xray AAR

NetGuard links against a precompiled xray-core Android AAR that is **not** committed to this repository (size, upstream licensing). You must drop it here before the first build, otherwise `./gradlew assembleDebug` will fail with unresolved `go.Seq` / `libXray` symbols.

## What to place here

A single AAR file (name doesn't matter — the `build.gradle.kts` line

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

picks up anything matching `*.aar`).

## Compact APK packaging (1.7.3+)

The current AAR's arm64 `libgojni.so` has its non-runtime debug/symbol sections
stripped for release distribution. All allocated ELF sections (including code,
dynamic symbols, relocations and Go runtime tables) were verified byte-identical.
Keep this property when replacing the AAR; an unstripped upstream AAR can add over
10 MB to the compressed APK.

`xz-1.12.jar` is the pure Java XZ decoder from
https://repo.maven.apache.org/maven2/org/tukaani/xz/1.12/xz-1.12.jar
(SHA256 `3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b`,
0BSD license, https://tukaani.org/xz/java.html).
The two offline agent assets retain their original names but contain XZ data.
`BundledAgent.read` restores and validates the original ELF before either SSH
bootstrap or an agent update. Neither server architecture requires a download.

After replacing agent assets with fresh `agent/Makefile` outputs, run
`python tools/pack-agent-assets.py` from the project. The app also accepts raw ELF
assets in development builds. Update the fingerprints in `BundledAgentTest` when
intentionally rebuilding the agent; the tests verify exact decoded bytes and
reject corrupt or oversized installers. The APK itself uses standard Android ZIP
packaging and installs directly; users do not extract any archive.

## Exact release-preparation input

The local input audited for the 3.0.0 preparation is `libXray.aar`,
11,341,899 bytes, SHA-256
`8e4aa74d10b85a40d2f5fe634c31a33caaa4180d413bf2f54edc71a7167ed0c7`.
It exposes `libXray.LibXray` and `go.Seq`; an arbitrary `libv2ray.aar` is not
API-compatible. Preserve this input when reproducing the audited build.
The upstream source revision/build recipe of this existing local AAR has not
been established by this audit; obtaining reproducible provenance remains a
release input follow-up. Do not substitute a supposedly equivalent upstream
artifact without rechecking the API, native payloads and runtime behavior.

## After placing the AAR

```bash
JAVA_HOME="/path/to/android-studio/jbr" ./gradlew assembleDebug
```

The build should succeed and produce `app/build/outputs/apk/debug/app-debug.apk`.

## Verifying the binary

The current APK packages the AAR's ARM64 `libgojni.so`. Inspect the actual AAR
contents and hash, rather than assuming an additional `libxray.so` exists:

```bash
sha256sum app/libs/libXray.aar
unzip -l app/libs/libXray.aar
```
