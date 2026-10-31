package com.github.ytlog.mobby.android.programs.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.github.ytlog.mobby.android.programs.ProgramPlatform
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.CallRequest
import com.github.ytlog.mobby.android.programs.capability.ProgramErrorCode
import com.github.ytlog.mobby.android.programs.capability.Surface
import com.github.ytlog.mobby.android.programs.store.LogRow
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One opened program page. */
class UiSession internal constructor(val sessionId: String, val programId: String, val version: String) {
    @Volatile internal var reply: JavaScriptReplyProxy? = null
    @Volatile internal var closed = false
}

sealed interface UiAttach {
    data class Opened(val session: UiSession) : UiAttach
    data class Refused(val reason: String) : UiAttach
}

/**
 * Hosts program pages: per-program origin, installed files only, CSP on every page, no
 * permission prompts, popups or foreign navigation, and an origin-bound message bridge whose
 * calls take the same pipeline as background calls.
 */
class ProgramUiHost(private val platform: ProgramPlatform, private val sdkScript: () -> ByteArray, private val scope: CoroutineScope) {
    private val sessions = ConcurrentHashMap<String, UiSession>()

    /** Sends [message] to every open page of [programId]; returns how many received it. */
    fun post(programId: String, message: JsonElement): Int {
        var delivered = 0
        sessions.values.filter { it.programId == programId && !it.closed }.forEach { session ->
            val reply = session.reply ?: return@forEach
            scope.launch(Dispatchers.Main) { runCatching { reply.postMessage(envelope("ui.message", message)) } }
            delivered++
        }
        return delivered
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    suspend fun attach(webView: WebView, programId: String, openExternal: (Uri) -> Unit, onCrash: () -> Unit): UiAttach {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return UiAttach.Refused("webview_too_old")
        val program = withContext(Dispatchers.IO) { platform.dao.program(programId) } ?: return UiAttach.Refused("program_not_installed")
        val version = withContext(Dispatchers.IO) { platform.dao.version(programId, program.currentVersion) } ?: return UiAttach.Refused("program_not_installed")
        val entry = version.uiEntry ?: return UiAttach.Refused("program_has_no_ui")
        val policy = UiRequestPolicy(programId, File(version.packagePath))
        val session = UiSession(UUID.randomUUID().toString(), programId, version.version)
        sessions[session.sessionId] = session

        with(webView.settings) {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        WebView.setWebContentsDebuggingEnabled(false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = serve(policy, request.url.toString(), request.method)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if ("${url.scheme}://${url.host}" == policy.origin) return false
                // Other links leave the program only through the system browser, after the user confirms.
                if (request.hasGesture() && (url.scheme == "https" || url.scheme == "http")) openExternal(url)
                return true
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                close(session); onCrash(); return true
            }
        }
        val errors = PageErrorLog(policy.origin)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val entry = errors.entry(message.messageLevel(), message.message(), message.sourceId(), message.lineNumber()) ?: return true
                scope.launch(Dispatchers.IO) {
                    platform.dao.insertLog(LogRow(programId = programId, caller = "ui:${session.sessionId}", at = platform.now(),
                        message = entry.message, dataJson = entry.data.toString()))
                }
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                callback?.onReceiveValue(null); return true
            }
        }
        WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf(policy.origin)) { _, message, sourceOrigin, isMainFrame, reply ->
            if (sourceOrigin.toString().trimEnd('/') != policy.origin || !isMainFrame || session.closed) return@addWebMessageListener
            session.reply = reply
            handle(session, message, reply)
        }
        webView.loadUrl("${policy.origin}/$entry")
        return UiAttach.Opened(session)
    }

    fun close(session: UiSession) {
        session.closed = true
        session.reply = null
        sessions.remove(session.sessionId)
        platform.release("ui:${session.sessionId}")
    }

    private fun serve(policy: UiRequestPolicy, url: String, method: String): WebResourceResponse = when (val response = policy.respond(url, method)) {
        is UiResponse.File -> WebResourceResponse(response.mimeType, if (response.mimeType.startsWith("text/") || response.mimeType.endsWith("json")) "utf-8" else null,
            200, "OK", response.headers, response.file.inputStream())
        is UiResponse.Sdk -> WebResourceResponse("text/javascript", "utf-8", 200, "OK", response.headers, ByteArrayInputStream(sdkScript()))
        is UiResponse.Denied -> WebResourceResponse("text/plain", "utf-8", response.status, response.reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private fun handle(session: UiSession, message: WebMessageCompat, reply: JavaScriptReplyProxy) {
        val text = message.data ?: return
        if (text.length > MAX_MESSAGE) return reply(reply, error(null, ProgramErrorCode.INVALID_ARGUMENT, "message_too_large"))
        val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return reply(reply, error(null, ProgramErrorCode.INVALID_ARGUMENT, "invalid_message"))
        val id = (body["id"] as? JsonPrimitive)?.contentOrNull
        val context = CallContext(session.programId, session.version, Surface.UI, uiSessionId = session.sessionId)
        scope.launch {
            val answer = when (val type = (body["type"] as? JsonPrimitive)?.contentOrNull) {
                "hello" -> buildJsonObject { put("id", id); put("status", "succeeded"); put("effect", "none") }
                "ui.send" -> {
                    val delivered = platform.router.uiMessage(session.programId, body["message"] ?: JsonNull)
                    if (delivered) buildJsonObject { put("id", id); put("status", "succeeded"); put("effect", "none") }
                    else error(id, ProgramErrorCode.UNSUPPORTED, "no_background_logic_or_program_not_working")
                }
                "permissions" -> buildJsonObject {
                    put("id", id); put("status", "succeeded"); put("effect", "none")
                    put("result", JsonArray(platform.gate.snapshot.grants(session.programId).sortedBy { it.capability }.map { grant ->
                        buildJsonObject { put("capability", grant.capability); put("state", platform.gate.state(grant).wire); put("required", grant.required) }
                    }))
                }
                "call", null -> {
                    val capability = (body["capability"] as? JsonPrimitive)?.contentOrNull
                    val args = body["args"] as? JsonObject ?: JsonObject(emptyMap())
                    if (capability == null) error(id, ProgramErrorCode.INVALID_ARGUMENT, "capability_required")
                    else platform.pipeline.call(context, CallRequest("ui-${UUID.randomUUID()}", capability, args)).toJson().let { response ->
                        JsonObject(response + ("id" to JsonPrimitive(id)))
                    }
                }
                else -> error(id, ProgramErrorCode.UNSUPPORTED, "unknown_message_type_$type")
            }
            withContext(Dispatchers.Main) { if (!session.closed) reply(reply, answer) }
        }
    }

    private fun reply(proxy: JavaScriptReplyProxy, body: JsonObject) { runCatching { proxy.postMessage(body.toString()) } }

    private fun error(id: String?, code: ProgramErrorCode, reason: String) = buildJsonObject {
        put("id", id); put("status", "failed"); put("effect", "none")
        put("error", buildJsonObject { put("code", code.name); put("reason", reason) })
    }

    private fun envelope(type: String, payload: JsonElement) = buildJsonObject { put("type", type); put("payload", payload) }.toString()

    companion object {
        const val BRIDGE = "mobbyBridge"
        private const val MAX_MESSAGE = 2 * 1024 * 1024
    }
}
