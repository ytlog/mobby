# LibTermux OS Module — ProGuard/R8 consumer rules
# Applied automatically to any app that depends on :os

-keep class com.libtermux.os.** { public *; }
-keep class com.libtermux.os.distro.** { *; }
-keep class com.libtermux.os.registry.** { *; }
-keep class com.libtermux.os.settings.** { *; }
-keep class com.libtermux.os.gui.** { *; }
-keep class com.libtermux.os.gui.vnc.** { *; }
-keep class com.libtermux.os.gui.compose.** { *; }

# Sealed class subclasses — required for when() exhaustive checks
-keep class com.libtermux.os.distro.DistroSetupState$* { *; }
-keep class com.libtermux.os.gui.vnc.VncState$* { *; }
-keep class com.libtermux.os.gui.DesktopSessionState$* { *; }
-keep class com.libtermux.os.SuResult$* { *; }
-keep class com.libtermux.os.distro.Distro$* { *; }

-keepclassmembers class com.libtermux.os.distro.Distro {
    public static ** Companion;
    public static ** all;
    public static ** fromId(...);
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.flow.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# DataStore
-keep class androidx.datastore.** { *; }

# DES cipher for VNC authentication
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.SecretKeySpec { *; }
-keep class javax.crypto.Cipher { *; }
