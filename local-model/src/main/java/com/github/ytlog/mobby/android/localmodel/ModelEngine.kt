package com.github.ytlog.mobby.android.localmodel

import com.github.ytlog.mobby.android.localmodel.llama.LlamaNative

internal data class PromptMessage(val role: String, val content: String)
internal class ModelEngine(private val store: LocalModelStore) {
    private var loaded: InstalledModel? = null
    private var handle = 0L

    @Synchronized fun current(): String? = loaded?.id

    @Synchronized fun load(id: String): String {
        val model = store.models().firstOrNull { it.id == id } ?: error("Model is not installed")
        require(model.engine == "llama") { "Backend is not packaged" }
        if (loaded?.id == id && handle != 0L) return id
        if (handle != 0L) { LlamaNative.unload(handle); handle = 0L; loaded = null }
        handle = LlamaNative.load(store.file(model).absolutePath)
        check(handle != 0L) { "Model could not be loaded" }
        loaded = model
        return id
    }

    @Synchronized fun unload() {
        if (handle != 0L) LlamaNative.unload(handle)
        handle = 0L; loaded = null
    }

    @Synchronized fun generate(id: String, messages: List<PromptMessage>, maxTokens: Int, onStart: (Int) -> Boolean, sink: (String) -> Boolean): Int {
        check(loaded?.id == id && handle != 0L) { "Model is not loaded" }
        require(messages.isNotEmpty() && messages.size <= 64)
        require(maxTokens in 1..1024)
        require(messages.all { it.role in listOf("system", "user", "assistant") && it.content.isNotBlank() && it.content.length <= 32_768 })
        return LlamaNative.generate(handle, messages.map { it.role }.toTypedArray(), messages.map { it.content }.toTypedArray(), maxTokens,
            object : LlamaNative.TokenSink {
                override fun onStart(inputTokens: Int) = onStart(inputTokens)
                override fun onToken(text: String) = sink(text)
            })
    }
}
