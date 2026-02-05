#// Keep rules can be added here
-keep class com.example.myapp.** { *; }
-keepclassmembers class com.example.myapp.** { *; }

# Keep all activities, services, receivers, and providers
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep all methods annotated with @Keep
-keep @interface androidx.annotation.Keep
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Retrofit interfaces
-keep interface com.example.myapp.network.** { *; }

# Keep Gson model classes
-keep class com.example.myapp.models.** { *; }
-keepclassmembers class com.example.myapp.models.** {
    <fields>;
    <methods>;
}

# Keep OkHttp classes
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep logging classes
-keep class com.example.myapp.logging.** { *; }
-keepclassmembers class com.example.myapp.logging.** {
    <fields>;
    <methods>;
}
