package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.runtime.api.gateway.*

import android.app.PendingIntent
import android.content.*
import android.os.IBinder
import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Application-owned binding. Recomposition, navigation and Activity destruction do not unbind. */
class RuntimeHost(context: Context, notification: () -> PendingIntent) {
    companion object { internal var notificationIntent: (() -> PendingIntent)? = null }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val service = MutableStateFlow<RuntimeService?>(null)
    private val status = MutableStateFlow(ConnectionState.CONNECTING)
    private val environmentState = MutableStateFlow(EnvironmentSnapshot(EnvironmentPhase.INITIALIZING, AppStrings.connectingToRuntimeService))
    private var observation: Job? = null
    private val diagnosticState = MutableStateFlow(DiagnosticState())
    private val app = context.applicationContext
    private val binding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = (binder as RuntimeService.LocalBinder).service
            service.value = connected
            observation?.cancel()
            observation = scope.launch {
                launch { connected.client.connection.collect { status.value = it } }
                launch { connected.state.collect { diagnosticState.value = it } }
                launch { connected.environment.collect { environmentState.value = it } }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service.value = null; observation?.cancel(); status.value = ConnectionState.DISCONNECTED
            environmentState.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, AppStrings.disconnectedResultUnconfirmed, RuntimeError(ErrorCode.DISCONNECTED, true))
        }
        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
            app.unbindService(this)
            app.bindService(Intent(app, RuntimeService::class.java), this, Context.BIND_AUTO_CREATE)
        }
    }
    init {
        notificationIntent = notification
        if (!app.bindService(Intent(app, RuntimeService::class.java), binding, Context.BIND_AUTO_CREATE)) status.value = ConnectionState.DISCONNECTED
    }
    private suspend fun connected(): RuntimeService? = withTimeoutOrNull(10_000) { service.filterNotNull().first() }
    val client: RuntimeClient = object : RuntimeClient {
        override val connection = status.asStateFlow()
        override suspend fun capabilities() = connected()?.client?.capabilities() ?: CapabilityResult.Unavailable(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun submit(request: RunRequest) = connected()?.client?.submit(request) ?: SubmitResult.Rejected(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun insert(request: InsertRequest) = connected()?.client?.insert(request) ?: CommandResult.Rejected(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun findByRequest(requestId: RequestId) = connected()?.client?.findByRequest(requestId) ?: RequestLookup.Unavailable
        override suspend fun respondToDevice(request: com.github.ytlog.mobby.android.runtime.api.device.DeviceInteractionResponse) =
            connected()?.client?.respondToDevice(request) ?: CommandResult.Rejected(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun cancel(request: CancelRequest) = connected()?.client?.cancel(request) ?: CommandResult.Rejected(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun resolveApproval(request: ApprovalDecision) = connected()?.client?.resolveApproval(request) ?: CommandResult.Rejected(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun snapshot(runId: RunId) = connected()?.client?.snapshot(runId) ?: SnapshotResult.Unavailable(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun readArtifact(request: ArtifactReadRequest) = connected()?.client?.readArtifact(request) ?: ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.DISCONNECTED, true))
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun observe(runId: RunId, after: EventCursor?): Flow<RuntimeUpdate> = service.filterNotNull().flatMapLatest { it.client.observe(runId, after) }
    }
    val diagnostics: RuntimeDiagnosticsClient = object : RuntimeDiagnosticsClient {
        override val state = diagnosticState.asStateFlow()
        override suspend fun executeShell(command: String) = connected()?.executeShell(command) ?: CommandResult.Rejected(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun stopShell() = connected()?.stopShell() ?: CommandResult.Rejected(RuntimeError(ErrorCode.DISCONNECTED, true))
    }
    val admin: RuntimeAdminClient = object : RuntimeAdminClient {
        override suspend fun saveDeviceDirectory(location: String) = connected()?.saveDeviceDirectory(location) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun eventHistorySettings() = connected()?.eventHistorySettings() ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun saveEventHistorySettings(settings: EventHistorySettings) = connected()?.saveEventHistorySettings(settings) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override val environment = environmentState.asStateFlow()
        override suspend fun listWorkspaces() = connected()?.listWorkspaces() ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun createWorkspace(name: String) = connected()?.createWorkspace(name) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun listSkills(agent: AgentId) = connected()?.listSkills(agent) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun listPlugins() = connected()?.listPlugins() ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun readSkill(ref: CapabilityRef) = connected()?.readSkill(ref) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun previewManualSkill(request: ManualSkillRequest) = connected()?.previewManualSkill(request) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun previewSkill(markdown: String) = connected()?.previewSkill(markdown) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun importResource(request: ImportResourceRequest) = connected()?.importResource(request) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun previewResource(ref: ResourceRef, workspace: WorkspaceRef, expanded: Boolean) = connected()?.previewResource(ref, workspace, expanded) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun resource(ref: ResourceRef, workspace: WorkspaceRef) = connected()?.resource(ref, workspace) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun importSkill(agent: AgentId, markdown: String) = connected()?.importSkill(agent, markdown) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun saveManualSkill(request: ManualSkillRequest) = connected()?.saveManualSkill(request) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun initialize() = connected()?.initialize() ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun validateGateway(profile: GatewayProfileRef, agent: AgentId) = connected()?.validateGateway(profile, agent) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun listGatewayProfiles() = connected()?.listGatewayProfiles() ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun fetchGatewayModels(request: FetchGatewayModelsRequest) = connected()?.fetchGatewayModels(request) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun saveGatewayProfile(request: SaveGatewayRequest) = connected()?.saveGatewayProfile(request) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun defaultGateway() = connected()?.defaultGateway() ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun selectDefaultGateway(selection: GatewaySelection) = connected()?.selectDefaultGateway(selection) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
        override suspend fun deleteGatewayProfile(id: String) = connected()?.deleteGatewayProfile(id) ?: AdminResult.Failed(RuntimeError(ErrorCode.DISCONNECTED, true))
    }
}
