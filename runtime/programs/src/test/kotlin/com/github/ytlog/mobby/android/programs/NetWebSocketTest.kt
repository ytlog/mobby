package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.base.net.NetTransport
import com.github.ytlog.mobby.android.programs.base.net.NetWebSocketCapability
import com.github.ytlog.mobby.android.programs.base.net.SocketQuotas
import com.github.ytlog.mobby.android.programs.base.net.WebSocketLimits
import com.github.ytlog.mobby.android.programs.base.secrets.SecretCipher
import com.github.ytlog.mobby.android.programs.base.secrets.SecretStore
import com.github.ytlog.mobby.android.programs.base.secrets.SecretsCapability
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.CallPipeline
import com.github.ytlog.mobby.android.programs.call.CallRequest
import com.github.ytlog.mobby.android.programs.call.CallResponse
import com.github.ytlog.mobby.android.programs.call.CallStatus
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.*
import com.github.ytlog.mobby.android.programs.program.ProgramLayout
import com.github.ytlog.mobby.android.programs.store.GrantRow
import com.github.ytlog.mobby.android.programs.store.ProgramRow
import com.github.ytlog.mobby.android.programs.store.ProgramsDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NetWebSocketTest {
    private val root = Files.createTempDirectory("ws").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.dao()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    /** What the server side of each accepted connection does; set per test before opening. */
    @Volatile private var script: (Peer) -> Unit = { it.accept(); it.echo() }
    private val handshakes = LinkedBlockingQueue<Map<String, String>>()
    private val serverSaw = LinkedBlockingQueue<String>()

    private object PlainCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray() to byteArrayOf(1)
        override fun decrypt(ciphertext: ByteArray, iv: ByteArray) = ciphertext.reversedArray()
    }

    /** Public-looking addresses for the checks; every connection really goes to the local scripted server. */
    private open inner class LocalTransport : NetTransport {
        val routes = mapOf("live.example.com" to "93.184.216.34", "other.example.com" to "93.184.216.35", "intranet.example.com" to "192.168.1.10")
        override fun resolve(host: String) = listOf(InetAddress.getByName(routes[host] ?: error("unexpected host $host")))
        override fun connect(address: InetSocketAddress, timeoutMillis: Int) = Socket().apply { connect(server.localSocketAddress, timeoutMillis) }
    }
    /** The scripted server speaks plain frames, so wss stays unencrypted here; TLS itself is the platform's. */
    private val transport = object : LocalTransport() { override fun secure(socket: Socket, host: String, port: Int) = socket }
    private val secrets = SecretStore(dao, PlainCipher) { 1 }
    private val limits = WebSocketLimits(maxMessageBytes = 4_096, handshakeTimeoutMillis = 1_000, closeWaitMillis = 1_000)
    private val sockets = NetWebSocketCapability(secrets, transport, limits,
        SocketQuotas(openPerCaller = 2, bufferedBytes = 10_000, bufferedMessages = 3, maxWaitMillis = 3_000))
    private val registry = CapabilityRegistry(listOf(sockets, SecretsCapability(secrets)))
    private val gate = PermissionGate(registry) { 1 }
    private val pipeline = CallPipeline(registry, gate, dao, ProgramLayout(File(root, "programs")), { 1 })
    private val run = CallContext("p", "1", Surface.BACKGROUND, runId = "r")
    private var ids = 0

    /** The server end of one connection: RFC 6455 framing written by hand so tests can also break the rules. */
    inner class Peer(private val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        lateinit var headers: Map<String, String>

        fun accept(status: Int = 101, accept: String? = null, extra: String = "") {
            val lines = generateSequence { readLine() }.takeWhile { it.isNotEmpty() }.toList()
            headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() } + ("request" to lines.first())
            handshakes.add(headers)
            val key = headers["sec-websocket-key"]!!
            val digest = accept ?: Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            val reason = if (status == 101) "Switching Protocols" else "Forbidden"
            output.write("HTTP/1.1 $status $reason\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $digest\r\n$extra\r\n".toByteArray())
            output.flush()
        }

        private fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == '\n'.code) return line.toString().trimEnd('\r')
                line.append(c.toChar())
            }
        }

        /** A client frame: opcode and unmasked payload; fails the test if the client did not mask. */
        fun frame(): Pair<Int, ByteArray> {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            assertTrue("client frames are masked", b1 and 0x80 != 0)
            val length = when (val n = b1 and 0x7f) { 126 -> input.readUnsignedShort(); 127 -> input.readLong().toInt(); else -> n }
            val mask = ByteArray(4).also(input::readFully)
            val payload = ByteArray(length).also(input::readFully)
            return (b0 and 0x0f) to ByteArray(length) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        }

        fun send(opcode: Int, payload: ByteArray, fin: Boolean = true, masked: Boolean = false) {
            output.write((if (fin) 0x80 else 0) or opcode)
            val maskBit = if (masked) 0x80 else 0
            when {
                payload.size < 126 -> output.write(maskBit or payload.size)
                else -> { output.write(maskBit or 126); output.write(payload.size shr 8); output.write(payload.size and 0xff) }
            }
            if (masked) output.write(ByteArray(4))
            output.write(payload)
            output.flush()
        }

        fun text(value: String) = send(0x1, value.toByteArray())
        fun closeFrame(code: Int, reason: String) = send(0x8, byteArrayOf((code shr 8).toByte(), code.toByte()) + reason.toByteArray())

        /** Echoes data frames; answers a close with the same code, as a well-behaved server does. */
        fun echo() {
            while (true) {
                val (opcode, payload) = runCatching { frame() }.getOrNull() ?: return
                serverSaw.add("$opcode:${String(payload)}")
                when (opcode) {
                    0x1, 0x2 -> send(opcode, payload)
                    0x8 -> { send(0x8, payload); socket.close(); return }
                }
            }
        }
    }

    @Before fun start() {
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread { runCatching { script(Peer(socket)) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        grant(Scope(domains = setOf("live.example.com", "intranet.example.com")))
    }

    @After fun stop() { server.close(); database.close(); root.deleteRecursively() }

    private fun grant(scope: Scope, revision: Long = 1) = runBlocking {
        val json = scope.toJson().toString()
        dao.upsertProgram(ProgramRow("p", "行情", "enabled", "1", 0, 0))
        dao.upsertGrant(GrantRow("p", "net.websocket", json, json, true, true, "manifest", 0, null, revision))
        PermissionManager(dao, gate) { 1 }.reload()
    }

    private fun call(args: String, context: CallContext = run): CallResponse = runBlocking {
        pipeline.call(context, CallRequest("r-${++ids}", "net.websocket", Json.parseToJsonElement(args).jsonObject))
    }
    private fun open(url: String = "wss://live.example.com/feed?x=1", context: CallContext = run): String {
        val response = call("""{"op":"open","url":"$url"}""", context)
        assertEquals(response.error?.reason, CallStatus.SUCCEEDED, response.status)
        return response.result!!.jsonObject["socket"]!!.jsonPrimitive.content
    }
    private fun receive(socket: String, waitMs: Int = 2_000) = call("""{"op":"receive","socket":"$socket","waitMs":$waitMs}""").result!!.jsonObject
    private fun texts(result: JsonObject) = result["messages"]!!.jsonArray.map { it.jsonObject["text"]?.jsonPrimitive?.content }

    @Test fun `a granted socket sends, receives replies in order and closes with a handshake`() {
        val socket = open()
        val request = handshakes.poll(2, TimeUnit.SECONDS)!!
        assertEquals("GET /feed?x=1 HTTP/1.1", request["request"])
        assertEquals("live.example.com", request["host"])
        assertEquals(CallStatus.SUCCEEDED, call("""{"op":"send","socket":"$socket","text":"订阅 BTC"}""").status)
        assertEquals(CallStatus.SUCCEEDED, call("""{"op":"send","socket":"$socket","base64":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}"}""").status)
        val received = mutableListOf<JsonObject>()
        while (received.size < 2) received += receive(socket)["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("订阅 BTC", received[0]["text"]!!.jsonPrimitive.content)
        assertEquals("AQID", received[1]["base64"]!!.jsonPrimitive.content)
        val closed = call("""{"op":"close","socket":"$socket","code":1000,"reason":"done"}""").result!!.jsonObject["closed"]!!.jsonObject
        assertEquals(listOf<Any?>(1000, "program"), listOf(closed["code"]!!.jsonPrimitive.int, closed["by"]!!.jsonPrimitive.content))
        assertTrue(serverSaw.toList().any { it.startsWith("8:") })
        val rows = runBlocking { dao.operations("p", 10) }
        assertTrue(rows.all { it.netHost == "live.example.com" && it.netMethod == "WEBSOCKET" })
        assertEquals(1, rows.count { it.netStatus == 101 })
        assertEquals("unknown_socket", call("""{"op":"send","socket":"$socket","text":"late"}""").error!!.reason)
    }

    @Test fun `ungranted hosts, private addresses and plain ws without a grant are refused before connecting`() {
        assertEquals("domain_not_granted", call("""{"op":"open","url":"wss://other.example.com/"}""").error!!.reason)
        assertEquals("address_not_allowed", call("""{"op":"open","url":"wss://intranet.example.com/"}""").error!!.reason)
        assertEquals("http_not_granted", call("""{"op":"open","url":"ws://live.example.com/"}""").error!!.reason)
        assertEquals("only_ws_and_wss", call("""{"op":"open","url":"https://live.example.com/"}""").error!!.reason)
        assertEquals("header_not_allowed: Sec-WebSocket-Key", call("""{"op":"open","url":"wss://live.example.com/","headers":{"Sec-WebSocket-Key":"x"}}""").error!!.reason)
        assertNull(handshakes.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `a peer close is reported once with its code and the socket is then forgotten`() {
        script = { peer -> peer.accept(); peer.text("bye soon"); peer.closeFrame(4001, "maintenance"); peer.frame() }
        val socket = open()
        var result = receive(socket)
        val messages = texts(result).toMutableList()
        while (result["closed"] == null) { result = receive(socket); messages += texts(result) }
        assertEquals(listOf("bye soon"), messages)
        val closed = result["closed"]!!.jsonObject
        assertEquals(listOf<Any?>(4001, "maintenance", "peer"), listOf(closed["code"]!!.jsonPrimitive.int, closed["reason"]!!.jsonPrimitive.content, closed["by"]!!.jsonPrimitive.content))
        assertEquals("unknown_socket", call("""{"op":"receive","socket":"$socket","waitMs":0}""").error!!.reason)
    }

    @Test fun `fragments are joined, pings answered, and broken frames close the socket instead of passing through`() {
        script = { peer ->
            peer.accept()
            peer.send(0x1, "frag".toByteArray(), fin = false); peer.send(0x9, "p".toByteArray()); peer.send(0x0, "ment".toByteArray())
            serverSaw.add("pong:" + peer.frame().let { "${it.first}:${String(it.second)}" })
            peer.send(0x1, "masked".toByteArray(), masked = true)
            serverSaw.add("close:" + peer.frame().let { "${it.first}:${((it.second[0].toInt() and 0xff) shl 8) or (it.second[1].toInt() and 0xff)}" })
        }
        val socket = open()
        var result = receive(socket)
        val messages = texts(result).toMutableList()
        while (result["closed"] == null) { result = receive(socket); messages += texts(result) }
        assertEquals(listOf("fragment"), messages)
        assertEquals(listOf<Any?>(1002, "server_frame_masked", "mobby"), result["closed"]!!.jsonObject.let { listOf(it["code"]!!.jsonPrimitive.int, it["reason"]!!.jsonPrimitive.content, it["by"]!!.jsonPrimitive.content) })
        assertEquals("pong:10:p", serverSaw.poll(2, TimeUnit.SECONDS))
        assertEquals("close:8:1002", serverSaw.poll(2, TimeUnit.SECONDS))

        script = { peer -> peer.accept(); peer.send(0x1, byteArrayOf(0xC3.toByte(), 0x28)); peer.frame() }
        val bad = open()
        var invalid = receive(bad)
        while (invalid["closed"] == null) invalid = receive(bad)
        assertEquals(1007, invalid["closed"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun `a program that falls behind is told its socket closed rather than losing messages`() {
        script = { peer -> peer.accept(); repeat(5) { peer.text("tick $it") }; peer.frame() }
        val socket = open()
        Thread.sleep(500)
        var result = receive(socket, 0)
        val messages = texts(result).toMutableList()
        while (result["closed"] == null) { result = receive(socket); messages += texts(result) }
        assertEquals(listOf("tick 0", "tick 1", "tick 2"), messages)
        assertEquals("receive_buffer_full", result["closed"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test fun `refused and forged handshakes fail and nothing stays open`() {
        script = { peer -> peer.accept(status = 403) }
        assertEquals("handshake_status_403", call("""{"op":"open","url":"wss://live.example.com/"}""").error!!.reason)
        script = { peer -> peer.accept(accept = "forged") }
        assertEquals("handshake_accept_mismatch", call("""{"op":"open","url":"wss://live.example.com/"}""").error!!.reason)
        script = { peer -> peer.accept(extra = "Sec-WebSocket-Extensions: permessage-deflate\r\n") }
        assertEquals("handshake_unrequested_extension", call("""{"op":"open","url":"wss://live.example.com/"}""").error!!.reason)
        assertEquals(0, sockets.openCount(run.caller))
    }

    // The first run of these tests hung forever: the TLS handshake had no read timeout against a silent server.
    @Test fun `a server that never answers the TLS or upgrade handshake times out instead of holding the call`() {
        script = { peer -> Thread.sleep(5_000); peer.toString() }
        val tls = NetWebSocketCapability(secrets, LocalTransport(), limits)
        val started = System.currentTimeMillis()
        val failure = runCatching { runBlocking { tls.invoke(CapabilityCall(run, Json.parseToJsonElement("""{"op":"open","url":"wss://live.example.com/"}""").jsonObject,
            ProgramSpace("p", root, root), isCancelled = { false })) } }.exceptionOrNull() as CapabilityException
        assertEquals(listOf<Any>(ProgramErrorCode.TIMEOUT, "network_timeout"), listOf(failure.code, failure.reason))
        assertEquals(TIMEOUT_REASON, call("""{"op":"open","url":"wss://live.example.com/"}""").error!!.reason)
        assertTrue(System.currentTimeMillis() - started < 4_000)
    }

    @Test fun `sockets belong to their run or page, end with it, and close when the grant goes away`() {
        val page = CallContext("p", "1", Surface.UI, uiSessionId = "page")
        val mine = open()
        assertEquals("unknown_socket", call("""{"op":"send","socket":"$mine","text":"x"}""", page).error!!.reason)
        open()
        assertEquals("too_many_open_sockets", call("""{"op":"open","url":"wss://live.example.com/"}""").error!!.reason)
        val pageSocket = open(context = page)
        sockets.release(run.caller)
        assertEquals(0, sockets.openCount(run.caller))
        assertEquals("unknown_socket", call("""{"op":"receive","socket":"$mine","waitMs":0}""").error!!.reason)
        // Narrowing the grant to another host closes the page's socket on the next recheck.
        grant(Scope(domains = setOf("other.example.com")), revision = 2)
        sockets.recheck { caller, scope -> gate.check(caller, sockets.descriptor, scope) is com.github.ytlog.mobby.android.programs.permission.GateDecision.Allowed }
        assertEquals(0, sockets.openCount(page.caller))
        assertTrue(generateSequence { serverSaw.poll(2, TimeUnit.SECONDS) }.take(4).any { it.startsWith("8:") })
        assertNotNull(pageSocket)
    }

    @Test fun `secrets fill headers only for their bound host`() = runBlocking {
        secrets.put("p", "token", "s3cret", setOf("live.example.com"))
        val response = call("""{"op":"open","url":"wss://live.example.com/","headers":{"Authorization":"Bearer {{secret:token}}"}}""")
        assertEquals(CallStatus.SUCCEEDED, response.status)
        assertEquals("Bearer s3cret", handshakes.poll(2, TimeUnit.SECONDS)!!["authorization"])
        val ws = Scope(domains = setOf("live.example.com", "other.example.com"))
        grant(ws, revision = 3)
        secrets.put("p", "other", "nope", setOf("other.example.com"))
        assertEquals("secret_not_bound_to_host",
            call("""{"op":"open","url":"wss://live.example.com/","headers":{"X-Key":"{{secret:other}}"}}""").error!!.reason)
    }

    private companion object { const val TIMEOUT_REASON = "network_timeout" }
}
