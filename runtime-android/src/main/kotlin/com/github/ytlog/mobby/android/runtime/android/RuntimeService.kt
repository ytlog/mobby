package com.github.ytlog.mobby.android.runtime.android

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
    private val mutableEnvironment = MutableStateFlow(EnvironmentSnapshot(EnvironmentPhase.INITIALIZING, "正在初始化运行环境"))
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
                    mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, "输出清理未完成，请重新检查运行环境", RuntimeError(ErrorCode.STORAGE_FULL, true))
                    return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL, true))
                }
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("runtime", "任务运行", NotificationManager.IMPORTANCE_LOW))
        scope.launch { combine(coordinator.active, ports.live) { _, _ -> Unit }.collect { submission.withLock {
            val running = coordinator.active.value != null
            val holding = ports.live.value
            when {
                running -> withContext(Dispatchers.Main) { showForeground("可返回应用查看进度或停止任务") }
                holding -> withContext(Dispatchers.Main) { showForeground("会话仍在运行，返回应用可继续") }
                else -> {
                    endForeground()
                    if (recovered && environment.value.phase == EnvironmentPhase.READY) {
                        try { outputStore.compact(journal) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, "输出清理未完成，请重新检查运行环境", RuntimeError(ErrorCode.STORAGE_FULL, true)) }
                    }
                }
            }
        } } }
        startInitialization()
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    private fun startInitialization() {
        if (initialization?.isActive == true || coordinator.active.value != null) return
        // Reserve readiness before releasing admission; initialization uses the same boundary
        // as submissions and idle cleanup, so maintenance cannot overlap a new run.
        mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.INITIALIZING, "正在验证本机运行环境")
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
                mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.READY, "运行环境已就绪")
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, "初始化超时，可重试", RuntimeError(ErrorCode.TIMEOUT, true))
            } catch (_: Exception) {
                mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, "运行环境初始化失败，请重试或检查安装", RuntimeError(ErrorCode.NOT_READY, true))
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
    private fun resources() = ResourceStore(java.io.File(filesDir, "input-resources"), EventHistorySettingsStore(this)::attachmentBudgetBytes)
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
    override suspend fun validateGateway(profile: GatewayProfileRef): AdminResult<GatewayCheck> {
        val config = try {
            withContext(Dispatchers.IO) {
                val mode = when (profile.id) {
                    "CODEX" -> AgentMode.CODEX
                    "CLAUDE" -> AgentMode.CLAUDE
                    "OPEN_CODE" -> AgentMode.OPEN_CODE
                    else -> error("invalid profile")
                }
                val (version, saved) = GatewayStore(this@RuntimeService).snapshot(mode)
                require(version == profile.version)
                saved.validateFor(mode)
                saved
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        return try { AdminResult.Success(GatewayProbe().check(profile, config)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.PROTOCOL_ERROR, true)) }
    }
    override suspend fun listGatewayProfiles(): AdminResult<List<GatewayProfileSummary>> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(AgentMode.values().filter { it != AgentMode.SHELL }.map { summary(it) }) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    private fun summary(mode: AgentMode): GatewayProfileSummary {
        val store = GatewayStore(this)
        val (version, config) = store.snapshot(mode)
        return GatewayProfileSummary(GatewayProfileRef(mode.name, version),
            mode.productAgent(),
            config.endpoint, config.model, com.github.ytlog.mobby.android.runtime.api.GatewayProtocol.valueOf(config.protocol.name), config.key.isNotEmpty())
    }
    override suspend fun saveGatewayProfile(request: SaveGatewayRequest): AdminResult<GatewayProfileSummary> = withContext(Dispatchers.IO) {
        val chars = request.credential?.consume()
        try {
            val mode = request.agent.launchMode()
            val store = GatewayStore(this@RuntimeService)
            val old = store.load(mode)
            store.save(mode, GatewayConfig(request.endpoint, request.model, chars?.concatToString() ?: old.key, GatewayProtocol.valueOf(request.protocol.name)))
            AdminResult.Success(summary(mode))
        } catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        finally { chars?.fill('\u0000') }
    }
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
                    val secrets = AgentMode.values().filter { it != AgentMode.SHELL }.mapNotNull { runCatching { GatewayStore(this@RuntimeService).load(it).key }.getOrNull() }.filter { it.isNotEmpty() }
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
    private fun beginForeground() = showForeground("可返回应用查看进度或停止任务")
    private fun showForeground(text: String) {
        check(environment.value.phase == EnvironmentPhase.READY)
        startService(Intent(this, RuntimeService::class.java))
        val notification = Notification.Builder(this, "runtime").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("mobby 正在执行任务").setContentText(text)
            .setContentIntent(RuntimeHost.notificationIntent?.invoke()).setOngoing(true).build()
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
            mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, "停止结果无法确认，已有任务不会自动重发；请重启后核实", RuntimeError(ErrorCode.DISCONNECTED, true))
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        hostStopCause = StopCause.TIMEOUT
        initialization?.cancel()
        mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, "系统要求结束后台任务，正在停止执行", RuntimeError(ErrorCode.TIMEOUT, true))
        // The Android deadline must not depend on a journal lock or process cleanup completing.
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationStarted = false
        stopSelf()
        scope.launch { finishHostStop(StopCause.TIMEOUT) }
    }
    override fun onDestroy() {
        initialization?.cancel()
        val cause = hostStopCause ?: StopCause.HOST_STOP
        mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, "运行服务已停止，正在核实任务状态", RuntimeError(if (cause == StopCause.TIMEOUT) ErrorCode.TIMEOUT else ErrorCode.INTERRUPTED, true))
        scope.launch(NonCancellable) {
            try { finishHostStop(cause) } finally { scope.cancel() }
        }
        super.onDestroy()
    }
}
