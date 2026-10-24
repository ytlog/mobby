package com.github.ytlog.mobby.android.programs

import com.github.ytlog.mobby.android.programs.ui.UiRequestPolicy
import com.github.ytlog.mobby.android.programs.ui.UiResponse
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UiRequestPolicyTest {
    private val root = Files.createTempDirectory("ui").toFile()
    private val pkg = File(root, "pkg").apply { File(this, "ui").mkdirs(); File(this, "ui/index.html").writeText("<html>"); File(this, "ui/app.js").writeText("x"); File(this, ".hidden").writeText("x") }
    private val policy = UiRequestPolicy("price-watch", pkg)
    private val origin = "https://price-watch.program.invalid"

    @After fun clean() { root.deleteRecursively() }

    @Test fun `pages get their files with the content security policy`() {
        val html = policy.respond("$origin/ui/index.html", "GET") as UiResponse.File
        assertEquals("text/html", html.mimeType)
        assertTrue(html.headers.getValue("Content-Security-Policy").contains("script-src 'self'"))
        assertTrue(html.headers.getValue("Content-Security-Policy").contains("connect-src 'self'"))
        assertEquals("text/javascript", (policy.respond("$origin/ui/app.js", "GET") as UiResponse.File).mimeType)
        assertTrue(policy.respond("$origin/_mobby/sdk.js", "GET") is UiResponse.Sdk)
    }

    @Test fun `everything outside the program's own files is refused`() {
        listOf(
            "https://other.program.invalid/ui/index.html" to 403, "https://example.com/x.js" to 403,
            "$origin:8443/ui/index.html" to 403, "$origin/ui/%2e%2e/%2e%2e/secret" to 404,
            "$origin/.hidden" to 404, "$origin/ui/missing.html" to 404, "$origin/manifest.json" to 404,
        ).forEach { (url, status) -> assertEquals(url, status, (policy.respond(url, "GET") as UiResponse.Denied).status) }
        File(pkg, "data.bin").writeText("x")
        assertEquals(415, (policy.respond("$origin/data.bin", "GET") as UiResponse.Denied).status)
        assertEquals(405, (policy.respond("$origin/ui/index.html", "POST") as UiResponse.Denied).status)
    }
}
