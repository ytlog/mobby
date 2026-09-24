package com.github.ytlog.mobby.android.localmodel.mlc

import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Optional adapter for the official generated mlc4j Android package. No native code is downloaded at runtime. */
class MlcRuntime {
    private val cls = Class.forName("ai.mlc.mlcllm.JSONFFIEngine")
    private val callbackType = Class.forName("ai.mlc.mlcllm.JSONFFIEngine\$KotlinFunction")
    private val instance = cls.getConstructor().newInstance()
    private var active: AtomicReference<Request?> = AtomicReference(null)
    private val workers = listOf("runBackgroundLoop", "runBackgroundStreamBackLoop").map { name ->
        Thread({ cls.getMethod(name).invoke(instance) }, "mlc-$name").apply { isDaemon = true }
    }

    private data class Request(val id: String, val sink: (String) -> Boolean, val done: CountDownLatch, var tokens: Int = -1, var error: String? = null)

    init {
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, args ->
            if (method.name == "invoke") onCallback(args?.firstOrNull() as? String)
            null
        }
        cls.getMethod("initBackgroundEngine", callbackType).invoke(instance, callback)
        workers.forEach(Thread::start)
    }

    private fun onCallback(raw: String?) {
        val request = active.get() ?: return
        try {
            val entries = Json.parseToJsonElement(raw ?: return) as? JsonArray ?: return
            for (item in entries) {
                val obj = item.jsonObject
                if (obj["id"]?.jsonPrimitive?.content != request.id) continue
                val choices = obj["choices"] as? JsonArray
                choices?.forEach { choice ->
                    val text = choice.jsonObject["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty() && !request.sink(text)) { request.error = "Client cancelled"; request.done.countDown() }
                }
                obj["usage"]?.jsonObject?.let { usage ->
                    request.tokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: -1
                    request.done.countDown()
                }
            }
        } catch (e: Exception) { request.error = e.message ?: "MLC stream error"; request.done.countDown() }
    }

    fun load(modelPath: String, modelLibId: String) {
        require(modelLibId.matches(Regex("[A-Za-z0-9_]+")))
        val config = buildJsonObject { put("model", modelPath); put("model_lib", "system://$modelLibId"); put("mode", "interactive") }
        cls.getMethod("reload", String::class.java).invoke(instance, config.toString())
    }

    @Synchronized fun generate(messages: List<Pair<String, String>>, maxTokens: Int, sink: (String) -> Boolean): Int {
        require(maxTokens in 1..1024)
        val id = UUID.randomUUID().toString()
        val state = Request(id, sink, CountDownLatch(1))
        check(active.compareAndSet(null, state)) { "MLC is busy" }
        try {
            val body = buildJsonObject {
                put("model", "local")
                put("messages", JsonArray(messages.map { (role, content) -> buildJsonObject { put("role", role); put("content", content) } }))
                put("stream", true); put("max_tokens", maxTokens)
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
            cls.getMethod("chatCompletion", String::class.java, String::class.java).invoke(instance, body.toString(), id)
            if (!state.done.await(180, TimeUnit.SECONDS)) error("MLC generation timed out")
            state.error?.let { error(it) }
            check(state.tokens >= 0) { "MLC did not return token usage" }
            return state.tokens
        } finally { active.compareAndSet(state, null) }
    }

    fun unload() { cls.getMethod("unload").invoke(instance) }
    fun close() {
        runCatching { unload() }
        runCatching { cls.getMethod("exitBackgroundLoop").invoke(instance) }
    }

    companion object {
        fun packaged(): Boolean = runCatching { Class.forName("ai.mlc.mlcllm.JSONFFIEngine"); true }.getOrDefault(false)
    }
}
