package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.base.config.ConfigStore
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.DryRunScope
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.*
import com.github.ytlog.mobby.android.programs.program.*
import com.github.ytlog.mobby.android.programs.store.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PermissionTest {
    private var now = 1_000L
    private var smsAvailable: Availability = Availability.Available
    private val sms = object : FakeHandler("sms.list", CapabilityKind.QUERY) { override fun availability() = smsAvailable }
    private val handoff = FakeHandler("files.pick", CapabilityKind.HANDOFF).let { base ->
        object : CapabilityHandler by base { override val descriptor = base.descriptor.copy(surfaces = setOf(Surface.UI)) }
    }
    private val registry = CapabilityRegistry(listOf(sms, handoff, FakeHandler("net.http", scope = ScopeKind.DOMAINS),
        FakeHandler("screen.click", scope = ScopeKind.PACKAGES), FakeHandler("ai.call", scope = ScopeKind.MODELS)))
    private val gate = PermissionGate(registry) { now }
    private val background = CallContext("p", "1", Surface.BACKGROUND, runId = "r")
    private val root = Files.createTempDirectory("perm").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.dao()

    @After fun close() { database.close(); root.deleteRecursively() }

    private fun grant(capability: String, scope: Scope = Scope.NONE, enabled: Boolean = true, expiresAt: Long? = null, revision: Long = 1) =
        Grant("p", capability, scope, scope, enabled, true, expiresAt, revision)
    private fun use(vararg grants: Grant, switches: Map<String, Boolean> = emptyMap()) { gate.snapshot = PermissionSnapshot(grants.toList(), switches) }
    private fun check(id: String, scope: CallScope = CallScope.NONE, context: CallContext = background) = gate.check(context, registry[id]!!.descriptor, scope)
    private fun denied(layer: Layer, reason: DenyReason) = GateDecision.Denied(layer, reason)

    @Test fun `layers are checked in order and the first failing one is reported`() {
        use(grant("sms.list", enabled = false), switches = mapOf("sms.list" to false))
        smsAvailable = Availability.Unavailable(AvailabilityReason.SYSTEM_PERMISSION_MISSING)
        assertEquals(denied(Layer.SYSTEM, DenyReason.SYSTEM_PERMISSION_MISSING), check("sms.list"))
        smsAvailable = Availability.Available
        assertEquals(denied(Layer.GLOBAL, DenyReason.GLOBAL_OFF), check("sms.list"))
        use(grant("sms.list", enabled = false))
        assertEquals(denied(Layer.PROGRAM, DenyReason.PROGRAM_OFF), check("sms.list"))
        use()
        assertEquals(denied(Layer.PROGRAM, DenyReason.NOT_REQUESTED), check("sms.list"))
        use(grant("sms.list", expiresAt = 1_000))
        assertEquals(denied(Layer.PROGRAM, DenyReason.EXPIRED), check("sms.list"))
        use(grant("sms.list", revision = 7))
        assertEquals(GateDecision.Allowed(7), check("sms.list"))
        assertEquals(ProgramErrorCode.PERMISSION_DENIED, denied(Layer.GLOBAL, DenyReason.GLOBAL_OFF).code)
        assertEquals(ProgramErrorCode.SCOPE_DENIED, denied(Layer.PROGRAM, DenyReason.DOMAIN_NOT_GRANTED).code)
    }

    @Test fun `scope checks cover packages, domains, methods, plain http and models`() {
        use(grant("net.http", Scope(domains = setOf("*.example.com"), methods = setOf("GET"))),
            grant("screen.click", Scope(packages = setOf("com.example.app"))), grant("ai.call", Scope(models = setOf("gpt-x"))))
        assertTrue(check("net.http", CallScope(host = "api.example.com", method = "GET")) is GateDecision.Allowed)
        assertEquals(denied(Layer.PROGRAM, DenyReason.DOMAIN_NOT_GRANTED), check("net.http", CallScope(host = "example.org", method = "GET")))
        assertEquals(denied(Layer.PROGRAM, DenyReason.METHOD_NOT_GRANTED), check("net.http", CallScope(host = "api.example.com", method = "POST")))
        assertEquals(denied(Layer.PROGRAM, DenyReason.HTTP_NOT_GRANTED), check("net.http", CallScope(host = "api.example.com", method = "GET", insecureHttp = true)))
        assertEquals(denied(Layer.PROGRAM, DenyReason.DOMAIN_NOT_GRANTED), check("net.http"))
        assertEquals(denied(Layer.PROGRAM, DenyReason.PACKAGE_NOT_GRANTED), check("screen.click", CallScope(packageName = "com.other")))
        assertEquals(denied(Layer.PROGRAM, DenyReason.MODEL_NOT_GRANTED), check("ai.call", CallScope(model = "other")))
    }

    @Test fun `handoffs need the ui and dry runs cannot hand off`() {
        use(grant("files.pick"))
        assertEquals(denied(Layer.CONTEXT, DenyReason.SURFACE_NOT_ALLOWED), check("files.pick"))
        val ui = CallContext("p", "1", Surface.UI, uiSessionId = "u")
        assertTrue(check("files.pick", context = ui) is GateDecision.Allowed)
        assertEquals(denied(Layer.CONTEXT, DenyReason.NOT_ALLOWED_IN_DRY_RUN), check("files.pick", context = ui.copy(dryRun = DryRunScope(emptyMap()))))
    }

    @Test fun `user changes only narrow grants and always bump the revision`() = runBlocking {
        val manager = PermissionManager(dao, gate) { now }
        val requested = Scope(domains = setOf("a.example.com", "b.example.com"))
        dao.upsertGrant(GrantRow("p", "net.http", requested.toJson().toString(), requested.toJson().toString(), false, true, "manifest", 0, null, 1))
        manager.reload()
        assertFalse(gate.snapshot.grant("p", "net.http")!!.enabled)
        assertTrue(manager.setGrant("p", "net.http", true, Scope(domains = setOf("a.example.com")), expiresAt = 5_000))
        val after = gate.snapshot.grant("p", "net.http")!!
        assertEquals(setOf("a.example.com"), after.granted.domains)
        assertEquals(2L, after.revision)
        assertFalse(gate.stillCurrent("p", "net.http", 1))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.setGrant("p", "net.http", true, Scope(domains = setOf("c.example.com")), null) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.setGrant("p", "net.http", true, null, expiresAt = 10) } }
        assertFalse(manager.setGrant("p", "sms.list", true, null, null))
        manager.setSwitch("net.http", false)
        assertFalse(gate.snapshot.switchOn("net.http"))
        assertEquals(5_000L, gate.snapshot.nextExpiry)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.request("p", "net.http", Scope(domains = setOf("c.example.com"))) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.request("p", "sms.list", Scope.NONE) } }
        manager.request("p", "net.http", Scope(domains = setOf("b.example.com")))
        Unit
    }

    @Test fun `config values are typed, defaulted and required fields are reported`() = runBlocking {
        val installer = ProgramInstaller(database, ProgramLayout(File(root, "programs")), ProgramPackageReader(fixtureRegistry())) { now }
        installer.install(PackageBuilder(File(root, "pkg")).apply {
            config = """[{"key":"threshold","type":"number","required":true,"label":"阈值"},
                {"key":"mode","type":"enum","required":false,"label":"模式","options":["fast","slow"],"default":"slow"},
                {"key":"apiToken","type":"secret","required":true,"label":"令牌","domains":["api.example.com"]}]"""
        }.write())
        val config = ConfigStore(dao) { now }
        assertEquals(listOf("threshold", "apiToken"), config.missingRequired("price-watch"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { config.set("price-watch", "threshold", JsonPrimitive("100")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { config.set("price-watch", "mode", JsonPrimitive("medium")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { config.set("price-watch", "apiToken", JsonPrimitive("x")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { config.set("price-watch", "other", JsonPrimitive(1)) } }
        config.set("price-watch", "threshold", JsonPrimitive(100))
        dao.upsertSecret(SecretRow("price-watch", "apiToken", byteArrayOf(1), byteArrayOf(1), "api.example.com", 0, 0))
        assertTrue(config.missingRequired("price-watch").isEmpty())
        val values = config.values("price-watch")
        assertEquals(100, values["threshold"]!!.jsonPrimitive.int)
        assertEquals("slow", values["mode"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { put("secret", "apiToken"); put("set", true) }, values["apiToken"])
    }

    @Test fun `availability puts permission causes before configuration`() {
        val granted = listOf(PermissionState.GRANTED)
        assertEquals(ProgramAvailability.PAUSED, ProgramAvailability.of(UserState.PAUSED, listOf(PermissionState.PROGRAM_OFF), listOf("x"), true))
        assertEquals(ProgramAvailability.NEEDS_SYSTEM_PERMISSION, ProgramAvailability.of(UserState.ENABLED, listOf(PermissionState.SYSTEM_MISSING, PermissionState.GLOBAL_OFF), emptyList(), false))
        assertEquals(ProgramAvailability.GLOBAL_SWITCH_OFF, ProgramAvailability.of(UserState.ENABLED, listOf(PermissionState.GLOBAL_OFF), emptyList(), false))
        assertEquals(ProgramAvailability.MISSING_PROGRAM_PERMISSION, ProgramAvailability.of(UserState.ENABLED, listOf(PermissionState.EXPIRED), listOf("x"), false))
        assertEquals(ProgramAvailability.NEEDS_CONFIG, ProgramAvailability.of(UserState.ENABLED, granted, listOf("x"), false))
        assertEquals(ProgramAvailability.SOURCE_DISCONNECTED, ProgramAvailability.of(UserState.ENABLED, granted, emptyList(), true))
        assertEquals(ProgramAvailability.WORKING, ProgramAvailability.of(UserState.ENABLED, granted, emptyList(), false))
    }
}
