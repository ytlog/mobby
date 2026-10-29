package com.github.ytlog.mobby.android.programs.ui

import android.webkit.ConsoleMessage.MessageLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Errors a program page reports to the WebView console (uncaught exceptions, syntax errors, blocked
 * resources, `console.error`). They go to the program log, where the user's details page and the
 * AI's records read them; otherwise a broken page is just blank. One instance per opened page.
 */
internal class PageErrorLog(private val origin: String, private val limit: Int = 20) {
    data class Entry(val message: String, val data: JsonObject)

    private var count = 0

    /** The log entry for one console message: null for non-errors, and after [limit] one truncation marker, then nothing. */
    @Synchronized fun entry(level: MessageLevel, message: String, source: String?, line: Int): Entry? {
        if (level != MessageLevel.ERROR) return null
        count++
        if (count == limit + 1) return Entry("page_errors_truncated", buildJsonObject { put("limit", limit) })
        if (count > limit) return null
        return Entry("page_error", buildJsonObject {
            put("message", message)
            put("source", source?.removePrefix("$origin/") ?: "")
            put("line", line)
        })
    }
}
