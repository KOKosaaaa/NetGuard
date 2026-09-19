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

## Where to get it

Option A — prebuilt release (fastest):

1. Open https://github.com/AnyaKovaleva/libXray/releases
2. Download the latest `libXray-v*.aar` (tested: v1.8.12, arm64-v8a).
3. Save it as `app/libs/libXray.aar`.

Option B — build from source:

1. `git clone https://github.com/2dust/AndroidLibXrayLite`
2. Follow the README there (requires `gomobile`, Go 1.21+, NDK r26).
3. Copy the resulting `libv2ray.aar` into `app/libs/`.

## After placing the AAR

```bash
JAVA_HOME="/path/to/android-studio/jbr" ./gradlew assembleDebug
```

The build should succeed and produce `app/build/outputs/apk/debug/app-debug.apk`.

## Verifying the binary

The AAR bundles a native `libgojni.so` (Go runtime) and `libxray.so`. If you want to confirm it is the version you expect:

```bash
unzip -p app/libs/libXray.aar jni/arm64-v8a/libxray.so | strings | grep -i "xray/v"
```
