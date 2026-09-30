# Native methods use exported JNI names; their classes and method names must remain stable.
-keepclasseswithmembernames class * {
    native <methods>;
}

# sherpa-onnx JNI resolves Kotlin classes and fields by their original names.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The llama backend calls these callback methods by name from native code.
-keep class com.github.ytlog.mobby.android.localmodel.llama.LlamaNative$TokenSink { *; }
-keep class com.github.ytlog.mobby.android.localmodel.llama.LlamaNative$ToolSink { *; }

# Ktor 2.3.12 probes the desktop JVM debugger inside a catch(Throwable) block.
# Android has no JVM management API; the probe deliberately returns false there.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# SLF4J 1.7.36 explicitly falls back to its bundled NOP logger without a binding.
# This app does not supply an SLF4J backend; allow only that optional binding class.
-dontwarn org.slf4j.impl.StaticLoggerBinder
