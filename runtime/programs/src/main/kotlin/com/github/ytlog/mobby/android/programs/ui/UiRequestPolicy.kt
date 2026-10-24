package com.github.ytlog.mobby.android.programs.ui

import java.io.File
import java.net.URI

/** What the WebView receives for one request. */
sealed interface UiResponse {
    data class File(val file: java.io.File, val mimeType: String, val headers: Map<String, String>) : UiResponse
    data class Sdk(val headers: Map<String, String>) : UiResponse
    data class Denied(val status: Int, val reason: String) : UiResponse
}

/**
 * Pure request rules for a program page: only its own origin, only files of its installed copy,
 * no hidden files or path escapes, and every HTML response carries the content security policy.
 */
class UiRequestPolicy(val programId: String, private val packageDir: File) {
    val origin = origin(programId)

    fun respond(url: String, method: String): UiResponse {
        val uri = runCatching { URI(url) }.getOrNull() ?: return UiResponse.Denied(400, "invalid_url")
        if ("${uri.scheme}://${uri.host}" != origin || uri.port != -1) return UiResponse.Denied(403, "outside_program_origin")
        if (method != "GET" && method != "HEAD") return UiResponse.Denied(405, "method_not_allowed")
        val path = (uri.rawPath ?: "/").removePrefix("/")
        if (path == SDK_PATH) return UiResponse.Sdk(SCRIPT_HEADERS)
        val decoded = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrNull() ?: return UiResponse.Denied(400, "invalid_path")
        if (decoded.isEmpty() || decoded.split('/').any { it.isEmpty() || it == ".." || it == "." || it.startsWith(".") } || '\u0000' in decoded)
            return UiResponse.Denied(404, "not_found")
        val file = File(packageDir, decoded)
        val base = packageDir.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(base) || !file.isFile) return UiResponse.Denied(404, "not_found")
        val mime = mimeType(decoded) ?: return UiResponse.Denied(415, "unsupported_type")
        return UiResponse.File(file, mime, if (mime == "text/html") HTML_HEADERS else COMMON_HEADERS)
    }

    companion object {
        const val SDK_PATH = "_mobby/sdk.js"
        const val CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; " +
            "font-src 'self'; media-src 'self' blob:; connect-src 'self'; frame-src 'none'; frame-ancestors 'none'; object-src 'none'; " +
            "worker-src 'none'; base-uri 'none'; form-action 'none'"
        private val COMMON_HEADERS = mapOf("X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store")
        private val HTML_HEADERS = COMMON_HEADERS + ("Content-Security-Policy" to CSP)
        private val SCRIPT_HEADERS = COMMON_HEADERS

        fun origin(programId: String) = "https://$programId.program.invalid"

        private val TYPES = mapOf(
            "html" to "text/html", "htm" to "text/html", "js" to "text/javascript", "mjs" to "text/javascript", "css" to "text/css",
            "json" to "application/json", "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "ico" to "image/x-icon", "woff" to "font/woff", "woff2" to "font/woff2",
            "ttf" to "font/ttf", "txt" to "text/plain", "mp3" to "audio/mpeg", "mp4" to "video/mp4", "wasm" to "application/wasm",
        )

        fun mimeType(path: String) = TYPES[path.substringAfterLast('.', "").lowercase()]
    }
}
