package com.github.ytlog.mobby.android.programs

import android.webkit.ConsoleMessage.MessageLevel
import com.github.ytlog.mobby.android.programs.ui.PageErrorLog
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PageErrorLogTest {
    private val origin = "https://price-watch.program.mobby"

    /** On the tablet a page module with a syntax error left a blank page and nothing in the program's records. */
    @Test fun `page errors are logged with the file they came from`() {
        val entry = PageErrorLog(origin).entry(MessageLevel.ERROR, "Uncaught SyntaxError: Unexpected reserved word", "$origin/ui/app.js", 67)!!
        assertEquals("page_error", entry.message)
        assertEquals("Uncaught SyntaxError: Unexpected reserved word", entry.data["message"]!!.jsonPrimitive.content)
        assertEquals("ui/app.js", entry.data["source"]!!.jsonPrimitive.content)
        assertEquals(67, entry.data["line"]!!.jsonPrimitive.int)
    }

    @Test fun `ordinary console output is not an error`() {
        assertNull(PageErrorLog(origin).entry(MessageLevel.LOG, "hello", "$origin/ui/app.js", 1))
        assertNull(PageErrorLog(origin).entry(MessageLevel.WARNING, "careful", "$origin/ui/app.js", 1))
    }

    @Test fun `a page that keeps failing cannot flood the log`() {
        val log = PageErrorLog(origin, limit = 2)
        val messages = (1..5).mapNotNull { log.entry(MessageLevel.ERROR, "boom $it", "$origin/ui/app.js", it)?.message }
        assertEquals(listOf("page_error", "page_error", "page_errors_truncated"), messages)
    }

    @Test fun `sources outside the program are kept as written`() {
        val entry = PageErrorLog(origin).entry(MessageLevel.ERROR, "x", "https://elsewhere.example/a.js", 1)!!
        assertEquals("https://elsewhere.example/a.js", entry.data["source"]!!.jsonPrimitive.content)
    }
}
