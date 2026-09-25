package com.github.ytlog.mobby.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class LocalModelSmokeTest {
    @Test fun localServiceRunsInSeparateProcessAndListsModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authClass = Class.forName("com.github.ytlog.mobby.android.localmodel.LocalModelAuth")
        val constructor = authClass.getDeclaredConstructor(android.content.Context::class.java).apply { isAccessible = true }
        val auth = constructor.newInstance(context)
        val tokenMethod = authClass.declaredMethods.first { it.name.startsWith("token") && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }.apply { isAccessible = true }
        val token = tokenMethod.invoke(auth, "admin") as String
        tokenMethod.invoke(auth, "inference")
        context.startForegroundService(Intent().setClassName(context, "com.github.ytlog.mobby.android.localmodel.LocalModelService"))
        var health = ""
        for (attempt in 0 until 40) {
            Thread.sleep(250)
            health = runCatching { request("/local/v1/health", token) }.getOrDefault("")
            if (health.contains("LISTENING")) break
        }
        assertTrue("Service did not start: $health", health.contains("LISTENING"))
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        assertTrue(activityManager.runningAppProcesses.any { it.processName == "${context.packageName}:local_model" && it.pid != android.os.Process.myPid() })
        val engines = request("/local/v1/engines", token)
        assertTrue(engines.contains("\"id\":\"llama\""))
        val catalog = request("/local/v1/catalog/models?family=Qwen", token)
        assertTrue("No Qwen GGUF candidates: $catalog", catalog.contains("Qwen3-0.6B") && catalog.contains("qwen2.5-0.5b") && catalog.contains("sha256"))
        val gemma = request("/local/v1/catalog/models?family=Gemma", token)
        assertTrue("No Gemma GGUF candidates: $gemma", gemma.contains("gemma-3-270m") && gemma.contains("gemma-3-1b"))
        request("/local/v1/server/stop", token, "POST")
    }

    private fun request(path: String, token: String, method: String = "GET"): String {
        val c = URL("http://127.0.0.1:11435$path").openConnection() as HttpURLConnection
        c.connectTimeout = 1000
        c.readTimeout = 30000
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        if (method == "POST") { c.doOutput = true; c.outputStream.use { it.write("{}".toByteArray()) } }
        return try {
            val response = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            assertTrue("$path HTTP ${c.responseCode}: $response", c.responseCode in 200..299)
            response
        } finally { c.disconnect() }
    }
}
