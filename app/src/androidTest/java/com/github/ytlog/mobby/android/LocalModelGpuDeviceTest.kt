package com.github.ytlog.mobby.android

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy

/** Runs against an already downloaded GGUF on a Vulkan-capable phone; never downloads a model. */
@RunWith(AndroidJUnit4::class)
class LocalModelGpuDeviceTest {
    @Test fun gemma4PlainChatStartsGenerating() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "local-models").listFiles()
            ?.firstOrNull { it.extension == "gguf" && it.name.contains("gemma-4") }
        assumeTrue("Download Gemma 4 before running this device test", model != null)
        val native = Class.forName("com.github.ytlog.mobby.android.localmodel.llama.LlamaNative")
        val handle = native.getMethod("load", String::class.java).invoke(null, model!!.absolutePath) as Long
        try {
            val sinkType = Class.forName("com.github.ytlog.mobby.android.localmodel.llama.LlamaNative\$TokenSink")
            var prefilled = false
            var generated = 0
            val sink = Proxy.newProxyInstance(sinkType.classLoader, arrayOf(sinkType)) { _, method, args ->
                when (method.name) {
                    "onPrefillComplete" -> prefilled = true
                    "onGeneratedToken" -> generated = args?.get(0) as Int
                }
                true
            }
            val count = native.getMethod("generate", java.lang.Long.TYPE, Array<String>::class.java,
                Array<String>::class.java, Integer.TYPE, sinkType)
                .invoke(null, handle, arrayOf("user"), arrayOf("你好"), 4, sink) as Int
            assertTrue("Gemma 4 did not finish prefill", prefilled)
            assertTrue("Gemma 4 did not generate a token", count > 0 && generated > 0)
        } finally {
            native.getMethod("unload", java.lang.Long.TYPE).invoke(null, handle)
        }
    }

    @Test fun installedModelLoadsAndGeneratesOnVulkan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE))
        val model = File(context.filesDir, "local-models").listFiles()?.firstOrNull { it.extension == "gguf" }
        assumeTrue("Download a GGUF model before running the GPU device test", model != null)
        val native = Class.forName("com.github.ytlog.mobby.android.localmodel.llama.LlamaNative")
        val load = native.getMethod("load", String::class.java)
        val unload = native.getMethod("unload", java.lang.Long.TYPE)
        val loadStarted = SystemClock.elapsedRealtime()
        val handle = load.invoke(null, model!!.absolutePath) as Long
        try {
            val backend = native.getMethod("backend", java.lang.Long.TYPE).invoke(null, handle) as String
            Log.i("MobbyGpuSmoke", "Loaded backend: $backend in ${SystemClock.elapsedRealtime() - loadStarted} ms")
            val reason = native.getMethod("backendReason", java.lang.Long.TYPE).invoke(null, handle) as String?
            assumeTrue("Device Vulkan API is below 1.2", reason != "vulkan_driver_too_old")
            assertTrue("Expected Vulkan, got $backend", backend.startsWith("Vulkan GPU"))
            val sinkType = Class.forName("com.github.ytlog.mobby.android.localmodel.llama.LlamaNative\$TokenSink")
            var prefilled = false
            val generateStarted = SystemClock.elapsedRealtime()
            val sink = Proxy.newProxyInstance(sinkType.classLoader, arrayOf(sinkType)) { _, method, args ->
                when (method.name) {
                    "onStart" -> Log.i("MobbyGpuSmoke", "Prompt tokens: ${args?.get(0)}; reused: ${args?.get(1)}")
                    "onPrefillComplete" -> {
                        prefilled = true
                        Log.i("MobbyGpuSmoke", "Prefill in ${SystemClock.elapsedRealtime() - generateStarted} ms")
                    }
                }
                true
            }
            val generate = native.getMethod("generate", java.lang.Long.TYPE, Array<String>::class.java,
                Array<String>::class.java, Integer.TYPE, sinkType)
            val count = generate.invoke(null, handle, arrayOf("user"), arrayOf("你好"), 4, sink) as Int
            Log.i("MobbyGpuSmoke", "Generated $count tokens in ${SystemClock.elapsedRealtime() - generateStarted} ms")
            assertTrue("Prompt was not processed", prefilled)
            assertTrue("Generation failed with $count", count >= 0)
        } finally {
            unload.invoke(null, handle)
        }
    }
}
