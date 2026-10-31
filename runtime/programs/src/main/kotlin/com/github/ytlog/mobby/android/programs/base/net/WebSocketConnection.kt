package com.github.ytlog.mobby.android.programs.base.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

data class WebSocketLimits(
    val maxMessageBytes: Int = 1024 * 1024,
    val connectTimeoutMillis: Int = 10_000,
    val handshakeTimeoutMillis: Int = 10_000,
    /** How long a close waits for the peer's close frame before the socket is dropped. */
    val closeWaitMillis: Long = 3_000,
)

/** A refused or failed opening handshake; [status] is the HTTP status when the server answered. */
class WebSocketHandshakeException(val reason: String, val status: Int? = null) : IOException(reason)

/**
 * One client WebSocket (RFC 6455) over a socket mobby opened to an address it already checked.
 * No extensions are offered, so frames never carry reserved bits; text must be valid UTF-8.
 */
class WebSocketConnection private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    /** The subprotocol the server chose from those offered, if any. */
    val protocol: String?,
    private val limits: WebSocketLimits,
    private val random: SecureRandom,
) {
    sealed interface Message {
        data class Text(val text: String) : Message
        class Binary(val bytes: ByteArray) : Message
    }

    /** Who ended the connection: the program, the peer, or mobby for a protocol or limit problem. */
    enum class Initiator(val wire: String) { PROGRAM("program"), PEER("peer"), MOBBY("mobby") }

    /** [code] is null when no close frame was exchanged, e.g. a dropped connection. */
    data class Closed(val code: Int?, val reason: String, val initiator: Initiator)

    private class ProtocolProblem(val code: Int, val reason: String) : Exception(reason)

    private val writeLock = Any()
    @Volatile private var closeSent: Pair<Int, Initiator>? = null
    val bytesIn = AtomicLong()
    val bytesOut = AtomicLong()

    fun sendText(text: String) = frame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
    fun sendBinary(bytes: ByteArray) = frame(OP_BINARY, bytes)

    /** Starts the closing handshake; [readLoop] returns once the peer answers or the wait runs out. */
    fun close(code: Int, reason: String, initiator: Initiator = Initiator.PROGRAM) {
        synchronized(writeLock) {
            if (closeSent != null) return
            closeSent = code to initiator
            runCatching { writeFrame(OP_CLOSE, ByteBuffer.allocate(2).putShort(code.toShort()).array() + reason.toByteArray(Charsets.UTF_8)) }
        }
    }

    /** Drops the socket without a closing handshake. */
    fun abort() { runCatching { socket.close() } }

    /**
     * Reads until the connection ends and says how. [deliver] gets each complete message and returns false when it
     * cannot keep it; mobby then closes the connection rather than drop messages silently.
     */
    fun readLoop(deliver: (Message) -> Boolean): Closed {
        var partial: ByteArrayOutputStream? = null
        var partialOpcode = 0
        try {
            while (true) {
                val b0 = read()
                val b1 = read()
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0f
                if (b0 and 0x70 != 0) throw ProtocolProblem(PROTOCOL_ERROR, "reserved_bits_set")
                if (b1 and 0x80 != 0) throw ProtocolProblem(PROTOCOL_ERROR, "server_frame_masked")
                val length = when (val short = b1 and 0x7f) {
                    126 -> (read() shl 8 or read()).toLong()
                    127 -> (0 until 8).fold(0L) { value, _ -> value shl 8 or read().toLong() }
                    else -> short.toLong()
                }
                if (opcode >= OP_CLOSE && (!fin || length > 125)) throw ProtocolProblem(PROTOCOL_ERROR, "invalid_control_frame")
                if (length < 0 || length + (partial?.size() ?: 0) > limits.maxMessageBytes) throw ProtocolProblem(TOO_BIG, "message_too_large")
                val payload = readFully(length.toInt())
                when (opcode) {
                    OP_CONTINUATION -> {
                        val buffer = partial ?: throw ProtocolProblem(PROTOCOL_ERROR, "unexpected_continuation")
                        buffer.write(payload)
                        if (fin) { partial = null; if (!deliver(message(partialOpcode, buffer.toByteArray()))) return overflow() }
                    }
                    OP_TEXT, OP_BINARY -> {
                        if (partial != null) throw ProtocolProblem(PROTOCOL_ERROR, "interleaved_message")
                        if (fin) { if (!deliver(message(opcode, payload))) return overflow() }
                        else { partial = ByteArrayOutputStream().apply { write(payload) }; partialOpcode = opcode }
                    }
                    OP_CLOSE -> return peerClosed(payload)
                    OP_PING -> synchronized(writeLock) { if (closeSent == null) writeFrame(OP_PONG, payload) }
                    OP_PONG -> Unit
                    else -> throw ProtocolProblem(PROTOCOL_ERROR, "unknown_opcode")
                }
            }
        } catch (problem: ProtocolProblem) {
            close(problem.code, problem.reason, Initiator.MOBBY)
            abort()
            return Closed(problem.code, problem.reason, Initiator.MOBBY)
        } catch (_: IOException) {
            abort()
            // After a close of our own the peer may just drop the socket instead of answering.
            return closeSent?.let { (code, by) -> Closed(code, "closed_without_peer_answer", by) } ?: Closed(null, "connection_lost", Initiator.PEER)
        }
    }

    private fun overflow(): Closed {
        close(POLICY, "receive_buffer_full", Initiator.MOBBY)
        abort()
        return Closed(POLICY, "receive_buffer_full", Initiator.MOBBY)
    }

    private fun peerClosed(payload: ByteArray): Closed {
        if (payload.size == 1) throw ProtocolProblem(PROTOCOL_ERROR, "invalid_close_payload")
        val code = if (payload.size >= 2) (payload[0].toInt() and 0xff shl 8) or (payload[1].toInt() and 0xff) else null
        if (code != null && !validCloseCode(code)) throw ProtocolProblem(PROTOCOL_ERROR, "invalid_close_code")
        val reason = if (payload.size > 2) utf8(payload.copyOfRange(2, payload.size)) ?: throw ProtocolProblem(INVALID_DATA, "close_reason_not_utf8") else ""
        val ours = closeSent
        // Echo the peer's code when it started the close; our own close already went out otherwise.
        if (ours == null) close(code ?: NORMAL, "", Initiator.PEER)
        abort()
        return if (ours != null) Closed(ours.first, reason.ifEmpty { "closed" }, ours.second) else Closed(code, reason, Initiator.PEER)
    }

    private fun message(opcode: Int, payload: ByteArray): Message =
        if (opcode == OP_TEXT) Message.Text(utf8(payload) ?: throw ProtocolProblem(INVALID_DATA, "text_not_utf8")) else Message.Binary(payload)

    private fun frame(opcode: Int, payload: ByteArray) {
        if (payload.size > limits.maxMessageBytes) throw IllegalArgumentException("message_too_large")
        synchronized(writeLock) {
            if (closeSent != null) throw IOException("closing")
            writeFrame(opcode, payload)
        }
    }

    /** Client frames are always masked with a fresh key. Callers hold [writeLock]. */
    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 0xffff -> { header.write(0x80 or 126); header.write(payload.size shr 8); header.write(payload.size and 0xff) }
            else -> { header.write(0x80 or 127); for (shift in 56 downTo 0 step 8) header.write((payload.size.toLong() shr shift).toInt() and 0xff) }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
        output.write(header.toByteArray() + masked)
        output.flush()
        bytesOut.addAndGet((header.size() + masked.size).toLong())
    }

    private fun read(): Int = input.read().also { if (it < 0) throw EOFException() }.also { bytesIn.incrementAndGet() }

    private fun readFully(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) throw EOFException()
            offset += n
        }
        bytesIn.addAndGet(length.toLong())
        return bytes
    }

    companion object {
        const val NORMAL = 1000
        const val PROTOCOL_ERROR = 1002
        const val INVALID_DATA = 1007
        const val POLICY = 1008
        const val TOO_BIG = 1009
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_RESPONSE_HEAD = 16 * 1024
        private const val CRLF_CRLF = 0x0d0a0d0a

        /** Codes a peer may send in a close frame (RFC 6455 7.4). */
        fun validCloseCode(code: Int) = code in 1000..1003 || code in 1007..1014 || code in 3000..4999

        /** Codes a program may close with: normal closure or its own range. */
        fun programCloseCode(code: Int) = code == NORMAL || code in 3000..4999

        /**
         * Connects to [address], which the caller checked, and performs the opening handshake for [url].
         * TLS verifies the certificate against the URL's host, never the address.
         */
        fun open(url: URI, address: java.net.InetAddress, headers: Map<String, String>, protocols: List<String>,
            transport: NetTransport, limits: WebSocketLimits, random: SecureRandom = SecureRandom()): WebSocketConnection {
            val secure = url.scheme == "wss"
            val host = url.host
            val port = url.port.takeIf { it > 0 } ?: if (secure) 443 else 80
            val raw = transport.connect(InetSocketAddress(address, port), limits.connectTimeoutMillis)
            var socket = raw
            try {
                // Set before TLS too: a server that never answers must not hold the call past its handshake time.
                socket.soTimeout = limits.handshakeTimeoutMillis
                if (secure) socket = transport.secure(socket, host, port).apply { soTimeout = limits.handshakeTimeoutMillis }
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
                val path = (url.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (url.rawQuery?.let { "?$it" } ?: "")
                val request = buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: ").append(host).append(if (url.port > 0 && url.port != (if (secure) 443 else 80)) ":${url.port}" else "").append("\r\n")
                    append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n")
                    if (protocols.isNotEmpty()) append("Sec-WebSocket-Protocol: ${protocols.joinToString(", ")}\r\n")
                    headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                    append("\r\n")
                }
                val output = socket.getOutputStream()
                output.write(request.toByteArray(Charsets.UTF_8))
                output.flush()
                val input = socket.getInputStream()
                val head = readHead(input)
                val lines = head.split("\r\n")
                val status = Regex("^HTTP/1\\.1 (\\d{3})(?: .*)?$").find(lines.first())?.groupValues?.get(1)?.toInt()
                    ?: throw WebSocketHandshakeException("invalid_handshake_response")
                if (status != 101) throw WebSocketHandshakeException("handshake_status_$status", status)
                val fields = lines.drop(1).filter { it.isNotEmpty() }.groupBy({ it.substringBefore(':').trim().lowercase() }, { it.substringAfter(':').trim() })
                fun field(name: String) = fields[name]?.joinToString(", ")
                if (field("upgrade")?.equals("websocket", ignoreCase = true) != true ||
                    field("connection")?.split(',')?.none { it.trim().equals("upgrade", ignoreCase = true) } != false)
                    throw WebSocketHandshakeException("handshake_not_upgraded", status)
                val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))
                if (field("sec-websocket-accept") != expected) throw WebSocketHandshakeException("handshake_accept_mismatch", status)
                if (!field("sec-websocket-extensions").isNullOrEmpty()) throw WebSocketHandshakeException("handshake_unrequested_extension", status)
                val chosen = field("sec-websocket-protocol")
                if (chosen != null && chosen !in protocols) throw WebSocketHandshakeException("handshake_unoffered_protocol", status)
                // Messages may be minutes apart; the run or page lifetime bounds the wait instead.
                socket.soTimeout = 0
                raw.soTimeout = 0
                return WebSocketConnection(socket, input, output, chosen, limits, random).also { it.bytesOut.addAndGet(request.length.toLong()); it.bytesIn.addAndGet(head.length + 4L) }
            } catch (error: Exception) {
                runCatching { socket.close() }
                runCatching { raw.close() }
                throw error
            }
        }

        /** The response head up to its blank line, read byte by byte so no frame data is consumed. */
        private fun readHead(input: InputStream): String {
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw WebSocketHandshakeException("handshake_connection_closed")
                head.write(b)
                last4 = last4 shl 8 or b
                if (last4 == CRLF_CRLF) return String(head.toByteArray(), 0, head.size() - 4, Charsets.ISO_8859_1)
                if (head.size() > MAX_RESPONSE_HEAD) throw WebSocketHandshakeException("handshake_response_too_large")
            }
        }

        private fun utf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { null }
    }
}
