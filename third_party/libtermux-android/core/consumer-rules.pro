# Consumer rules — applied to apps using this library
-keep class com.libtermux.** { *; }
-keepclassmembers class com.libtermux.utils.NativeUtils {
    native <methods>;
}
