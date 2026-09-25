package com.github.ytlog.mobby.android.localmodel.llama

/** Thin JNI boundary. Callers serialize load/generate/unload on one worker. */
object LlamaNative {
    init { System.loadLibrary("mobby_llama") }

    @JvmStatic external fun load(path: String): Long
    @JvmStatic external fun unload(handle: Long)
    @JvmStatic external fun generate(handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, sink: TokenSink): Int
    @JvmStatic external fun generateTools(handle: Long, messagesJson: String, toolsJson: String, choice: String,
        parallel: Boolean, enableThinking: Boolean, temperature: Float, topP: Float, maxTokens: Int, sink: ToolSink): String

    interface TokenSink { fun onStart(inputTokens: Int): Boolean; fun onToken(text: String): Boolean }
    interface ToolSink { fun onStart(inputTokens: Int): Boolean; fun onToken(): Boolean; fun onText(text: String): Boolean }
}
