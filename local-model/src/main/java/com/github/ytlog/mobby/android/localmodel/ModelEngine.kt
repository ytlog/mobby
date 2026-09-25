package com.github.ytlog.mobby.android.localmodel

import com.github.ytlog.mobby.android.localmodel.llama.LlamaNative

internal data class PromptMessage(val role: String, val content: String)
internal class ModelEngine(private val store: LocalModelStore) {
    val telemetry = InferenceTelemetry()
    @Volatile private var loaded: InstalledModel? = null
    private var handle = 0L

    fun current(): String? = loaded?.id

    @Synchronized fun load(id: String): String {
        val model = store.models().firstOrNull { it.id == id } ?: error("Model is not installed")
        require(model.engine == "llama") { "Backend is not packaged" }
        if (loaded?.id == id && handle != 0L) return id
        if (handle != 0L) { LlamaNative.unload(handle); handle = 0L; loaded = null }
        val started = System.nanoTime()
        try {
            handle = LlamaNative.load(store.file(model).absolutePath)
            check(handle != 0L) { "Model could not be loaded" }
            loaded = model
            telemetry.load(id, (System.nanoTime() - started) / 1_000_000, true)
            return id
        } catch (e: Exception) {
            telemetry.load(id, (System.nanoTime() - started) / 1_000_000, false)
            throw e
        }
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
        val run = telemetry.begin(id, "text")
        var completed = false
        try {
            val count = LlamaNative.generate(handle, messages.map { it.role }.toTypedArray(), messages.map { it.content }.toTypedArray(), maxTokens,
                object : LlamaNative.TokenSink {
                    override fun onStart(inputTokens: Int, reusedTokens: Int): Boolean {
                        run.prompt(inputTokens, reusedTokens); return onStart(inputTokens)
                    }
                    override fun onPrefillComplete(): Boolean { run.prefillComplete(); return true }
                    override fun onGeneratedToken(count: Int): Boolean { run.token(count); return true }
                    override fun onToken(text: String): Boolean { run.text(); return sink(text) }
                })
            completed = count >= 0
            return count
        } finally { run.finish(completed) }
    }

    @Synchronized fun generateTools(id: String, context: ToolContext, maxTokens: Int, keepGoing: () -> Boolean,
                                    onStart: (Int) -> Boolean = { true }, onText: (String) -> Boolean = { true }): String {
        check(loaded?.id == id && handle != 0L) { "Model is not loaded" }
        val run = telemetry.begin(id, "tools")
        var completed = false
        try {
            val raw = LlamaNative.generateTools(handle, context.messages.toString(), context.tools.toString(), context.choice,
                context.parallel, context.enableThinking, context.temperature, context.topP, maxTokens,
                object : LlamaNative.ToolSink {
                    override fun onStart(inputTokens: Int, reusedTokens: Int): Boolean {
                        run.prompt(inputTokens, reusedTokens); return keepGoing() && onStart(inputTokens)
                    }
                    override fun onPrefillComplete(): Boolean { run.prefillComplete(); return keepGoing() }
                    override fun onGeneratedToken(count: Int): Boolean { run.token(count); return keepGoing() }
                    override fun onToken() = keepGoing()
                    override fun onText(text: String): Boolean { run.text(); return keepGoing() && onText(text) }
                })
            completed = true
            return raw
        } finally { run.finish(completed) }
    }
}
