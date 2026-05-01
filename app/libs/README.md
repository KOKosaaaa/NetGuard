# app/libs — native xray AAR

NetGuard links against a precompiled xray-core Android AAR that is **not** committed to this repository (size, upstream licensing). You must drop it here before the first build, otherwise `./gradlew assembleDebug` will fail with unresolved `go.Seq` / `libXray` symbols.

## What to place here

A single AAR file (name doesn't matter — the `build.gradle.kts` line

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

picks up anything matching `*.aar`).

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
