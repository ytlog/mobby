package com.github.ytlog.mobby.android.programs.base.net

import com.github.ytlog.mobby.android.programs.base.secrets.SecretStore
import com.github.ytlog.mobby.android.programs.base.storage.enumType
import com.github.ytlog.mobby.android.programs.base.storage.invalid
import com.github.ytlog.mobby.android.programs.base.storage.schema
import com.github.ytlog.mobby.android.programs.base.storage.stringType
import com.github.ytlog.mobby.android.programs.base.storage.text
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.capability.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SocketQuotas(
    val openPerCaller: Int = 4,
    /** Received messages waiting for the program; past either limit mobby closes the socket instead of dropping messages. */
    val bufferedBytes: Int = 4 * 1024 * 1024,
    val bufferedMessages: Int = 256,
    val maxWaitMillis: Long = 25_000,
) { companion object { val DEFAULT = SocketQuotas() } }

/**
 * `net.websocket`: client WebSockets opened by mobby for one run or page session, with the same host grant,
 * address checks and secret rules as `net.http`. The program pulls received messages with `receive`, so a
 * handler can wait for a reply inside itself; events would queue behind that very handler. Sockets close when
 * their run or page ends, or when the grant no longer covers them.
 */
class NetWebSocketCapability(
    private val secrets: SecretStore,
    private val transport: NetTransport = PlatformNetTransport,
    private val limits: WebSocketLimits = WebSocketLimits(),
    private val quotas: SocketQuotas = SocketQuotas.DEFAULT,
) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor("net.websocket", 1, CapabilityKind.SESSION,
        schema(listOf("op"), "op" to enumType("open", "send", "receive", "close"), "url" to stringType(8_192),
            "headers" to buildJsonObject { put("type", "object") },
            "protocols" to buildJsonObject { put("type", "array"); put("maxItems", 8); put("items", stringType(128)) },
            "socket" to stringType(64), "text" to stringType(limits.maxMessageBytes), "base64" to stringType(limits.maxMessageBytes / 3 * 4 + 4),
            "waitMs" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", quotas.maxWaitMillis) },
            "code" to buildJsonObject { put("type", "integer"); put("minimum", 1000); put("maximum", 4999) }, "reason" to stringType(123)),
        buildJsonObject { put("type", "object") }, ScopeKind.DOMAINS, setOf(SideEffect.NETWORK, SideEffect.SEND), emptyList(),
        setOf(Surface.BACKGROUND, Surface.UI), quotas.maxWaitMillis + limits.connectTimeoutMillis + limits.handshakeTimeoutMillis, true,
        CapabilityLimits(limits.maxMessageBytes / 3 * 4 + 64 * 1024, 600))

    private class Session(val id: String, val context: CallContext, val host: String, val insecure: Boolean, val connection: WebSocketConnection) {
        val inbox = ArrayDeque<WebSocketConnection.Message>()
        var inboxBytes = 0
        @Volatile var closed: WebSocketConnection.Closed? = null
        /** Wakes a waiting receive; conflated, so a message that arrives before the wait is not missed. */
        val signal = Channel<Unit>(Channel.CONFLATED)
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    override fun availability(): Availability = Availability.Available

    override fun callScope(args: JsonObject): CallScope = when (args.text("op")) {
        "open" -> parse(SecretPlaceholders.neutral(args.text("url") ?: invalid("url_required"))).let { CallScope(host = it.host.lowercase(), insecureHttp = it.scheme == "ws") }
        // The socket's own host is checked again, so a revoked or narrowed grant stops a socket that is already open.
        else -> session(args).let { CallScope(host = it.host, insecureHttp = it.insecure) }
    }

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome = when (call.args.text("op")) {
        "open" -> open(call)
        "send" -> send(call, owned(call))
        "receive" -> receive(call, owned(call))
        "close" -> close(call, owned(call))
        else -> invalid("unknown_op")
    }

    /** Closes the sockets of a run or page session that ended. */
    fun release(caller: String) {
        sessions.values.filter { it.context.caller == caller }.forEach { end(it, WebSocketConnection.Closed(GOING_AWAY, "session_ended", WebSocketConnection.Initiator.MOBBY)) }
    }

    /** Closes sockets whose program may no longer reach their host, e.g. after a revoke or a narrowed grant. */
    fun recheck(allowed: (CallContext, CallScope) -> Boolean) {
        sessions.values.filter { !allowed(it.context, CallScope(host = it.host, insecureHttp = it.insecure)) }
            .forEach { end(it, WebSocketConnection.Closed(WebSocketConnection.POLICY, "permission_revoked", WebSocketConnection.Initiator.MOBBY)) }
    }

    /** Open sockets, for tests and diagnostics. */
    fun openCount(caller: String) = sessions.values.count { it.context.caller == caller && it.closed == null }

    private suspend fun open(call: CapabilityCall): CapabilityOutcome {
        if (openCount(call.context.caller) >= quotas.openPerCaller) throw CapabilityException(ProgramErrorCode.QUOTA_EXCEEDED, "too_many_open_sockets")
        val headers = (call.args["headers"] as? JsonObject ?: JsonObject(emptyMap())).mapValues { (name, value) ->
            if (!name.matches(NetHttpCapability.HEADER_NAME) || name.lowercase().let { it in FORBIDDEN_HEADERS || it.startsWith("sec-websocket-") })
                invalid("header_not_allowed: $name")
            (value as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 8_192 && '\n' !in it.content && '\r' !in it.content }?.content
                ?: invalid("header_values_must_be_single_line_text")
        }
        val protocols = (call.args["protocols"] as? JsonArray).orEmpty().map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString && it.content.matches(TOKEN) }?.content ?: invalid("protocols_must_be_tokens")
        }
        val template = call.args.text("url")!!
        val url = parse(SecretPlaceholders.neutral(template))
        val host = url.host.lowercase()
        val address = runInterruptible(Dispatchers.IO) { transport.checkedAddresses(host) }.first()
        val target = parse(SecretPlaceholders.fill(secrets, template, call.context.programId, host, encode = true))
        val filled = headers.mapValues { SecretPlaceholders.fill(secrets, it.value, call.context.programId, host, encode = false) }
        val connection = try {
            runInterruptible(Dispatchers.IO) { WebSocketConnection.open(target, address, filled, protocols, transport, limits) }
        } catch (refused: WebSocketHandshakeException) {
            throw CapabilityException(ProgramErrorCode.FAILED, refused.reason, Effect.NONE)
        } catch (_: SocketTimeoutException) {
            throw CapabilityException(ProgramErrorCode.TIMEOUT, "network_timeout", Effect.UNKNOWN)
        } catch (error: IOException) {
            throw CapabilityException(ProgramErrorCode.SOURCE_DISCONNECTED, "network_error: ${error.javaClass.simpleName}", Effect.UNKNOWN)
        }
        val session = Session(UUID.randomUUID().toString(), call.context, host, url.scheme == "ws", connection)
        sessions[session.id] = session
        Thread({
            val ended = connection.readLoop { message -> accept(session, message) }
            if (session.closed == null) session.closed = ended
            session.signal.trySend(Unit)
        }, "websocket-${session.id}").apply { isDaemon = true }.start()
        return CapabilityOutcome(Effect.CONFIRMED, buildJsonObject {
            put("socket", session.id)
            connection.protocol?.let { put("protocol", it) }
        }, OperationAudit(host, "WEBSOCKET", 101, connection.bytesOut.get(), connection.bytesIn.get()))
    }

    private fun accept(session: Session, message: WebSocketConnection.Message): Boolean = synchronized(session) {
        val size = size(message)
        if (session.inbox.size >= quotas.bufferedMessages || session.inboxBytes + size > quotas.bufferedBytes) return false
        session.inbox.addLast(message)
        session.inboxBytes += size
        session.signal.trySend(Unit)
        true
    }

    private fun send(call: CapabilityCall, session: Session): CapabilityOutcome {
        session.closed?.let { throw CapabilityException(ProgramErrorCode.CONDITION_UNMET, "socket_closed") }
        val text = call.args.text("text")
        val base64 = call.args.text("base64")
        if ((text == null) == (base64 == null)) invalid("send_needs_text_or_base64")
        val bytes = base64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() ?: invalid("invalid_base64") }
        val size = bytes?.size ?: text!!.toByteArray().size
        if (size > limits.maxMessageBytes) invalid("message_too_large")
        try {
            if (bytes != null) session.connection.sendBinary(bytes) else session.connection.sendText(text!!)
        } catch (_: IOException) {
            throw CapabilityException(ProgramErrorCode.CONDITION_UNMET, "socket_closed", Effect.UNKNOWN)
        }
        // Written to the connection; whether the peer read it is not known.
        return CapabilityOutcome(Effect.SUBMITTED, JsonObject(emptyMap()), OperationAudit(session.host, "WEBSOCKET", null, size.toLong(), 0))
    }

    private suspend fun receive(call: CapabilityCall, session: Session): CapabilityOutcome {
        val wait = (call.args["waitMs"] as? JsonPrimitive)?.longOrNull ?: quotas.maxWaitMillis
        var batch = take(session)
        if (batch.isEmpty() && session.closed == null && wait > 0) {
            withTimeoutOrNull(wait) { session.signal.receive() }
            batch = take(session)
        }
        val closed = session.closed?.takeIf { synchronized(session) { session.inbox.isEmpty() } }
        // Once the program has been told how the socket ended there is nothing left to ask it.
        if (closed != null) sessions.remove(session.id)
        return CapabilityOutcome(Effect.NONE, buildJsonObject {
            put("messages", JsonArray(batch.map { message -> when (message) {
                is WebSocketConnection.Message.Text -> buildJsonObject { put("type", "text"); put("text", message.text) }
                is WebSocketConnection.Message.Binary -> buildJsonObject { put("type", "binary"); put("base64", Base64.getEncoder().encodeToString(message.bytes)) }
            } }))
            closed?.let { put("closed", closedJson(it)) }
        }, OperationAudit(session.host, "WEBSOCKET", null, 0, batch.sumOf { size(it).toLong() }))
    }

    /** Messages up to one message's worth of bytes, so a reply fits the API's response limits. */
    private fun take(session: Session): List<WebSocketConnection.Message> = synchronized(session) {
        val batch = mutableListOf<WebSocketConnection.Message>()
        var bytes = 0
        while (session.inbox.isNotEmpty()) {
            val next = size(session.inbox.first())
            if (batch.isNotEmpty() && bytes + next > limits.maxMessageBytes) break
            batch += session.inbox.removeFirst()
            bytes += next
            session.inboxBytes -= next
        }
        batch
    }

    private suspend fun close(call: CapabilityCall, session: Session): CapabilityOutcome {
        val code = (call.args["code"] as? JsonPrimitive)?.intOrNull ?: WebSocketConnection.NORMAL
        if (!WebSocketConnection.programCloseCode(code)) invalid("close_code_must_be_1000_or_3000_to_4999")
        val reason = call.args.text("reason").orEmpty()
        if (reason.toByteArray().size > 123) invalid("close_reason_too_long")
        if (session.closed == null) {
            session.connection.close(code, reason)
            withTimeoutOrNull(limits.closeWaitMillis) { while (session.closed == null) session.signal.receive() }
            if (session.closed == null) {
                session.closed = WebSocketConnection.Closed(code, "closed_without_peer_answer", WebSocketConnection.Initiator.PROGRAM)
                session.connection.abort()
            }
        }
        sessions.remove(session.id)
        return CapabilityOutcome(Effect.CONFIRMED, buildJsonObject { put("closed", closedJson(session.closed!!)) },
            OperationAudit(session.host, "WEBSOCKET", null, null, null))
    }

    /** Closes for a reason of mobby's, and forgets the socket: its caller is gone or may no longer use it. */
    private fun end(session: Session, why: WebSocketConnection.Closed) {
        sessions.remove(session.id)
        if (session.closed == null) session.closed = why
        session.connection.close(why.code ?: GOING_AWAY, why.reason, WebSocketConnection.Initiator.MOBBY)
        Thread({ Thread.sleep(limits.closeWaitMillis); session.connection.abort() }, "websocket-close-${session.id}").apply { isDaemon = true }.start()
        session.signal.trySend(Unit)
    }

    private fun owned(call: CapabilityCall): Session =
        session(call.args).takeIf { it.context.caller == call.context.caller } ?: throw CapabilityException(ProgramErrorCode.RESOURCE_MISSING, "unknown_socket")

    private fun session(args: JsonObject): Session =
        sessions[args.text("socket") ?: invalid("socket_required")] ?: throw CapabilityException(ProgramErrorCode.RESOURCE_MISSING, "unknown_socket")

    private fun parse(text: String): URI {
        val url = try { URI(text) } catch (_: Exception) { invalid("invalid_url") }
        if (url.scheme != "wss" && url.scheme != "ws") invalid("only_ws_and_wss")
        if (url.host.isNullOrEmpty() || url.rawUserInfo != null) invalid("url_needs_a_host_without_credentials")
        if (url.rawFragment != null) invalid("fragment_not_allowed")
        return url
    }

    private fun closedJson(closed: WebSocketConnection.Closed) = buildJsonObject {
        put("code", closed.code?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", closed.reason)
        put("by", closed.initiator.wire)
    }

    private fun size(message: WebSocketConnection.Message) = when (message) {
        is WebSocketConnection.Message.Text -> message.text.toByteArray().size
        is WebSocketConnection.Message.Binary -> message.bytes.size
    }

    companion object {
        const val GOING_AWAY = 1001
        private val TOKEN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")
        private val FORBIDDEN_HEADERS = setOf("host", "upgrade", "connection", "content-length", "transfer-encoding", "te", "trailer", "proxy-authorization")
    }
}
