package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.call.*
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.*
import com.github.ytlog.mobby.android.programs.program.ProgramLayout
import com.github.ytlog.mobby.android.programs.store.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Handler whose behavior each test sets. */
internal class ScriptedHandler(
    id: String, kind: CapabilityKind = CapabilityKind.COMMAND, scope: ScopeKind = ScopeKind.NONE,
    prerequisites: List<Prerequisite> = emptyList(), timeout: Long = 1_000,
    schema: JsonObject = buildJsonObject { put("type", "object"); put("additionalProperties", false)
        put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string"); put("maxLength", 10) }) }) },
) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor(id, 1, kind, schema, JsonObject(emptyMap()), scope, emptySet(), prerequisites,
        setOf(Surface.BACKGROUND, Surface.UI), timeout, true, CapabilityLimits(200, 60))
    var behavior: suspend (CapabilityCall) -> CapabilityOutcome = { CapabilityOutcome(Effect.CONFIRMED, JsonPrimitive("done")) }
    var scopeOf: (JsonObject) -> CallScope = { CallScope.NONE }
    val calls = mutableListOf<CapabilityCall>()
    override fun availability(): Availability = Availability.Available
    override fun callScope(args: JsonObject) = scopeOf(args)
    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome { calls += call; return behavior(call) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CallPipelineTest {
    private val root = Files.createTempDirectory("pipeline").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private var onInsert: () -> Unit = {}
    private val dao = object : ProgramsDao by database.dao() {
        override suspend fun insertOperation(row: OperationRow) { database.dao().insertOperation(row); onInsert() }
    }
    private val write = ScriptedHandler("notes.write")
    private val query = ScriptedHandler("notes.read", CapabilityKind.QUERY)
    private val sms = ScriptedHandler("sms.list", CapabilityKind.QUERY, prerequisites = listOf(Prerequisite("device-plugin", "sms")))
    private val local = ScriptedHandler("storage.kv", prerequisites = listOf(Prerequisite.PROGRAM_SPACE))
    private val net = ScriptedHandler("net.http", scope = ScopeKind.DOMAINS, schema = buildJsonObject { put("type", "object") })
    private val registry = CapabilityRegistry(listOf(write, query, sms, local, net))
    private val gate = PermissionGate(registry) { 10 }
    private val layout = ProgramLayout(File(root, "programs"))
    private var ids = 0
    private val pipeline = CallPipeline(registry, gate, dao, layout, { 10 }) { "op-${++ids}" }
    private val run = CallContext("p", "1", Surface.BACKGROUND, runId = "r1")

    @After fun close() { database.close(); root.deleteRecursively() }

    private fun grant(capability: String, scope: Scope = Scope.NONE, revision: Long = 1) = Grant("p", capability, scope, scope, true, true, null, revision)
    private fun allow(vararg capabilities: String) { gate.snapshot = PermissionSnapshot(capabilities.map { grant(it) }, emptyMap()) }
    private fun call(capability: String, args: String = "{}", id: String = "r-1", context: CallContext = run, deadline: Long? = null) =
        runBlocking { pipeline.call(context, CallRequest(id, capability, Json.parseToJsonElement(args).jsonObject, deadline)) }
    private fun stored(id: String = "r-1", caller: String = run.caller) = runBlocking { database.dao().operation(caller, id) }

    @Test fun `a successful call is recorded before dispatch and replayed without its result`() {
        allow("notes.write")
        write.behavior = { call ->
            val row = stored(caller = call.context.caller)!!
            assertEquals("accepted", row.status); assertNotNull(row.dispatchedAt)
            CapabilityOutcome(Effect.CONFIRMED, JsonPrimitive("saved ${call.args["text"]!!.jsonPrimitive.content}"))
        }
        val first = call("notes.write", """{"text":"hi"}""")
        assertEquals(CallStatus.SUCCEEDED, first.status)
        assertEquals(JsonPrimitive("saved hi"), first.result)
        assertEquals("succeeded", stored()!!.status)
        val again = call("notes.write", """{ "text" : "hi" }""")
        assertEquals(first.operationId, again.operationId)
        assertNull(again.result)
        assertEquals(1, write.calls.size)
        assertEquals(ProgramErrorCode.REQUEST_CONFLICT, call("notes.write", """{"text":"other"}""").error!!.code)
        // Another run may reuse the same request ID.
        assertEquals(CallStatus.SUCCEEDED, call("notes.write", """{"text":"hi"}""", context = run.copy(runId = "r2")).status)
    }

    @Test fun `malformed calls are refused before any record or dispatch`() {
        allow("notes.write")
        assertEquals(ProgramErrorCode.INVALID_ARGUMENT, call("notes.write", id = "bad id").error!!.code)
        assertEquals(ProgramErrorCode.UNSUPPORTED, call("camera.photo").error!!.code)
        assertEquals("$.extra is not allowed", call("notes.write", """{"extra":1}""").error!!.reason)
        assertEquals("$.text is longer than 10", call("notes.write", """{"text":"01234567890"}""").error!!.reason)
        assertEquals("arguments_too_large", call("notes.write", """{"text":"${"x".repeat(300)}"}""").error!!.reason)
        assertNull(stored())
        assertTrue(write.calls.isEmpty())
    }

    @Test fun `denied calls are recorded with their layer and never dispatched`() {
        gate.snapshot = PermissionSnapshot(listOf(grant("net.http", Scope(domains = setOf("api.example.com")))), emptyMap())
        net.scopeOf = { CallScope(host = it["host"]!!.jsonPrimitive.content) }
        val denied = call("net.http", """{"host":"evil.example.com"}""")
        assertEquals(CallError(ProgramErrorCode.SCOPE_DENIED, "program", "domain_not_granted"), denied.error)
        assertEquals("failed", stored()!!.status)
        assertEquals(ProgramErrorCode.PERMISSION_DENIED, call("notes.write", id = "r-2").error!!.code)
        assertTrue(net.calls.isEmpty() && write.calls.isEmpty())
    }

    @Test fun `a grant revoked after admission stops the dispatch`() {
        allow("notes.write")
        onInsert = { gate.snapshot = PermissionSnapshot(listOf(grant("notes.write").copy(enabled = false, revision = 2)), emptyMap()) }
        val response = call("notes.write")
        assertEquals(CallError(ProgramErrorCode.PERMISSION_DENIED, "program", "program_off"), response.error)
        assertTrue(write.calls.isEmpty())
        assertNull(stored()!!.dispatchedAt)
    }

    @Test fun `failures keep what is known about side effects`() {
        allow("notes.write", "notes.read")
        write.behavior = { throw CapabilityException(ProgramErrorCode.EFFECT_UNKNOWN, "radio", Effect.UNKNOWN) }
        call("notes.write").let { assertEquals(CallStatus.UNKNOWN, it.status); assertEquals(Effect.UNKNOWN, it.effect) }
        write.behavior = { delay(5_000); error("unreachable") }
        call("notes.write", id = "r-2", deadline = 50).let { assertEquals(CallStatus.TIMEOUT, it.status); assertEquals(Effect.UNKNOWN, it.effect) }
        query.behavior = { delay(5_000); error("unreachable") }
        call("notes.read", id = "r-3", deadline = 50).let { assertEquals(CallStatus.TIMEOUT, it.status); assertEquals(Effect.NONE, it.effect) }
        assertEquals("timeout", stored("r-3")!!.status)
    }

    @Test fun `dry runs simulate effects, gate device queries and isolate program storage`() {
        allow("notes.write", "sms.list", "storage.kv")
        val dry = run.copy(runId = "dry-1", dryRun = DryRunScope(allowedQueries = emptySet(), requested = listOf("notes.write", "sms.list", "storage.kv").associateWith { Scope.NONE }))
        assertEquals(CallStatus.SIMULATED, call("notes.write", context = dry).status)
        assertEquals(CallStatus.SIMULATED, call("sms.list", id = "r-2", context = dry).status)
        assertTrue(write.calls.isEmpty() && sms.calls.isEmpty())
        assertEquals(CallStatus.SUCCEEDED, call("sms.list", id = "r-3", context = dry.copy(dryRun = DryRunScope(setOf("sms.list"), mapOf("sms.list" to Scope.NONE)))).status)
        assertEquals(CallStatus.SUCCEEDED, call("storage.kv", id = "r-4", context = dry).status)
        assertTrue(local.calls.single().space.files.path.contains("dry-run/dry-1"))
        call("storage.kv", id = "r-5")
        assertEquals(layout.files("p"), local.calls.last().space.files)
    }
}
