# Native methods use exported JNI names; their classes and method names must remain stable.
-keepclasseswithmembernames class * {
    native <methods>;
}

# The llama backend calls these callback methods by name from native code.
-keep class com.github.ytlog.mobby.android.localmodel.llama.LlamaNative$TokenSink { *; }
-keep class com.github.ytlog.mobby.android.localmodel.llama.LlamaNative$ToolSink { *; }
