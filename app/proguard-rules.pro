# AxiSight R8 rules (release builds only; debug is not minified).

# ---- USB camera stack (AUSBC + libuvc JNI) ----------------------------------
# libUVCCamera.so / libusb100.so call back into these classes by name.
-keep class com.jiangdg.** { *; }
-keep class com.serenegiant.** { *; }
-dontwarn com.jiangdg.**
-dontwarn com.serenegiant.**

# ---- Firebase / Play services -----------------------------------------------
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
# Readable Crashlytics stack traces.
-keepattributes SourceFile,LineNumberTable

# ---- Android components -------------------------------------------------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

-keep @interface androidx.annotation.Keep
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# JNI entry points.
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ---- Logging ------------------------------------------------------------------
-dontwarn org.slf4j.**
