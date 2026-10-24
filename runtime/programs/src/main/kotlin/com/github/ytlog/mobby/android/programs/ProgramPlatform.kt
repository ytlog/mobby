package com.github.ytlog.mobby.android.programs

import android.app.PendingIntent
import android.content.Context
import com.github.ytlog.mobby.android.programs.api.EventStreamRoute
import com.github.ytlog.mobby.android.programs.api.ProgramApiServer
import com.github.ytlog.mobby.android.programs.api.SubscriptionsRoute
import com.github.ytlog.mobby.android.programs.api.UiMessagesRoute
import com.github.ytlog.mobby.android.programs.ui.ProgramUiHost
import com.github.ytlog.mobby.android.programs.base.config.ConfigCapability
import com.github.ytlog.mobby.android.programs.base.config.ConfigStore
import com.github.ytlog.mobby.android.programs.base.log.LogCapability
import com.github.ytlog.mobby.android.programs.base.net.NetHttpCapability
import com.github.ytlog.mobby.android.programs.base.notify.NotifyCapability
import com.github.ytlog.mobby.android.programs.base.secrets.KeystoreSecretCipher
import com.github.ytlog.mobby.android.programs.base.secrets.SecretStore
import com.github.ytlog.mobby.android.programs.base.secrets.SecretsCapability
import com.github.ytlog.mobby.android.programs.base.storage.FilesCapability
import com.github.ytlog.mobby.android.programs.base.storage.KvCapability
import com.github.ytlog.mobby.android.programs.base.storage.SqlCapability
import com.github.ytlog.mobby.android.programs.call.CallPipeline
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.event.*
import com.github.ytlog.mobby.android.programs.permission.*
import com.github.ytlog.mobby.android.programs.program.*
import com.github.ytlog.mobby.android.programs.run.ProgramProcessLauncher
import com.github.ytlog.mobby.android.programs.run.RunSupervisor
import com.github.ytlog.mobby.android.programs.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File

data class PermissionView(
    val capability: String, val required: Boolean, val enabled: Boolean, val state: PermissionState,
    val granted: Scope, val requested: Scope, val expiresAt: Long?, val scopeKind: ScopeKind?,
)

data class ConfigView(val field: ConfigField, val value: JsonElement?, val secretSet: Boolean)

data class SubscriptionView(val id: String, val event: String, val filter: JsonObject, val handler: String, val source: String,
    val paused: Boolean, val lastTriggeredAt: Long?, val triggerCount: Long, val active: Boolean)

data class RequestView(val requestId: String, val capability: String, val scope: Scope, val createdAt: Long)

data class ProgramView(
    val id: String, val name: String, val version: String, val userState: UserState, val availability: ProgramAvailability,
    val hasBackground: Boolean, val hasUi: Boolean, val permissions: List<PermissionView>, val config: List<ConfigView>,
    val subscriptions: List<SubscriptionView>, val requests: List<RequestView>,
)

data class ProgramCandidate(val directory: File, val name: String)

/** A capability as the permission management page shows it. */
data class CapabilityInfo(val id: String, val kind: CapabilityKind, val implicit: Boolean, val availability: Availability, val sideEffects: Set<SideEffect>)

/**
 * The program platform: registry, permissions, storage, runs, events and the loopback API, behind
 * one facade for the app and the development plugin. Every mutation ends with [refresh], which
 * reloads grants, rebuilds routing and tells live runs what changed.
 */
