# ────────────────────────────────────────────────────────────────────────────
# X-link R8 / ProGuard rules
#
# Keep line numbers in stack traces (BetaLogger captures crashes — without
# this they're useless).
# ────────────────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ────────────────────────────────────────────────────────────────────────────
# WebRTC SDK
# Native code in libjingle_peerconnection_so.so resolves Java symbols by name
# via JNI. Renaming any of these breaks runtime instantiation.
# ────────────────────────────────────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ────────────────────────────────────────────────────────────────────────────
# Codec2 JNI bridge (com.example.p2pcodec2.Codec2Bridge)
# native methods are looked up by their JNI symbol name.
# ────────────────────────────────────────────────────────────────────────────
-keep class com.example.p2pcodec2.Codec2Bridge {
    *;
    native <methods>;
}

# ────────────────────────────────────────────────────────────────────────────
# Firebase / Google Play Services
# ────────────────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Firestore custom-mapped POJOs (we use mapOf<>(), so no custom classes — but
# safe to keep the annotations in case a future refactor introduces them).
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
}

# ────────────────────────────────────────────────────────────────────────────
# Kotlin
# ────────────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ────────────────────────────────────────────────────────────────────────────
# AndroidX biometric — invoked reflectively from system framework
# ────────────────────────────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ────────────────────────────────────────────────────────────────────────────
# AndroidX security crypto — EncryptedSharedPreferences uses reflection.
# ────────────────────────────────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ────────────────────────────────────────────────────────────────────────────
# ZXing (QR scanner — embedded ScanContract uses reflection to find activities)
# ────────────────────────────────────────────────────────────────────────────
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# ────────────────────────────────────────────────────────────────────────────
# View binding generated classes — already referenced from Activity, R8 keeps
# them, but explicit rule documents intent.
# ────────────────────────────────────────────────────────────────────────────
-keep class com.example.xxxlinkxxx.databinding.** { *; }

# ────────────────────────────────────────────────────────────────────────────
# Suppress warnings from optional / not-bundled deps that crop up under R8
# ────────────────────────────────────────────────────────────────────────────
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
