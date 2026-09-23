package com.github.ytlog.mobby.android.runtime.android

import android.content.Context
import com.libtermux.executor.OutputLine
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.device.DeviceCatalog
import com.github.ytlog.mobby.android.device.DeviceHost
import com.github.ytlog.mobby.android.device.DeviceSession
import com.github.ytlog.mobby.android.runtime.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

internal class AndroidRuntimePorts(
    private val context: Context, private val runtime: RuntimeEnvironment,
    private val state: StateFlow<EnvironmentSnapshot>, private val registry: ProcessRegistry,
    private val scope: CoroutineScope,
) : EnvironmentPort, ProcessPort {
    private val gate = Mutex()
    private var held: LiveAgent? = null
    private val liveState = MutableStateFlow(false)
    val live: StateFlow<Boolean> = liveState.asStateFlow()
    @Volatile private var control: AgentSession? = null
    override fun offerApproval(requestId: RequestId, approvalId: String, choice: ApprovalChoice) = control?.offer(requestId, approvalId, choice) == true
    suspend fun shutdownLive() {
        val current = gate.withLock { held.also { held = null } }
        current?.shutdown(force = true)
        liveState.value = false
    }
    private val skills get() = SkillStore(runtime.sdk.vfs.homeDir)
    private val resources get() = ResourceStore(File(context.filesDir, "input-resources"))
    private val gateways = GatewayStore(context)
    private fun mode(agent: AgentId) = agent.launchMode()
    override suspend fun capabilities(): CapabilityResult = withContext(Dispatchers.IO) {
        CapabilityResult.Available(RuntimeCapabilities("mobby-local-1", AgentId.values().map { agent ->
            val config = runCatching { gateways.load(mode(agent)).also { it.validateFor(mode(agent)) } }.getOrNull()
            val models = when {
                config == null -> emptyList()
                config.models.isNotEmpty() -> config.models.map { ModelCapability(it.id, emptySet(), it.name) }
                else -> listOf(ModelCapability(config.model, emptySet()))
            }
            AgentCapability(agent, models,
                unavailableReason = when {
                    state.value.phase != EnvironmentPhase.READY -> RuntimeError(ErrorCode.NOT_READY, true)
                    agent == AgentId.OPEN_CODE && !runtime.opencodeReady -> RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)
                    config == null -> RuntimeError(ErrorCode.INVALID_CONFIG)
                    else -> null
                },
                supportsResume = true, supportsApproval = agent == AgentId.CLAUDE_CODE, supportsResources = true, supportsImages = config != null, skillCapabilities = skills.list(agent).filter { it.available }.map { it.ref }.toSet())
        }))
    }
    override suspend fun validate(request: RunRequest): RuntimeError? = withContext(Dispatchers.IO) {
        if (state.value.phase != EnvironmentPhase.READY) return@withContext RuntimeError(ErrorCode.NOT_READY, true)
        if (runtime.workspaces.resolve(request.workspaceRef) == null) return@withContext RuntimeError(ErrorCode.PERMISSION_DENIED)
        if (request.reasoningLevel != null)
            return@withContext RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)
        val deviceRefs = request.capabilityRefs.map { it.value }.filter { it.startsWith("plugin:") }.toSet()
        if (deviceRefs.any { !DeviceCatalog.isKnown(it) } || DeviceCatalog.missingParent(deviceRefs) ||
            request.capabilityRefs.any { !it.value.startsWith("plugin:") && skills.resolve(it, request.agentId) == null })
            return@withContext RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)
        if (deviceRefs.any { !DeviceHost.granted(context, it) })
            return@withContext RuntimeError(ErrorCode.PERMISSION_DENIED)
        if (DeviceCatalog.selected(deviceRefs).any { skills.blocked(request.agentId, it.skillName) })
            return@withContext RuntimeError(ErrorCode.INVALID_CONFIG)
        if (request.requestedOutput == RequestedOutput.SKILL_PROPOSAL && !skills.hasCreator(request.agentId, request.capabilityRefs))
            return@withContext RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)
        if (request.inputParts.filterIsInstance<InputPart.Resource>().any { runCatching { resources.summary(it.ref, request.workspaceRef) }.isFailure })
            return@withContext RuntimeError(ErrorCode.RESOURCE_MISSING)
        try { resources.prepare(request.inputParts, request.workspaceRef) }
        catch (_: ResourceStore.InputTooLarge) { return@withContext RuntimeError(ErrorCode.INPUT_TOO_LARGE) }
        catch (_: Exception) { return@withContext RuntimeError(ErrorCode.INVALID_CONFIG) }
        val mode = mode(request.agentId)
        if (request.agentId == AgentId.OPEN_CODE && !runtime.opencodeReady) return@withContext RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)
        if (request.gatewayProfileRef.id != mode.name) return@withContext RuntimeError(ErrorCode.INVALID_CONFIG)
        if (request.sessionRef?.value?.matches(AgentSessionId) == false) return@withContext RuntimeError(ErrorCode.INVALID_CONFIG)
        val valid = runCatching {
            runtime.executable(mode)
            gateways.load(mode, request.gatewayProfileRef.version).also {
                it.validateFor(mode); require(it.accepts(request.modelId))
            }
        }.isSuccess
        if (valid) null else RuntimeError(ErrorCode.INVALID_CONFIG)
    }
    override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, output: suspend (String, Boolean) -> Unit): ProcessResult {
        val config = withContext(Dispatchers.IO) {
            val stored = gateways.load(mode(request.agentId), request.gatewayProfileRef.version)
            stored.validateFor(mode(request.agentId))
            require(stored.accepts(request.modelId))
            stored.forRun(request.modelId)
        }
        val reusable = gate.withLock { held?.takeIf { it.accepts(request) } }
        val watcher = scope.launch { stop.filterNotNull().first(); gate.withLock { held }?.shutdown(force = true) }
        var files: AgentInputFiles? = null
        return try {
            if (reusable != null) {
                val prepared = assemble(request, reusable.extras)
                files = prepared.files
                reusable.run(prepared.turn, stop, submit = true) { line, error -> output(sanitize(line, config), error) }
            } else {
                shutdownLive()
                start(request, config, stop) { line, error -> output(sanitize(line, config), error) }
            }
        } catch (_: TimeoutCancellationException) {
            ProcessResult(null, false, ErrorCode.TIMEOUT)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            held?.shutdown(force = true)
            ProcessResult(null, false, if (stop.value == null) ErrorCode.INTERRUPTED else null)
        } catch (_: Exception) {
            held?.shutdown(force = true)
            ProcessResult(null, false, ErrorCode.PROTOCOL_ERROR)
        } finally { watcher.cancel(); files?.close() }
    }
    private fun assemble(request: RunRequest, extras: List<Pair<String, File>>): PreparedTurn {
        val prepared = resources.prepare(request.inputParts, request.workspaceRef)
        val skillRefs = request.capabilityRefs.filterNot { DeviceCatalog.isKnown(it.value) }.toSet()
        var prompt = skills.prompt(request.agentId, skillRefs, prepared.prompt, extras)
        val structured = request.requestedOutput == RequestedOutput.SKILL_PROPOSAL
        if (structured) prompt += "\n\n" + SkillGeneration.instruction
        val files = if (prepared.images.isEmpty()) null else AgentInputFiles.create(File(context.filesDir, "agent-inputs"), prepared.images)
        val images = prepared.images.mapIndexed { index, image ->
            TurnImage(image.mediaType, files?.imagePaths?.get(index) ?: "/", Base64.getEncoder().encodeToString(image.bytes))
        }
        val schema = if (structured) Json.parseToJsonElement(SkillGeneration.schema) else null
        return PreparedTurn(AgentTurn(request.requestId, prompt, images, schema), files)
    }
    private suspend fun start(request: RunRequest, config: GatewayConfig, stop: StateFlow<StopCause?>, output: suspend (String, Boolean) -> Unit): ProcessResult {
        val workingDirectory = requireNotNull(runtime.workspaces.resolve(request.workspaceRef))
        var session: DeviceSession? = null
        var bridgeDir: File? = null
        var inbox: File? = null
        val extras = mutableListOf<Pair<String, File>>()
        val staged = mutableListOf<String>()
        var bridgeReleased = false
        val bridgeGate = Any()
        fun releaseBridge() = synchronized(bridgeGate) {
            if (bridgeReleased) return@synchronized
            bridgeReleased = true
            session?.close()
            staged.forEach { skills.unstage(request.agentId, it) }
            bridgeDir?.deleteRecursively()
            inbox?.deleteRecursively()
        }
        var prepared: PreparedTurn? = null
        var launched = false
        try {
            val deviceRefs = request.capabilityRefs.map { it.value }.filter { DeviceCatalog.isKnown(it) }.toSet()
            if (deviceRefs.isNotEmpty()) {
                bridgeDir = File(context.filesDir, "device-bridge/${request.requestId.value}")
                inbox = File(runtime.sdk.vfs.homeDir, "mobby-plugin-inbox/${request.requestId.value}")
                inbox!!.mkdirs()
                val node = File(runtime.sdk.vfs.binDir, "node").absolutePath
                session = DeviceHost.start(context, bridgeDir!!, inbox!!, workingDirectory, node, deviceRefs)
                for (skill in session!!.skills) {
                    val name = skill.parentFile.name
                    val installed = skills.stage(request.agentId, name, skill)
                    if (installed == null) {
                        releaseBridge()
                        return ProcessResult(null, false, ErrorCode.INVALID_CONFIG)
                    }
                    staged += name
                    extras += name to installed
                }
            }
            val ready = assemble(request, extras)
            prepared = ready
            val connection = AgentSessions.connect(request, runtime.executable(mode(request.agentId)), workingDirectory.absolutePath, ready.turn)
            val agent = LiveAgent(request, connection.session, extras)
            control = connection.session
            val exit = AtomicReference<Int?>(null)
            agent.job = scope.launch(Dispatchers.IO) {
                try {
                    runtime.sdk.executor.executeArgsStreaming(listOf(File(runtime.sdk.vfs.binDir, "node").absolutePath,
                        File(context.filesDir, "gateway.cjs").absolutePath, mode(request.agentId).name) + connection.arguments,
                        workingDirectory, mapOf("MOBBY_GATEWAY_CONFIG" to config.json()),
                        onStarted = { pid -> agent.started = true; liveState.value = true; registry.started(pid) },
                        onTerminated = { code -> exit.set(code); registry.terminated(code) },
                        input = connection.session.input, timeoutMs = 0,
                    ).collect { agent.inbox.send(it) }
                } finally {
                    withContext(NonCancellable) {
                        agent.alive = false
                        agent.inbox.close()
                        connection.session.close()
                        if (control === connection.session) control = null
                        releaseBridge()
                        gate.withLock {
                            if (held === agent) held = null
                            if (held == null) liveState.value = false
                        }
                    }
                }
            }
            launched = true
            agent.exit = exit
            gate.withLock { held = agent }
            return try { agent.run(ready.turn, stop, submit = false, output) } finally { ready.files?.close() }
        } catch (e: Exception) {
            if (!launched) {
                releaseBridge()
                prepared?.files?.close()
            }
            throw e
        }
    }
    private fun sanitize(text: String, config: GatewayConfig): String {
        var value = text
        if (config.key.isNotEmpty()) {
            value = value.replace(config.key, "[redacted]")
            val escaped = kotlinx.serialization.json.JsonPrimitive(config.key).toString().removeSurrounding("\"")
            value = value.replace(escaped, "[redacted]")
        }
        return value.replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*"), "$1[redacted]")
    }
}

