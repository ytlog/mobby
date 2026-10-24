package com.github.ytlog.mobby.android.programs

import android.app.NotificationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.base.config.ConfigCapability
import com.github.ytlog.mobby.android.programs.base.config.ConfigStore
import com.github.ytlog.mobby.android.programs.base.log.LogCapability
import com.github.ytlog.mobby.android.programs.base.net.NetHttpCapability
import com.github.ytlog.mobby.android.programs.base.net.NetLimits
import com.github.ytlog.mobby.android.programs.base.net.NetTransport
import com.github.ytlog.mobby.android.programs.base.notify.NotifyCapability
import com.github.ytlog.mobby.android.programs.base.secrets.SecretCipher
import com.github.ytlog.mobby.android.programs.base.secrets.SecretStore
import com.github.ytlog.mobby.android.programs.base.secrets.SecretsCapability
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.CallPipeline
import com.github.ytlog.mobby.android.programs.call.CallRequest
import com.github.ytlog.mobby.android.programs.call.CallStatus
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.*
import com.github.ytlog.mobby.android.programs.program.ProgramLayout
import com.github.ytlog.mobby.android.programs.store.GrantRow
import com.github.ytlog.mobby.android.programs.store.ProgramRow
import com.github.ytlog.mobby.android.programs.store.ProgramsDatabase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NetAndSecretsTest {
    private val root = Files.createTempDirectory("net").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.dao()
    private val seen = mutableListOf<Pair<String, Map<String, String>>>()
    private var port = 0
    private lateinit var server: ApplicationEngine

    /** Plaintext cipher: the store's rules are under test here, not Android Keystore. */
    private object PlainCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray() to byteArrayOf(1)
        override fun decrypt(ciphertext: ByteArray, iv: ByteArray) = ciphertext.reversedArray()
    }

    /** Hosts below map to the local test server; every other name resolves to its real address check. */
    private val transport = object : NetTransport {
        val routes = mapOf("api.example.com" to "93.184.216.34", "cdn.example.com" to "93.184.216.35", "evil.example.org" to "93.184.216.36", "intranet.example.com" to "192.168.1.10")
        override fun resolve(host: String) = listOf(InetAddress.getByName(routes[host] ?: error("unexpected host $host")))
        override fun open(url: URL): HttpURLConnection {
            val local = URL("http", "127.0.0.1", port, url.file)
            return (local.openConnection() as HttpURLConnection).also { it.setRequestProperty("X-Test-Host", url.host) }
        }
    }
    private val secrets = SecretStore(dao, PlainCipher) { 1 }
    private val net = NetHttpCapability(secrets, transport, NetLimits(inlineResponseBytes = 64, downloadBytes = 1_000))
    private val registry = CapabilityRegistry(listOf(net, SecretsCapability(secrets), ConfigCapability(ConfigStore(dao) { 1 }), LogCapability(dao, { 1 }, keep = 2)))
    private val gate = PermissionGate(registry) { 1 }
    private val pipeline = CallPipeline(registry, gate, dao, ProgramLayout(File(root, "programs")), { 1 })
    private val context = CallContext("p", "1", Surface.BACKGROUND, runId = "r")
    private var ids = 0

    @Before fun start() {
        server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing { route("{...}") { handle {
            val request = call.request
            seen += (request.headers["X-Test-Host"] + request.uri) to request.headers.entries().associate { it.key to it.value.first() }
            val (status, headers, body) = when (request.path()) {
                "/price" -> Triple(200, mapOf("Content-Type" to "application/json"), """{"price":98}""".toByteArray())
                "/to-cdn" -> Triple(302, mapOf("Location" to "http://cdn.example.com/price"), ByteArray(0))
                "/to-evil" -> Triple(302, mapOf("Location" to "http://evil.example.org/collect"), ByteArray(0))
                "/big" -> Triple(200, emptyMap(), "x".repeat(200).toByteArray())
                "/huge" -> Triple(200, emptyMap(), "x".repeat(2_000).toByteArray())
                "/binary" -> Triple(200, emptyMap(), byteArrayOf(0xC3.toByte(), 0x28))
                else -> Triple(404, emptyMap(), "missing".toByteArray())
            }
            headers.forEach { (k, v) -> call.response.headers.append(k, v, safeOnly = false) }
            call.respondBytes(body, status = HttpStatusCode.fromValue(status))
            } } }
        }.start(wait = false)
        port = runBlocking { server.resolvedConnectors().first().port }
        grantNet()
    }

    private fun grantNet() = runBlocking {
        val scope = Scope(domains = setOf("api.example.com", "cdn.example.com", "intranet.example.com"), insecureHttp = true).toJson().toString()
        dao.upsertProgram(ProgramRow("p", "价格提醒", "enabled", "1", 0, 0))
        dao.upsertGrant(GrantRow("p", "net.http", scope, scope, true, true, "manifest", 0, null, 1))
        PermissionManager(dao, gate) { 1 }.reload()
    }

    @After fun stop() { server.stop(0, 0); database.close(); root.deleteRecursively() }

    private fun call(capability: String, args: String) = runBlocking {
        pipeline.call(context, CallRequest("r-${++ids}", capability, Json.parseToJsonElement(args).jsonObject))
    }
    private fun fetch(args: String) = call("net.http", args)

    @Test fun `granted requests go out and are audited without bodies`() = runBlocking {
        val response = fetch("""{"url":"http://api.example.com/price"}""")
        assertEquals(CallStatus.SUCCEEDED, response.status)
        val result = response.result!!.jsonObject
        assertEquals(200, result["status"]!!.jsonPrimitive.int)
        assertEquals("""{"price":98}""", result["body"]!!.jsonPrimitive.content)
        val row = dao.operations("p", 1).single()
        assertEquals(listOf<Any?>("api.example.com", "GET", 200, 12L), listOf(row.netHost, row.netMethod, row.netStatus, row.netBytesIn))
    }

    @Test fun `ungranted hosts, private addresses and plain http without a grant are refused`() = runBlocking {
        assertEquals("domain_not_granted", fetch("""{"url":"http://evil.example.org/x"}""").error!!.reason)
        assertEquals("address_not_allowed", fetch("""{"url":"http://intranet.example.com/x"}""").error!!.reason)
        val https = Scope(domains = setOf("api.example.com")).toJson().toString()
        dao.upsertGrant(GrantRow("p", "net.http", https, https, true, true, "manifest", 0, null, 2))
        PermissionManager(dao, gate) { 1 }.reload()
        assertEquals("http_not_granted", fetch("""{"url":"http://api.example.com/price"}""").error!!.reason)
        assertTrue(seen.isEmpty())
    }

    @Test fun `every redirect hop is rechecked`() {
        val followed = fetch("""{"url":"http://api.example.com/to-cdn"}""")
        assertEquals("""{"price":98}""", followed.result!!.jsonObject["body"]!!.jsonPrimitive.content)
        val stopped = fetch("""{"url":"http://api.example.com/to-evil"}""")
        assertEquals(ProgramErrorCode.SCOPE_DENIED, stopped.error!!.code)
        assertEquals(listOf("api.example.com/to-cdn", "cdn.example.com/price", "api.example.com/to-evil"), seen.map { it.first })
    }

    @Test fun `secrets are filled in only for their bound hosts and never readable`() = runBlocking {
        assertEquals(CallStatus.SUCCEEDED, call("secrets", """{"op":"put","name":"token","value":"s3cr3t","domains":["api.example.com"]}""").status)
        assertEquals(ProgramErrorCode.SCOPE_DENIED, call("secrets", """{"op":"put","name":"other","value":"x","domains":["evil.example.org"]}""").error!!.code)
        fetch("""{"url":"http://api.example.com/price?key={{secret:token}}","headers":{"Authorization":"Bearer {{secret:token}}"}}""")
        assertEquals("Bearer s3cr3t", seen.last().second["Authorization"])
        assertEquals("api.example.com/price?key=s3cr3t", seen.last().first)
        val leaked = fetch("""{"url":"http://cdn.example.com/price","headers":{"Authorization":"{{secret:token}}"}}""")
        assertEquals("secret_not_bound_to_host", leaked.error!!.reason)
        val listed = call("secrets", """{"op":"list"}""").result!!.jsonArray.single().jsonObject
        assertEquals(setOf("name", "domains", "updatedAt"), listed.keys)
        assertFalse(dao.operations("p", 50).any { it.errorReason?.contains("s3cr3t") == true })
    }

    @Test fun `large or binary responses become files and oversized ones fail`() {
        val big = fetch("""{"url":"http://api.example.com/big"}""").result!!.jsonObject
        assertNull(big["body"])
        val file = File(root, "programs/data/p/" + big["file"]!!.jsonPrimitive.content)
        assertEquals(200L, file.length())
        assertTrue(fetch("""{"url":"http://api.example.com/binary"}""").result!!.jsonObject["file"] != null)
        assertEquals(ProgramErrorCode.QUOTA_EXCEEDED, fetch("""{"url":"http://api.example.com/huge"}""").error!!.code)
    }

    @Test fun `unsafe headers and bodies on GET are rejected`() {
        assertEquals(ProgramErrorCode.INVALID_ARGUMENT, fetch("""{"url":"http://api.example.com/price","headers":{"Host":"x"}}""").error!!.code)
        assertEquals(ProgramErrorCode.INVALID_ARGUMENT, fetch("""{"url":"http://api.example.com/price","headers":{"X-A":"a\r\nX-B: b"}}""").error!!.code)
        assertEquals(ProgramErrorCode.INVALID_ARGUMENT, fetch("""{"url":"http://api.example.com/price","body":"x"}""").error!!.code)
        assertEquals(ProgramErrorCode.INVALID_ARGUMENT, fetch("""{"url":"file:///etc/hosts"}""").error!!.code)
        assertEquals(ProgramErrorCode.INVALID_ARGUMENT, fetch("""{"url":"http://user:pass@api.example.com/"}""").error!!.code)
    }

    @Test fun `config and log need no grant and logs are bounded`() = runBlocking {
        assertEquals(CallStatus.SUCCEEDED, call("config", "{}").status)
        repeat(3) { call("log", """{"message":"m$it","data":{"long":"${"x".repeat(5_000)}"}}""") }
        val logs = dao.logs("p", 10)
        assertEquals(listOf("m2", "m1"), logs.map { it.message })
        assertTrue(logs.first().dataJson!!.endsWith("…truncated"))
    }

    @Test fun `calls beyond the per-minute limit are refused`() {
        repeat(60) { call("log", """{"message":"x"}""") }
        assertEquals("rate_limited", call("log", """{"message":"x"}""").error!!.reason)
    }

    @Test fun `notifications name the program and need a UI to open one`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notify = NotifyCapability(app, dao) { null }
        val outcome = notify.invoke(CapabilityCall(context, Json.parseToJsonElement("""{"title":"低于阈值","body":"98"}""").jsonObject,
            ProgramSpace("p", root, root)) { false })
        assertEquals(Effect.SUBMITTED, outcome.effect)
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("价格提醒 · 低于阈值", posted.extras.getString("android.title"))
        val noUi = runCatching { notify.invoke(CapabilityCall(context, Json.parseToJsonElement("""{"title":"x","openUi":true}""").jsonObject, ProgramSpace("p", root, root)) { false }) }
        assertEquals("program_has_no_ui", (noUi.exceptionOrNull() as CapabilityException).reason)
    }
}
