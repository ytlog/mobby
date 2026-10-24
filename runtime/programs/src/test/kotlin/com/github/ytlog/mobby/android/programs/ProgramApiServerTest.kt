package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.api.ProgramApiServer
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.CallPipeline
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.*
import com.github.ytlog.mobby.android.programs.program.ProgramLayout
import com.github.ytlog.mobby.android.programs.store.GrantRow
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
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProgramApiServerTest {
    private val root = Files.createTempDirectory("api").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val note = ScriptedHandler("notes.write")
    private val net = ScriptedHandler("net.http", scope = ScopeKind.DOMAINS)
    private val registry = CapabilityRegistry(listOf(note, net))
    private val gate = PermissionGate(registry) { 10 }
    private val permissions = PermissionManager(database.dao(), gate) { 10 }
    private val pipeline = CallPipeline(registry, gate, database.dao(), ProgramLayout(File(root, "programs")), { 10 })
    private val server = ProgramApiServer(pipeline, registry, gate, permissions, { token ->
        if (token == "good") CallContext("p", "1", Surface.BACKGROUND, runId = "r") else null
    })
    private var port = 0

    @Before fun start() = runBlocking {
        val scope = Scope(domains = setOf("api.example.com")).toJson().toString()
        database.dao().upsertGrant(GrantRow("p", "notes.write", "{}", "{}", true, true, "manifest", 0, null, 3))
        database.dao().upsertGrant(GrantRow("p", "net.http", scope, scope, false, false, "manifest", 0, null, 1))
        permissions.reload()
        port = server.start()
    }

    @After fun stop() { server.stop(); database.close(); root.deleteRecursively() }

    private fun request(method: String, path: String, body: String? = null, token: String? = "good"): Pair<Int, JsonObject> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
        val code = connection.responseCode
        val text = (if (code < 400) connection.inputStream else connection.errorStream).use { it.readBytes().decodeToString() }
        return code to Json.parseToJsonElement(text).jsonObject
    }

    private fun operation(requestId: String = "r-1", capability: String = "notes.write", args: String = "{}") =
        """{"schema":"mobby.program-api/1","requestId":"$requestId","capability":"$capability","args":$args}"""

    @Test fun `server binds to loopback only`() {
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isSuccess)
    }

    @Test fun `credentials, browser origins and non-loopback hosts are refused`() {
        assertEquals(401, request("POST", "/v1/operations", operation(), token = null).first)
        assertEquals(401, request("POST", "/v1/operations", operation(), token = "stolen").first)
        // HttpURLConnection never sends Origin or a custom Host, so these go over a raw socket.
        fun raw(headers: String) = Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET /v1/permissions HTTP/1.1\r\n${headers}Authorization: Bearer good\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes().decodeToString().lineSequence().first()
        }
        assertEquals("HTTP/1.1 200 OK", raw("Host: 127.0.0.1:$port\r\n"))
        assertEquals("HTTP/1.1 403 Forbidden", raw("Host: 127.0.0.1:$port\r\nOrigin: https://evil.example\r\n"))
        assertEquals("HTTP/1.1 403 Forbidden", raw("Host: attacker.example:$port\r\n"))
    }

    @Test fun `operations run through the pipeline and can be looked up again`() {
        val (code, body) = request("POST", "/v1/operations", operation())
        assertEquals(200, code)
        assertEquals("succeeded", body["status"]!!.jsonPrimitive.content)
        assertEquals("confirmed", body["effect"]!!.jsonPrimitive.content)
        val (_, found) = request("GET", "/v1/operations/r-1")
        assertEquals(body["operationId"], found["operationId"])
        assertEquals(404, request("GET", "/v1/operations/missing").first)
        val denied = request("POST", "/v1/operations", operation("r-2", "net.http")).second
        assertEquals("PERMISSION_DENIED", denied["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun `malformed bodies are rejected with an explanation`() {
        assertEquals(400, request("POST", "/v1/operations", "not json").first)
        assertEquals(400, request("POST", "/v1/operations", operation().replace("}", """,\"programId\":\"other\"}""").removeSuffix("}") + "}").first)
        assertEquals(400, request("POST", "/v1/operations", operation().replace("mobby.program-api/1", "mobby.program-api/2")).first)
        assertEquals(413, request("POST", "/v1/operations", "{\"x\":\"${"y".repeat(1_100_000)}\"}").first)
    }

    @Test fun `programs see only their own permissions and can ask for declared ones`() {
        val permissionsBody = request("GET", "/v1/permissions").second["permissions"]!!.jsonArray
        assertEquals(listOf("net.http" to "program_off", "notes.write" to "granted"),
            permissionsBody.map { it.jsonObject["capability"]!!.jsonPrimitive.content to it.jsonObject["state"]!!.jsonPrimitive.content })
        val capabilities = request("GET", "/v1/capabilities").second["capabilities"]!!.jsonArray
        assertEquals(setOf("net.http", "notes.write"), capabilities.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
        val asked = request("POST", "/v1/permissions/requests", """{"capability":"net.http","scope":{"domains":["api.example.com"]}}""")
        assertEquals(200 to "pending", asked.first to asked.second["state"]!!.jsonPrimitive.content)
        assertEquals(400, request("POST", "/v1/permissions/requests", """{"capability":"net.http","scope":{"domains":["more.example.com"]}}""").first)
        assertEquals(400, request("POST", "/v1/permissions/requests", """{"capability":"camera.photo"}""").first)
    }
}
