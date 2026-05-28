# libv2ray / libXray
-keep class libv2ray.** { *; }
-keep class libXray.** { *; }
-keep class go.** { *; }
-keep class org.golang.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.smarttools.netguard.model.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class * extends androidx.room.RoomDatabase$Callback
-dontwarn androidx.room.paging.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.platform.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit
-keep class com.google.mlkit.** { *; }

# Strip Log.d / Log.v in release. Defence-in-depth: even if a future change
# logs a server address or UUID directly with Log.d, R8 removes the call so
# nothing reaches `adb logcat`. Log.i / Log.w / Log.e remain.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# sshj (Add Server wizard) pulls in i2p EdDSA which optionally references
# sun.security.x509.X509Key — that class only exists in OpenJDK and is
# never reached on Android. R8 flags it as a missing reference; silence.
-dontwarn sun.security.x509.**
-dontwarn org.bouncycastle.jce.**
-dontwarn net.schmizz.sshj.**
