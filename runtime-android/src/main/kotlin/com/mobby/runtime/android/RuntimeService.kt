package com.mobby.runtime.android

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.mobby.runtime.api.*
import com.mobby.runtime.engine.*
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
    private lateinit var coordinator: RunCoordinator
    private lateinit var journal: RuntimeJournal
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
        override suspend fun submit(request: RunRequest): SubmitResult = submission.withLock {
            if (environment.value.phase != EnvironmentPhase.READY) return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true))
            try { withContext(Dispatchers.Main) { beginForeground() } }
            catch (_: Exception) { return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.PERMISSION_DENIED, true)) }
            coordinator.submit(request).also { if (coordinator.active.value == null) endForeground() }
        }
    } }
    override fun onCreate() {
        super.onCreate()
        runtime = RuntimeEnvironment(this)
        registry = ProcessRegistry(this)
        journal = RuntimeJournal(this)
        val ports = AndroidRuntimePorts(this, runtime, environment, registry)
        coordinator = RunCoordinator(scope, ports, ports, journal, OutputStore(this))
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("runtime", "任务运行", NotificationManager.IMPORTANCE_LOW))
        scope.launch { coordinator.active.collect { if (it == null) submission.withLock { if (coordinator.active.value == null) endForeground() } } }
        startInitialization()
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    private fun startInitialization() {
        if (initialization?.isActive == true || coordinator.active.value != null) return
        initialization = scope.launch {
            mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.INITIALIZING, "正在验证本机运行环境")
            try {
                withTimeout(120_000) {
                    registry.recover()
                    if (!recovered) { coordinator.recover(); recovered = coordinator.connection.value == ConnectionState.CONNECTED }
                    check(recovered)
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
        }
    }
    override suspend fun initialize(): AdminResult<Unit> = withContext(Dispatchers.Main) {
        if (coordinator.active.value != null) AdminResult.Failed(RuntimeError(ErrorCode.BUSY, true))
        else { startInitialization(); AdminResult.Success(Unit) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val resourceDispatcher = Dispatchers.IO.limitedParallelism(1)
    override suspend fun previewResource(ref: ResourceRef, workspace: WorkspaceRef, expanded: Boolean): AdminResult<ResourcePreview> = withContext(resourceDispatcher) {
        try { AdminResult.Success(ResourcePreview(resources().preview(ref, workspace, expanded))) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
    }
    private fun resources() = ResourceStore(java.io.File(filesDir, "input-resources"))
    override suspend fun importResource(request: ImportResourceRequest): AdminResult<ResourceSummary> = withContext(resourceDispatcher) {
        try { AdminResult.Success(resources().save(request)) }
        catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        catch (_: java.nio.charset.CharacterCodingException) { AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL, true)) }
    }
    override suspend fun resource(ref: ResourceRef, workspace: WorkspaceRef): AdminResult<ResourceSummary> = withContext(resourceDispatcher) {
        try { AdminResult.Success(resources().summary(ref, workspace)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
    }
    private fun skills() = SkillStore(runtime.sdk.vfs.homeDir)
    override suspend fun listSkills(agent: AgentId): AdminResult<List<SkillSummary>> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(skills().list(agent)) } catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
    }
    override suspend fun readSkill(ref: CapabilityRef): AdminResult<SkillPreview> = withContext(Dispatchers.IO) {
        try { skills().preview(ref)?.let { AdminResult.Success(it) } ?: AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    override suspend fun previewManualSkill(request: ManualSkillRequest): AdminResult<SkillPreview> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(com.mobby.runtime.engine.SkillDocument.manual(request.name, request.description, request.body)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    override suspend fun previewSkill(markdown: String): AdminResult<SkillPreview> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(com.mobby.runtime.engine.SkillDocument.preview(markdown)) }
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
        val preview = try { com.mobby.runtime.engine.SkillDocument.manual(request.name, request.description, request.body) }
            catch (_: Exception) { return AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        return importSkill(request.agent, preview.markdown)
    }
    override suspend fun listGatewayProfiles(): AdminResult<List<GatewayProfileSummary>> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(listOf(AgentMode.CODEX, AgentMode.CLAUDE).map { summary(it) }) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    private fun summary(mode: AgentMode): GatewayProfileSummary {
        val store = GatewayStore(this)
        val (version, config) = store.snapshot(mode)
        return GatewayProfileSummary(GatewayProfileRef(mode.name, version),
            if (mode == AgentMode.CODEX) AgentId.CODEX else AgentId.CLAUDE_CODE,
            config.endpoint, config.model, com.mobby.runtime.api.GatewayProtocol.valueOf(config.protocol.name), config.key.isNotEmpty())
    }
    override suspend fun saveGatewayProfile(request: SaveGatewayRequest): AdminResult<GatewayProfileSummary> = withContext(Dispatchers.IO) {
        val chars = request.credential?.consume()
        try {
            val mode = if (request.agent == AgentId.CODEX) AgentMode.CODEX else AgentMode.CLAUDE
            val store = GatewayStore(this@RuntimeService)
            val old = store.load(mode)
            store.save(mode, GatewayConfig(request.endpoint, request.model, chars?.concatToString() ?: old.key, GatewayProtocol.valueOf(request.protocol.name)))
            AdminResult.Success(summary(mode))
        } catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        finally { chars?.fill('\u0000') }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun executeShell(command: String): CommandResult = submission.withLock {
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
                val secrets = listOf(AgentMode.CODEX, AgentMode.CLAUDE).mapNotNull { runCatching { GatewayStore(this@RuntimeService).load(it).key }.getOrNull() }.filter { it.isNotEmpty() }
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
    override suspend fun stopShell(): CommandResult = requestShellStop(StopCause.USER)
    private fun requestShellStop(cause: StopCause): CommandResult {
        if (diagnosticState.value.phase?.terminal != false) return CommandResult.AlreadyTerminal
        if (shellStopCause == null) shellStopCause = cause
        diagnosticState.update { it.copy(phase = RunPhase.CANCELLING) }
        shell?.cancel()
        return CommandResult.Accepted
    }
    private fun beginForeground() {
        check(environment.value.phase == EnvironmentPhase.READY)
        if (notificationStarted) return
        startService(Intent(this, RuntimeService::class.java))
        val notification = Notification.Builder(this, "runtime").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("mobby 正在执行任务").setContentText("可返回应用查看进度或停止任务")
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
        mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, "系统要求结束后台任务，正在停止执行", RuntimeError(ErrorCode.TIMEOUT, true))
        // The Android deadline must not depend on a journal lock or process cleanup completing.
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationStarted = false
        stopSelf()
        scope.launch { finishHostStop(StopCause.TIMEOUT) }
    }
    override fun onDestroy() {
        val cause = hostStopCause ?: StopCause.HOST_STOP
        mutableEnvironment.value = EnvironmentSnapshot(EnvironmentPhase.FAILED, "运行服务已停止，正在核实任务状态", RuntimeError(if (cause == StopCause.TIMEOUT) ErrorCode.TIMEOUT else ErrorCode.INTERRUPTED, true))
        scope.launch(NonCancellable) {
            try { finishHostStop(cause) } finally { scope.cancel() }
        }
        super.onDestroy()
    }
}