private class PreparedTurn(val turn: AgentTurn, val files: AgentInputFiles?)

private class LiveAgent(
    request: RunRequest,
    val session: AgentSession,
    val extras: List<Pair<String, File>>,
) {
    val inbox = kotlinx.coroutines.channels.Channel<OutputLine>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    lateinit var job: Job
    lateinit var exit: AtomicReference<Int?>
    @Volatile var alive = true
    @Volatile var started = false
    private var binding: LiveSessionBinding? = null
    private val anchor = request
    fun accepts(request: RunRequest) = alive && ::job.isInitialized && job.isActive && binding?.accepts(request) == true
    fun shutdown(force: Boolean) {
        if (!::job.isInitialized) return
        if (force) job.cancel() else session.release()
    }
    suspend fun run(turn: AgentTurn, stop: StateFlow<StopCause?>, submit: Boolean, output: suspend (String, Boolean) -> Unit): ProcessResult {
        if (submit) session.submit(turn)
        while (true) {
            if (stop.value != null) return finish(retain = false, force = true)
            val line = inbox.receiveCatching().getOrNull() ?: return finish(retain = false, force = false)
            when (line) {
                is OutputLine.Stdout -> {
                    session.onStdout(line.text, autoAllow = true).forEach { output(it, false) }
                    if (session.takeTurnEnded()) return finish(retain = stop.value == null && session.sessionId() != null, force = false, acceptProtocolExit = session.abandonAfterTurn())
                }
                is OutputLine.Stderr -> output(line.text, true)
                is OutputLine.Exit -> return finish(retain = false, force = false)
            }
        }
    }
    private suspend fun finish(retain: Boolean, force: Boolean, acceptProtocolExit: Boolean = false): ProcessResult {
        val id = session.sessionId()
        if (retain && alive && job.isActive && id != null) {
            binding = LiveSessionBinding(anchor.agentId, anchor.workspaceRef, anchor.modelId, anchor.gatewayProfileRef, anchor.capabilityRefs, anchor.requestedOutput, id)
            return ProcessResult(null, true, retained = true)
        }
        shutdown(force = force || !alive)
        if (job.isActive) withTimeoutOrNull(5_000) { job.join() }
        // OpenCode can emit step_finish reason=stop and then stay alive. Stopping it is still a finished turn.
        val cancelled = job.isActive
        if (cancelled) { job.cancel(); job.join() }
        val code = if (::exit.isInitialized) exit.get() else null
        val reported = if (acceptProtocolExit && cancelled) 0 else code
        return ProcessResult(reported, reported != null || started || cancelled, retained = false)
    }
}
