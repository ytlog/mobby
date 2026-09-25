package com.github.ytlog.mobby.android.localmodel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Process-local timings only. Prompts, generated text, tools and credentials are never recorded. */
internal class InferenceTelemetry(private val clock: () -> Long = System::nanoTime) {
    data class Snapshot(
        val stage: String, val model: String, val protocol: String, val elapsedMs: Long,
        val promptTokens: Int = 0, val reusedTokens: Int = 0, val generatedTokens: Int = 0,
        val setupMs: Long? = null, val prefillMs: Long? = null,
        val firstTokenMs: Long? = null, val firstTextMs: Long? = null,
        val loadMs: Long? = null,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("stage", stage); put("model", model); put("protocol", protocol); put("elapsedMs", elapsedMs)
            put("promptTokens", promptTokens); put("reusedTokens", reusedTokens); put("generatedTokens", generatedTokens)
            setupMs?.let { put("setupMs", it) }; prefillMs?.let { put("prefillMs", it) }
            firstTokenMs?.let { put("firstTokenMs", it) }; firstTextMs?.let { put("firstTextMs", it) }
            loadMs?.let { put("loadMs", it) }
        }
    }

    @Volatile private var latest: Snapshot? = null
    @Volatile private var activeStart: Long? = null
    @Volatile private var lastLoadMs: Long? = null
    fun snapshot(): Snapshot? = latest?.let { value -> activeStart?.let { value.copy(elapsedMs = millis(it, clock())) } ?: value }
    private fun millis(from: Long, to: Long): Long = (to - from).coerceAtLeast(0) / 1_000_000

    inner class Run internal constructor(private val started: Long, initial: Snapshot) {
        private var value = initial
        private var prefillStarted = started
        private fun update(next: Snapshot) { value = next; latest = next }
        fun prompt(tokens: Int, reused: Int) {
            val now = clock(); prefillStarted = now
            update(value.copy(stage = "prefill", elapsedMs = millis(started, now), promptTokens = tokens,
                reusedTokens = reused, setupMs = millis(started, now)))
        }
        fun prefillComplete() {
            val now = clock()
            update(value.copy(stage = "generating", elapsedMs = millis(started, now), prefillMs = millis(prefillStarted, now)))
        }
        fun token(count: Int) {
            val now = clock()
            update(value.copy(stage = "generating", elapsedMs = millis(started, now), generatedTokens = count,
                firstTokenMs = value.firstTokenMs ?: millis(started, now)))
        }
        fun text() {
            if (value.firstTextMs == null) {
                val now = clock()
                update(value.copy(elapsedMs = millis(started, now), firstTextMs = millis(started, now)))
            }
        }
        fun finish(success: Boolean) {
            val now = clock()
            update(value.copy(stage = if (success) "generated" else "failed", elapsedMs = millis(started, now)))
            activeStart = null
        }
    }

    fun begin(model: String, protocol: String): Run {
        val now = clock()
        val initial = Snapshot("setup", model, protocol, 0, loadMs = lastLoadMs)
        activeStart = now
        latest = initial
        return Run(now, initial)
    }

    fun load(model: String, durationMs: Long, success: Boolean) {
        activeStart = null
        lastLoadMs = durationMs.takeIf { success }
        latest = Snapshot(if (success) "ready" else "load_failed", model, "load", durationMs, loadMs = durationMs)
    }
}
