# LibTermux — shizuku module ProGuard rules

# Keep all Shizuku runtime classes
-keep class rikka.shizuku.** { *; }
-keepclassmembers class rikka.shizuku.** { *; }

# Keep all ShizukuTermux public API
-keep class com.libtermux.shizuku.** { *; }
-keepclassmembers class com.libtermux.shizuku.** { *; }

# Shizuku permission callback
-keepclassmembers class * implements rikka.shizuku.Shizuku$OnRequestPermissionResultListener {
    public <methods>;
}
