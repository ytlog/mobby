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
    @Volatile private var control: ClaudeControlSession? = null
    override fun offerApproval(requestId: RequestId, approvalId: String, choice: ApprovalChoice) = control?.offer(requestId, approvalId, choice) == true
    private val skills get() = SkillStore(runtime.sdk.vfs.homeDir)
    private val resources get() = ResourceStore(File(context.filesDir, "input-resources"))
    private val gateways = GatewayStore(context)
    private fun mode(agent: AgentId) = if (agent == AgentId.CODEX) AgentMode.CODEX else AgentMode.CLAUDE
    override suspend fun capabilities(): CapabilityResult = withContext(Dispatchers.IO) {
        CapabilityResult.Available(RuntimeCapabilities("mobby-local-1", AgentId.values().map { agent ->
            val config = runCatching { gateways.load(mode(agent)).also { it.validateFor(mode(agent)) } }.getOrNull()
            AgentCapability(agent, if (config == null) emptyList() else listOf(ModelCapability(config.model, emptySet())),
                unavailableReason = if (state.value.phase != EnvironmentPhase.READY) RuntimeError(ErrorCode.NOT_READY, true)
                    else if (config == null) RuntimeError(ErrorCode.INVALID_CONFIG) else null,
                supportsResume = true, supportsApproval = agent == AgentId.CLAUDE_CODE, supportsResources = true, supportsImages = config != null, skillCapabilities = skills.list(agent).filter { it.available }.map { it.ref }.toSet())
        }))
    }
    override suspend fun validate(request: RunRequest): RuntimeError? = withContext(Dispatchers.IO) {
        if (state.value.phase != EnvironmentPhase.READY) return@withContext RuntimeError(ErrorCode.NOT_READY, true)
        if (runtime.workspaces.resolve(request.workspaceRef) == null) return@withContext RuntimeError(ErrorCode.PERMISSION_DENIED)
        if (request.reasoningLevel != null)
            return@withContext RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)
        if (request.capabilityRefs.size > 8 || request.capabilityRefs.any { skills.resolve(it, request.agentId) == null })
            return@withContext RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)
        if (request.requestedOutput == RequestedOutput.SKILL_PROPOSAL && !skills.hasCreator(request.agentId, request.capabilityRefs))
            return@withContext RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY)
        if (request.inputParts.filterIsInstance<InputPart.Resource>().any { runCatching { resources.summary(it.ref, request.workspaceRef) }.isFailure })
            return@withContext RuntimeError(ErrorCode.RESOURCE_MISSING)
        try { resources.prepare(request.inputParts, request.workspaceRef) }
        catch (_: ResourceStore.InputTooLarge) { return@withContext RuntimeError(ErrorCode.INPUT_TOO_LARGE) }
        catch (_: Exception) { return@withContext RuntimeError(ErrorCode.INVALID_CONFIG) }
        val mode = mode(request.agentId)
        if (request.gatewayProfileRef.id != mode.name) return@withContext RuntimeError(ErrorCode.INVALID_CONFIG)
        if (request.sessionRef?.value?.matches(Regex("[A-Za-z0-9-]{1,100}")) == false) return@withContext RuntimeError(ErrorCode.INVALID_CONFIG)
        val valid = runCatching {
            runtime.executable(mode)
            gateways.load(mode, request.gatewayProfileRef.version).also {
                it.validateFor(mode); require(it.model == request.modelId)
            }
        }.isSuccess
        if (valid) null else RuntimeError(ErrorCode.INVALID_CONFIG)
    }
    override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, output: suspend (String, Boolean) -> Unit): ProcessResult = supervisorScope {
        val config = withContext(Dispatchers.IO) { gateways.load(mode(request.agentId), request.gatewayProfileRef.version).also { it.validateFor(mode(request.agentId)) } }
        var started = false
        var exit: Int? = null
        val worker = async(Dispatchers.IO) {
            val workingDirectory = requireNotNull(runtime.workspaces.resolve(request.workspaceRef))
            val prepared = resources.prepare(request.inputParts, request.workspaceRef)
            var prompt = skills.prompt(request.agentId, request.capabilityRefs, prepared.prompt)
            if (request.requestedOutput == RequestedOutput.SKILL_PROPOSAL) {
                prompt += "\n\n" + """
                    本轮是应用内的技能草稿预览，不是安装任务。使用 Skill Creator 的设计指导，但只读取所需说明：
                    不运行初始化、打包或安装脚本，不执行 mkdir，不创建目录，不写入或修改任何文件。
                    保存与目录创建由应用在用户明确确认后完成。需要澄清时先提问，不伪造已安装结果。
                    需求明确后，最终回复必须提供完整 SKILL.md（含 name、description 元信息和正文）。
                    输出块的第一行必须逐字为：
                    ````SKILL.md
                    输出块的最后一行必须逐字为：
                    ````
                    两行之间只放完整文件内容。文件必须以单独一行 --- 开始，其后为 name 和 description，
                    再以单独一行 --- 结束元信息，之后才是正文。不要遗漏第一个 ---。
                    不要加外层 markdown 代码块，不要把开始标记换成 ```markdown。
                    遵守现有权限与沙箱。这些是本轮生成约定，不代表应用已保存或安装技能。
                """.trimIndent()
            }
            val input = if (request.agentId != AgentId.CODEX || prepared.images.isEmpty()) null else AgentInputFiles.create(File(context.filesDir, "agent-inputs"), prepared.images)
            val session = if (request.agentId == AgentId.CLAUDE_CODE) ClaudeControlSession(request.requestId, AgentInputFiles.claudeMessage(prompt, prepared.images)) else null
            control = session
            try {
                val args = AgentCommand.arguments(request, runtime.executable(mode(request.agentId)), prompt, input?.imagePaths.orEmpty(), session != null, approvals = session != null)
                runtime.sdk.executor.executeArgsStreaming(listOf(File(runtime.sdk.vfs.binDir, "node").absolutePath,
                    File(context.filesDir, "gateway.cjs").absolutePath, mode(request.agentId).name) + args,
                    workingDirectory, mapOf("MOBBY_GATEWAY_CONFIG" to config.json()),
                    onStarted = { pid -> started = true; registry.started(pid) },
                    onTerminated = { code -> exit = code; registry.terminated(code) }, input = session?.input
                ).collect { line ->
                    when (line) {
                        is OutputLine.Stdout -> if (session?.onStdout(line.text) != false) output(sanitize(line.text, config), false)
                        is OutputLine.Stderr -> output(sanitize(line.text, config), true)
                        is OutputLine.Exit -> exit = line.code
                    }
                }
            } finally { session?.close(); if (control === session) control = null; input?.close() }
        }
        val watcher = launch { stop.filterNotNull().first(); control?.close(); worker.cancel() }
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