class ProgramPlatform internal constructor(
    private val context: Context,
    internal val database: ProgramsDatabase,
    internal val layout: ProgramLayout,
    /** Default workspace `programs/` directory, where programs are written. */
    val workspacePrograms: File,
    launcher: (logDir: File) -> ProgramProcessLauncher,
    internal val registry: CapabilityRegistry,
    private val secretStore: SecretStore,
    private val schedules: ScheduleBackend,
    private val sql: SqlCapability?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal val dao = database.dao()
    internal val gate = PermissionGate(registry, clock)
    private val permissions = PermissionManager(dao, gate, clock)
    val config = ConfigStore(dao, clock)
    private val reader = ProgramPackageReader(registry)
    private val installer = ProgramInstaller(database, layout, reader, clock)
    internal val pipeline = CallPipeline(registry, gate, dao, layout, clock)
    private val sdkDir = File(layout.root, "sdk")
    private lateinit var server: ProgramApiServer
    internal val runs = RunSupervisor(scope, dao, launcher(File(layout.root, "logs")), sdkDir, { server.port }, clock = clock)
    private val status = ProgramStatus(dao, gate, config)
    internal val router = EventRouter(dao, status, runs, layout, schedules, clock)
    private val control = Mutex()
    private var expiry: Job? = null
    private val revision = MutableStateFlow(0)

    val apiPort: Int get() = server.port

    internal fun now() = clock()

    /** Closes cached database connections under [dir] before it is deleted. */
    internal fun releaseStorage(dir: File) { sql?.close(dir) }

    /** Development commands for the chat plugin; the app installs it as the device-side port. */
    val development: com.github.ytlog.mobby.android.device.ProgramDevelopmentPort by lazy { com.github.ytlog.mobby.android.programs.dev.DevelopmentService(this) }

    /** Program pages; the app's activity attaches WebViews here. */
    val ui = ProgramUiHost(this, { context.assets.open("program-ui-sdk/sdk.js").use { it.readBytes() } }, scope)

    suspend fun start() {
        installSdk()
        runs.recover()
        permissions.reload()
        server = ProgramApiServer(pipeline, registry, gate, permissions, runs,
            listOf(EventStreamRoute(runs), SubscriptionsRoute(dao, registry, gate) { programId -> refresh("subscription_changed", programId) },
                UiMessagesRoute(ui::post)))
        withContext(Dispatchers.IO) { server.start() }
        router.rebuild()
        ScheduleTarget.publish(router)
        scheduleExpiry()
    }

    internal fun stopServer() { if (::server.isInitialized) server.stop() }


    val programs: Flow<List<ProgramView>> = combine(
        dao.programsFlow(), dao.grantsFlow(), dao.subscriptionsFlow(), dao.switchesFlow(), dao.configFlow(), dao.pendingRequestsFlow(), revision,
    ) { _ -> Unit }.mapLatest { views() }.flowOn(Dispatchers.IO)

    val switches: Flow<Map<String, Boolean>> = dao.switchesFlow().map { rows -> rows.associate { it.capability to it.enabled } }

    fun capabilities(): List<CapabilityInfo> = registry.descriptors.map { d ->
        CapabilityInfo(d.id, d.kind, d.implicit, registry[d.id]!!.availability(), d.sideEffects)
    }

    fun runs(limit: Int = 200): Flow<List<RunRow>> = dao.recentRunsFlow(limit)

    suspend fun operations(programId: String, limit: Int = 100) = dao.operations(programId, limit)
    suspend fun logs(programId: String, limit: Int = 100) = dao.logs(programId, limit)
    fun runLog(runId: String): String? = File(layout.root, "logs/$runId.log").takeIf { it.isFile }?.readText()

    /** Program directories in the default workspace, newest first. */
    fun candidates(): List<ProgramCandidate> = workspacePrograms.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "manifest.json").isFile }.sortedByDescending { it.lastModified() }
        .map { ProgramCandidate(it, it.name) }

    suspend fun preview(directory: File): InstallPreview = withContext(Dispatchers.IO) { installer.preview(directory) }

    suspend fun install(directory: File): InstallResult = mutate("program_updated") {
        val result = installer.install(directory)
        if (result.updated) runs.stop(result.programId, "program_updated")
        result
    }

    suspend fun setEnabled(programId: String, enabled: Boolean) = mutate(if (enabled) "enabled" else "paused") {
        dao.setUserState(programId, (if (enabled) UserState.ENABLED else UserState.PAUSED).wire, clock())
        if (!enabled) runs.stop(programId, "paused")
    }

    suspend fun stop(programId: String) = runs.stop(programId, "stopped_by_user")

    suspend fun stopAll() = mutate("stop_all") {
        dao.setAllUserState(UserState.PAUSED.wire, clock())
        runs.stopAll("stop_all")
    }

    suspend fun delete(programId: String, withData: Boolean) = mutate("program_deleted") {
        runs.stop(programId, "program_deleted")
        sql?.close(layout.data(programId))
        installer.delete(programId, withData)
    }

    suspend fun setGrant(programId: String, capability: String, enabled: Boolean, scope: Scope?, expiresAt: Long?) = mutate("permission_changed", programId) {
        permissions.setGrant(programId, capability, enabled, scope, expiresAt)
    }

    suspend fun setSwitch(capability: String, enabled: Boolean) = mutate("permission_changed") { permissions.setSwitch(capability, enabled) }

    suspend fun resolveRequest(request: RequestView, programId: String, approved: Boolean) = mutate("permission_changed", programId) {
        permissions.resolveRequest(request.requestId, approved, programId, request.capability, request.scope)
    }

    suspend fun setConfig(programId: String, key: String, value: JsonElement?) = mutate("config_changed", programId) {
        if (value == null) config.clear(programId, key) else config.set(programId, key, value)
    }

    /** Secret config fields are filled in by the user here; programs never read them back. */
    suspend fun setSecretField(programId: String, key: String, value: String) = mutate("config_changed", programId) {
        val field = config.fields(programId).firstOrNull { it.key == key && it.type == ConfigType.SECRET.wire }
            ?: throw IllegalArgumentException("Not a secret field")
        secretStore.put(programId, key, value, field.domains.orEmpty().toSet())
    }

    suspend fun setSubscriptionPaused(subscriptionId: String, paused: Boolean) = mutate("subscription_changed", subscriptionId.substringBefore('/')) {
        dao.setSubscriptionPaused(subscriptionId, paused)
    }

    /** Applies a change, then reloads grants, rebuilds routing and informs live runs. */
    private suspend fun <T> mutate(reason: String, programId: String? = null, block: suspend () -> T): T = control.withLock {
        val result = withContext(Dispatchers.IO) { block() }
        refreshLocked(reason, programId)
        result
    }

    internal suspend fun refresh(reason: String, programId: String? = null) = control.withLock { refreshLocked(reason, programId) }

    /** [programId] names the program a change belongs to; only its live run is told about it. */
    private suspend fun refreshLocked(reason: String, programId: String?) {
        permissions.reload()
        router.rebuild()
        // A program that can no longer work stops now instead of at its next call.
        for (program in dao.programs()) {
            val availability = status.availability(program.programId)
            if (availability != ProgramAvailability.WORKING) runs.stop(program.programId, reason)
            else if (program.programId == programId) when (reason) {
                "permission_changed" -> router.notify(program.programId, "permission.changed", JsonObject(emptyMap()))
                "config_changed" -> router.notify(program.programId, "config.changed", JsonObject(emptyMap()))
                "subscription_changed" -> router.notify(program.programId, "subscription.changed", JsonObject(emptyMap()))
            }
        }
        scheduleExpiry()
        revision.update { it + 1 }
    }

    private fun scheduleExpiry() {
        expiry?.cancel()
        val next = gate.snapshot.nextExpiry?.takeIf { it > clock() } ?: return
        expiry = scope.launch { delay(next - clock()); refresh("permission_expired") }
    }

    private suspend fun views(): List<ProgramView> {
        val pending = dao.pendingRequestsFlow().first().groupBy { it.programId }
        return dao.programs().sortedBy { it.name }.map { program ->
            val version = dao.version(program.programId, program.currentVersion)
            val manifest = version?.let { ManifestJson.decodeFromString(ProgramManifest.serializer(), it.manifestJson) }
            val values = config.values(program.programId)
            ProgramView(
                program.programId, program.name, program.currentVersion, UserState.of(program.userState), status.availability(program.programId),
                version?.backgroundEntry != null, version?.uiEntry != null,
                dao.grants(program.programId).sortedBy { it.capability }.map { row ->
                    val grant = PermissionManager.grant(row)
                    PermissionView(row.capability, row.required, row.enabled, gate.state(grant), grant.granted, grant.requested, row.expiresAt,
                        registry[row.capability]?.descriptor?.scope)
                },
                manifest?.config.orEmpty().map { field ->
                    val secret = field.type == ConfigType.SECRET.wire
                    ConfigView(field, if (secret) null else values[field.key], secret && (values[field.key] as? JsonObject)?.get("set")?.jsonPrimitive?.boolean == true)
                },
                dao.subscriptions(program.programId).map { row ->
                    SubscriptionView(row.subscriptionId, row.eventType, Json.parseToJsonElement(row.filterJson).jsonObject, row.handler, row.source,
                        row.paused, row.lastTriggeredAt, row.triggerCount, router.isActive(row.subscriptionId))
                },
                pending[program.programId].orEmpty().map { RequestView(it.requestId, it.capability, Scope.fromJson(it.scope), it.createdAt) },
            )
        }
    }

    private fun installSdk() {
        val target = File(sdkDir, ".staging").apply { deleteRecursively(); mkdirs() }
        fun copy(asset: String, to: File) {
            val children = context.assets.list(asset).orEmpty()
            if (children.isEmpty()) { to.parentFile!!.mkdirs(); context.assets.open(asset).use { input -> to.outputStream().use { input.copyTo(it) } } }
            else children.forEach { copy("$asset/$it", File(to, it)) }
        }
        copy("program-sdk", target)
        File(sdkDir, "runner.cjs").delete(); File(sdkDir, "node_modules").deleteRecursively()
        target.listFiles().orEmpty().forEach { it.renameTo(File(sdkDir, it.name)) }
        target.deleteRecursively()
    }

    companion object {
        fun create(
            context: Context,
            workspacePrograms: File,
            launcher: (logDir: File) -> ProgramProcessLauncher,
            openUi: (programId: String) -> PendingIntent?,
        ): ProgramPlatform {
            val app = context.applicationContext
            val database = ProgramsDatabase.open(app)
            val dao = database.dao()
            val clock = System::currentTimeMillis
            val secrets = SecretStore(dao, KeystoreSecretCipher(), clock)
            val sql = SqlCapability()
            val registry = CapabilityRegistry(
                PHASE_ONE_DEVICE_CAPABILITIES.map { DeviceCapabilityHandler(it, AndroidDevicePort(app)) } + listOf(
                    KvCapability(dao, clock), FilesCapability(), sql, NetHttpCapability(secrets), SecretsCapability(secrets),
                    ConfigCapability(ConfigStore(dao, clock)), LogCapability(dao, clock), NotifyCapability(app, dao, openUi), ScheduleCapability(),
                ))
            return ProgramPlatform(app, database, ProgramLayout(File(app.filesDir, "programs")), workspacePrograms, launcher, registry, secrets,
                WorkManagerSchedules(app), sql, CoroutineScope(SupervisorJob() + Dispatchers.Default), clock)
        }
    }
}
