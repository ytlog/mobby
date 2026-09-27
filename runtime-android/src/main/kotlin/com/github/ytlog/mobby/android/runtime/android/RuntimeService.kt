package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.runtime.api.gateway.*

import com.github.ytlog.mobby.android.runtime.android.gateway.*

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal open class RuntimeService : Service(), RuntimeAdminClient, RuntimeDiagnosticsClient {
    inner class LocalBinder : Binder() { val service get() = this@RuntimeService }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val submission = Mutex()
    private val mutableEnvironment = MutableStateFlow(EnvironmentSnapshot(EnvironmentPhase.INITIALIZING, AppStrings.initializingRuntime))
    override val environment = mutableEnvironment.asStateFlow()
    private lateinit var runtime: RuntimeEnvironment
    private lateinit var registry: ProcessRegistry
    private lateinit var ports: AndroidRuntimePorts
    private lateinit var coordinator: RunCoordinator
    private lateinit var journal: RuntimeJournal
    private lateinit var outputStore: OutputStore
    private var initialization: Job? = null
    private var shell: Job? = null
    private val shellId = RunId("shell-diagnostic")
    private val diagnosticState = MutableStateFlow(DiagnosticState())
    override val state = diagnosticState.asStateFlow()
    private var shellStopCause: StopCause? = null
    private var recovered = false
    private var notificationStarted = false
    @Volatile private var hostStopCause: StopCause? = null
    val client: RuntimeClient by lazy { object : RuntimeClient by coordinator {
        override suspend fun submit(request: RunRequest): SubmitResult {
            if (environment.value.phase != EnvironmentPhase.READY) return SubmitResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true))
            return submission.withLock {
                if (environment.value.phase != EnvironmentPhase.READY) return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true))
                try { outputStore.compact(journal) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, AppStrings.outputCleanupIncompleteRecheckRuntime, RuntimeError(ErrorCode.STORAGE_FULL, true))
                    return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL, true))
                }
                if (request.capabilityRefs.any { it.value == "plugin:device:screen" } && !RuntimeTaskNotification.canShow(this@RuntimeService))
                    return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.PERMISSION_DENIED, true))
                try { withContext(Dispatchers.Main) { beginForeground() } }
                catch (_: Exception) { return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.PERMISSION_DENIED, true)) }
                coordinator.submit(request).also { if (coordinator.active.value == null) endForeground() }
            }
        }
    } }
    override fun onCreate() {
        super.onCreate()
        runtime = RuntimeEnvironment(this)
        registry = ProcessRegistry(this)
        journal = RuntimeJournal(this, policyProvider = EventHistorySettingsStore(this)::policy)
        ports = AndroidRuntimePorts(this, runtime, environment, registry, scope)
        outputStore = OutputStore(this, journal::outputExpired, EventHistorySettingsStore(this)::outputPolicy)
        coordinator = RunCoordinator(scope, ports, ports, journal, outputStore)
        RuntimeTaskNotification.createChannel(this)
        scope.launch { combine(coordinator.active, ports.live) { _, _ -> Unit }.collect { submission.withLock {
            val running = coordinator.active.value != null
            val holding = ports.live.value
            when {
                running -> withContext(Dispatchers.Main) { showForeground(AppStrings.returnToTheAppToViewProgressOrStop) }
                holding -> withContext(Dispatchers.Main) { showForeground(AppStrings.conversationIsStillRunningReturnToTheAppTo) }
                else -> {
                    endForeground()
                    if (recovered && environment.value.phase == EnvironmentPhase.READY) {
                        try { outputStore.compact(journal) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, AppStrings.outputCleanupIncompleteRecheckRuntime, RuntimeError(ErrorCode.STORAGE_FULL, true)) }
                    }
                }
            }
        } } }
        startInitialization()
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RuntimeTaskNotification.stopTarget(intent)?.let { runId ->
            scope.launch { stopFromNotification(runId) }
        }
        return START_NOT_STICKY
    }

    internal open suspend fun stopFromNotification(runId: RunId) {
        // Bind the action to the run displayed in the notification, never a newer task.
        if (runId == shellId || coordinator.active.value != runId) return
        coordinator.requestStop(runId, StopCause.USER)
    }
    private fun startInitialization() {
        if (initialization?.isActive == true || coordinator.active.value != null) return
        // Reserve readiness before releasing admission; initialization uses the same boundary
        // as submissions and idle cleanup, so maintenance cannot overlap a new run.
        mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.INITIALIZING, AppStrings.verifyingLocalRuntime)
        initialization = scope.launch { submission.withLock {
            try {
                withTimeout(120_000) {
                    registry.recover()
                    if (!recovered) { coordinator.recover(); recovered = coordinator.connection.value == ConnectionState.CONNECTED }
                    check(recovered)
                    journal.compact()
                    outputStore.compact(journal)
                    withContext(Dispatchers.IO) { AgentInputFiles.cleanup(java.io.File(filesDir, "agent-inputs")) }
                    runtime.initialize { message -> mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.INITIALIZING, message) }
                    check(runtime.dependenciesReady)
                }
                mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.READY, AppStrings.runtimeReady)
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, AppStrings.initializationTimedOutYouCanRetry, RuntimeError(ErrorCode.TIMEOUT, true))
            } catch (_: Exception) {
                mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, AppStrings.runtimeInitializationFailedRetryOrCheckTheInstallation, RuntimeError(ErrorCode.NOT_READY, true))
            }
        } }
    }
    override suspend fun eventHistorySettings(): AdminResult<EventHistorySettings> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(EventHistorySettingsStore(this@RuntimeService).load()) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    override suspend fun saveEventHistorySettings(settings: EventHistorySettings): AdminResult<Unit> = withContext(Dispatchers.IO) {
        try { EventHistorySettingsStore(this@RuntimeService).save(settings); AdminResult.Success(Unit) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL)) }
    }
    override suspend fun initialize(): AdminResult<Unit> = withContext(Dispatchers.Main) { submission.withLock {
        if (coordinator.active.value != null) AdminResult.Failed(RuntimeError(ErrorCode.BUSY, true))
        else { startInitialization(); AdminResult.Success(Unit) }
    } }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val resourceDispatcher = Dispatchers.IO.limitedParallelism(1)
    override suspend fun previewResource(ref: ResourceRef, workspace: WorkspaceRef, expanded: Boolean): AdminResult<ResourcePreview> = withContext(resourceDispatcher) {
        try { AdminResult.Success(ResourcePreview(resources().preview(ref, workspace, expanded))) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
    }
    private fun resources() = PersistentResourceStore(java.io.File(filesDir, "input-resources"), EventHistorySettingsStore(this)::attachmentBudgetBytes, java.io.File(filesDir, "resource-cache"))
    override suspend fun importResource(request: ImportResourceRequest): AdminResult<ResourceSummary> = withContext(resourceDispatcher) {
        try {
            require(WorkspaceStore.forContext(this@RuntimeService).resolve(request.workspaceRef) != null)
            AdminResult.Success(resources().save(request))
        }
        catch (_: ResourceStore.QuotaExceeded) { AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_BUDGET_EXCEEDED)) }
        catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        catch (_: java.nio.charset.CharacterCodingException) { AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL, true)) }
    }
    override suspend fun resource(ref: ResourceRef, workspace: WorkspaceRef): AdminResult<ResourceSummary> = withContext(resourceDispatcher) {
        try { AdminResult.Success(resources().summary(ref, workspace)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
    }
    override suspend fun listWorkspaces(): AdminResult<List<WorkspaceSummary>> = withContext(Dispatchers.IO) {
        if (environment.value.phase != EnvironmentPhase.READY) return@withContext AdminResult.Failed(RuntimeError(ErrorCode.NOT_READY, true))
        try { AdminResult.Success(runtime.workspaces.list()) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
    }
    override suspend fun createWorkspace(name: String): AdminResult<WorkspaceSummary> = withContext(Dispatchers.IO) {
        if (environment.value.phase != EnvironmentPhase.READY) return@withContext AdminResult.Failed(RuntimeError(ErrorCode.NOT_READY, true))
        try {
            AdminResult.Success(runtime.workspaces.create(name) { directory ->
                check(runtime.sdk.executor.execute("git init -q .", directory).isSuccess)
            })
        } catch (e: CancellationException) { throw e }
        catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL, true)) }
    }
    private fun skills() = SkillStore(runtime.sdk.vfs.homeDir)
    override suspend fun listSkills(agent: AgentId): AdminResult<List<SkillSummary>> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(skills().list(agent)) } catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
    }
    override suspend fun listPlugins(): AdminResult<List<PluginSummary>> =
        AdminResult.Success(com.github.ytlog.mobby.android.device.DeviceHost.summaries(this))
    override suspend fun readSkill(ref: CapabilityRef): AdminResult<SkillPreview> = withContext(Dispatchers.IO) {
        try { skills().preview(ref)?.let { AdminResult.Success(it) } ?: AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    override suspend fun previewManualSkill(request: ManualSkillRequest): AdminResult<SkillPreview> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(com.github.ytlog.mobby.android.runtime.engine.SkillDocument.manual(request.name, request.description, request.body)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    override suspend fun previewSkill(markdown: String): AdminResult<SkillPreview> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(com.github.ytlog.mobby.android.runtime.engine.SkillDocument.preview(markdown)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    override suspend fun importSkill(agent: AgentId, markdown: String): AdminResult<SkillSummary> = submission.withLock {
        if (coordinator.active.value != null) return@withLock AdminResult.Failed(RuntimeError(ErrorCode.BUSY, true))
        withContext(Dispatchers.IO) {
            try { AdminResult.Success(skills().save(agent, markdown)) }
            catch (_: java.nio.file.FileAlreadyExistsException) { AdminResult.Failed(RuntimeError(ErrorCode.REQUEST_CONFLICT)) }
            catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
            catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL, true)) }
        }
    }
    override suspend fun saveManualSkill(request: ManualSkillRequest): AdminResult<SkillSummary> {
        val preview = try { com.github.ytlog.mobby.android.runtime.engine.SkillDocument.manual(request.name, request.description, request.body) }
            catch (_: Exception) { return AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        return importSkill(request.agent, preview.markdown)
    }
    private val gatewayAdmin by lazy { GatewayAdmin(this) }
    override suspend fun validateGateway(profile: GatewayProfileRef, agent: AgentId) = gatewayAdmin.validateGateway(profile, agent)
    override suspend fun listGatewayProfiles() = gatewayAdmin.listGatewayProfiles()
    override suspend fun fetchGatewayModels(request: FetchGatewayModelsRequest) = gatewayAdmin.fetchGatewayModels(request)
    override suspend fun defaultGateway() = gatewayAdmin.defaultGateway()
    override suspend fun selectDefaultGateway(selection: GatewaySelection) = gatewayAdmin.selectDefaultGateway(selection)
    override suspend fun saveGatewayProfile(request: SaveGatewayRequest) = gatewayAdmin.saveGatewayProfile(request)
    override suspend fun deleteGatewayProfile(id: String) = gatewayAdmin.deleteGatewayProfile(id)
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun executeShell(command: String): CommandResult {
        if (environment.value.phase != EnvironmentPhase.READY) return CommandResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true))
        return submission.withLock {
            if (environment.value.phase != EnvironmentPhase.READY) return@withLock CommandResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true))
            if (command.isBlank() || command.toByteArray().size > 65536 || '\u0000' in command) return@withLock CommandResult.Rejected(RuntimeError(ErrorCode.INVALID_CONFIG))
            if (!coordinator.acquireDiagnostic(shellId)) return@withLock CommandResult.Rejected(RuntimeError(ErrorCode.BUSY, true))
            try { withContext(Dispatchers.Main) { beginForeground() } }
            catch (_: Exception) { coordinator.releaseDiagnostic(shellId, true); return@withLock CommandResult.Rejected(RuntimeError(ErrorCode.PERMISSION_DENIED)) }
            shellStopCause = null
            diagnosticState.value = DiagnosticState(RunPhase.RUNNING)
            shell = scope.launch(start = CoroutineStart.ATOMIC) {
                var exit: Int? = null
                var started = false
                var error: ErrorCode? = null
                try {
                    val secrets = GatewayStore(this@RuntimeService).list().map { it.key }.filter { it.isNotEmpty() }
                    runtime.runShell(command,
                        onStarted = { started = true; registry.started(it) }, onTerminated = { exit = it; registry.terminated(it) }).collect { line ->
                        val text = when (line) {
                            is com.libtermux.executor.OutputLine.Stdout -> line.text
                            is com.libtermux.executor.OutputLine.Stderr -> line.text
                            is com.libtermux.executor.OutputLine.Exit -> { exit = line.code; null }
                        }
                        if (text != null) {
                            val redacted = secrets.fold(text) { value, key -> value.replace(key, "[redacted]") }
                            diagnosticState.update { it.copy(output = (it.output + redacted.take(4096)).takeLast(200)) }
                        }
                    }
                } catch (_: TimeoutCancellationException) { error = ErrorCode.TIMEOUT }
                catch (_: CancellationException) { if (shellStopCause == null) error = ErrorCode.INTERRUPTED }
                catch (_: Exception) { error = ErrorCode.PROTOCOL_ERROR }
                finally {
                    withContext(NonCancellable) {
                        val confirmed = !started || exit != null
                        val phase = when {
                            !confirmed -> RunPhase.OUTCOME_UNKNOWN
                            error == ErrorCode.TIMEOUT || shellStopCause == StopCause.TIMEOUT -> RunPhase.TIMED_OUT
                            shellStopCause == StopCause.HOST_STOP -> RunPhase.INTERRUPTED
                            error != null -> RunPhase.FAILED
                            shellStopCause == StopCause.USER -> RunPhase.CANCELLED
                            exit == 0 -> RunPhase.SUCCEEDED
                            else -> RunPhase.FAILED
                        }
                        diagnosticState.update { it.copy(phase = phase, error = error?.let { code -> RuntimeError(code) }) }
                        coordinator.releaseDiagnostic(shellId, confirmed)
                    }
                }
            }
            CommandResult.Accepted
        }
    }
    override suspend fun stopShell(): CommandResult = requestShellStop(StopCause.USER)
    private fun requestShellStop(cause: StopCause): CommandResult {
        if (diagnosticState.value.phase?.terminal != false) return CommandResult.AlreadyTerminal
        if (shellStopCause == null) shellStopCause = cause
        diagnosticState.update { it.copy(phase = RunPhase.CANCELLING) }
        shell?.cancel()
        return CommandResult.Accepted
    }
    private fun beginForeground() = showForeground(AppStrings.returnToTheAppToViewProgressOrStop)
    private fun showForeground(text: String) {
        check(environment.value.phase == EnvironmentPhase.READY)
        startService(Intent(this, RuntimeService::class.java))
        // Shell diagnostics reuse a fixed ID, so only Agent runs have a run-specific stop action.
        val stopTarget = coordinator.active.value?.takeUnless { it == shellId }
        val notification = RuntimeTaskNotification.build(this, text, stopTarget, RuntimeHost.notificationIntent?.invoke())
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        else startForeground(1, notification)
        hostStopCause = null
        notificationStarted = true
    }
    private suspend fun endForeground() = withContext(Dispatchers.Main) {
        if (notificationStarted) { stopForeground(STOP_FOREGROUND_REMOVE); notificationStarted = false; stopSelf() }
    }
    internal open suspend fun stopForHost(cause: StopCause) {
        if (::ports.isInitialized) ports.shutdownLive()
        if (coordinator.active.value == shellId) requestShellStop(cause)
        else coordinator.active.value?.let { coordinator.requestStop(it, cause) }
        withTimeoutOrNull(3_000) { coordinator.active.first { it == null } }
    }
    private suspend fun finishHostStop(cause: StopCause) {
        try { submission.withLock { stopForHost(cause) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, AppStrings.stopResultUnconfirmedTasksWillNotBeResentAutomatically, RuntimeError(ErrorCode.DISCONNECTED, true))
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        hostStopCause = StopCause.TIMEOUT
        initialization?.cancel()
        mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, AppStrings.systemRequestedBackgroundTaskTerminationStoppingExecution, RuntimeError(ErrorCode.TIMEOUT, true))
        // The Android deadline must not depend on a journal lock or process cleanup completing.
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationStarted = false
        stopSelf()
        scope.launch { finishHostStop(StopCause.TIMEOUT) }
    }
    override fun onDestroy() {
        initialization?.cancel()
        val cause = hostStopCause ?: StopCause.HOST_STOP
        mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, AppStrings.runtimeServiceStoppedCheckingTaskStatus, RuntimeError(if (cause == StopCause.TIMEOUT) ErrorCode.TIMEOUT else ErrorCode.INTERRUPTED, true))
        scope.launch(NonCancellable) {
            try { finishHostStop(cause) } finally { scope.cancel() }
        }
        super.onDestroy()
    }
}
