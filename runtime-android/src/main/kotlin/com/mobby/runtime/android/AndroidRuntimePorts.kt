package com.mobby.runtime.android

import android.content.Context
import com.libtermux.executor.OutputLine
import com.mobby.runtime.api.*
import com.mobby.runtime.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

internal class AndroidRuntimePorts(
    private val context: Context, private val runtime: RuntimeEnvironment,
    private val state: StateFlow<EnvironmentSnapshot>, private val registry: ProcessRegistry
) : EnvironmentPort, ProcessPort {
    private val gateways = GatewayStore(context)
    private fun mode(agent: AgentId) = if (agent == AgentId.CODEX) AgentMode.CODEX else AgentMode.CLAUDE
    override suspend fun capabilities(): CapabilityResult = withContext(Dispatchers.IO) {
        CapabilityResult.Available(RuntimeCapabilities("mobby-local-1", AgentId.values().map { agent ->
            val config = runCatching { gateways.load(mode(agent)).also { it.validate() } }.getOrNull()
            AgentCapability(agent, if (config == null) emptyList() else listOf(ModelCapability(config.model, emptySet())),
                unavailableReason = if (state.value.phase != EnvironmentPhase.READY) RuntimeError(ErrorCode.NOT_READY, true)
                    else if (config == null) RuntimeError(ErrorCode.INVALID_CONFIG) else null,
                supportsResume = true, supportsApproval = false)
        }))
    }
    override suspend fun validate(request: RunRequest): RuntimeError? = withContext(Dispatchers.IO) {
        if (state.value.phase != EnvironmentPhase.READY) return@withContext RuntimeError(ErrorCode.NOT_READY, true)
        if (request.workspaceRef.value != "default") return@withContext RuntimeError(ErrorCode.PERMISSION_DENIED)
        if (request.capabilityRefs.isNotEmpty() || request.inputParts.any { it !is InputPart.Text } || request.reasoningLevel != null)
            return@withContext RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)
        val text = request.inputParts.filterIsInstance<InputPart.Text>().joinToString("\n") { it.text }
        if (text.isBlank() || text.toByteArray().size > 65536 || '\u0000' in text) return@withContext RuntimeError(ErrorCode.INVALID_CONFIG)
        val mode = mode(request.agentId)
        if (request.gatewayProfileRef.id != mode.name) return@withContext RuntimeError(ErrorCode.INVALID_CONFIG)
        if (request.sessionRef?.value?.matches(Regex("[A-Za-z0-9-]{1,100}")) == false) return@withContext RuntimeError(ErrorCode.INVALID_CONFIG)
        val valid = runCatching {
            runtime.executable(mode)
            gateways.load(mode, request.gatewayProfileRef.version).also { it.validate(); require(it.model == request.modelId) }
        }.isSuccess
        if (valid) null else RuntimeError(ErrorCode.INVALID_CONFIG)
    }
    override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, output: suspend (String, Boolean) -> Unit): ProcessResult = supervisorScope {
        val config = withContext(Dispatchers.IO) { gateways.load(mode(request.agentId), request.gatewayProfileRef.version).also { it.validate() } }
        var started = false
        var exit: Int? = null
        val worker = async(Dispatchers.IO) {
            val args = AgentCommand.arguments(request, runtime.executable(mode(request.agentId)), request.inputParts.filterIsInstance<InputPart.Text>().joinToString("\n") { it.text })
            runtime.sdk.executor.executeArgsStreaming(listOf(File(runtime.sdk.vfs.binDir, "node").absolutePath,
                File(context.filesDir, "gateway.cjs").absolutePath, mode(request.agentId).name) + args,
                runtime.workspace, mapOf("MOBBY_GATEWAY_CONFIG" to config.json()),
                onStarted = { pid -> started = true; registry.started(pid) },
                onTerminated = { code -> exit = code; registry.terminated(code) }
            ).collect { line ->
                when (line) {
                    is OutputLine.Stdout -> output(sanitize(line.text, config), false)
                    is OutputLine.Stderr -> output(sanitize(line.text, config), true)
                    is OutputLine.Exit -> exit = line.code
                }
            }
        }
        val watcher = launch { stop.filterNotNull().first(); worker.cancel() }
        try {
            worker.await()
            ProcessResult(exit, !started || exit != null)
        } catch (_: TimeoutCancellationException) {
            ProcessResult(exit, !started || exit != null, ErrorCode.TIMEOUT)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            ProcessResult(exit, !started || exit != null, if (stop.value == null) ErrorCode.INTERRUPTED else null)
        } catch (_: Exception) {
            ProcessResult(exit, !started || exit != null, ErrorCode.PROTOCOL_ERROR)
        } finally { watcher.cancel() }
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
