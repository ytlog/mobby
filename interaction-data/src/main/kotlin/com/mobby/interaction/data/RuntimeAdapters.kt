package com.mobby.interaction.data

import com.mobby.interaction.domain.*
import com.mobby.runtime.api.*
import com.mobby.interaction.domain.AgentId as DomainAgent
import com.mobby.runtime.api.AgentId as RuntimeAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import java.util.UUID

internal fun RuntimeError.message(): String = when (code) {
    ErrorCode.NOT_READY -> "运行环境尚未就绪"
    ErrorCode.BUSY -> "已有任务正在执行，请等待或前往该会话停止"
    ErrorCode.INVALID_CONFIG -> "网关或模型配置无效，请检查设置"
    ErrorCode.PERMISSION_DENIED -> "权限被拒绝，任务未完成"
    ErrorCode.UNSUPPORTED_CAPABILITY -> "当前 Agent 尚不支持此能力"
    ErrorCode.DISCONNECTED -> "连接中断，结果待确认"
    ErrorCode.RESOURCE_MISSING -> "所需文件不存在或不可读取"
    ErrorCode.STORAGE_FULL -> "存储写入失败，请检查可用空间"
    ErrorCode.TIMEOUT -> "任务执行超时"
    ErrorCode.INTERRUPTED -> "执行中断，结果待确认"
    ErrorCode.STALE_APPROVAL -> "此确认请求已失效"
    ErrorCode.REQUEST_CONFLICT -> "请求标识冲突，未重复执行"
    ErrorCode.INCOMPATIBLE_VERSION -> "运行接口不兼容，请更新应用"
    ErrorCode.NOT_FOUND -> "请求未被接纳，可重新提交"
    ErrorCode.PROTOCOL_ERROR -> "Agent 执行失败或返回了不完整的协议，请展开过程查看详情"
}
internal fun RunPhase.domain(): ExecutionPhase = ExecutionPhase.valueOf(if (this == RunPhase.STARTING) "ACCEPTED" else name)
internal fun RuntimeError.failure() = when (code) {
    ErrorCode.BUSY -> Failure.BUSY
    ErrorCode.UNSUPPORTED_CAPABILITY -> Failure.UNSUPPORTED_CAPABILITY
    ErrorCode.INVALID_CONFIG, ErrorCode.PERMISSION_DENIED -> Failure.INVALID_CONFIG
    else -> Failure.UNAVAILABLE
}
internal fun TurnExecution.request() = RunRequest(RequestId(turnId.value), RuntimeAgent.valueOf(config.agent.name), WorkspaceRef(config.workspace),
    listOf(InputPart.Text(draft.text)) + draft.attachments.map { InputPart.Resource(ResourceRef(it)) }, config.model,
    GatewayProfileRef(config.gatewayProfile, config.gatewayVersion), config.reasoning, session?.let(::SessionRef), draft.capabilities.map(::CapabilityRef).toSet())
internal class RuntimeExecutionAdapter(private val client: RuntimeClient) : ExecutionPort {
    override suspend fun submit(turn: TurnExecution): Submission = try {
        when (val result = client.submit(turn.request())) {
            is SubmitResult.Accepted -> Submission.Accepted(ExecutionId(result.runId.value))
            is SubmitResult.Rejected -> Submission.Rejected(result.error.failure(), result.activeRunId?.let { ExecutionId(it.value) })
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Submission.Unconfirmed }
    override suspend fun lookup(turnId: TurnId): Submission = when (val found = client.findByRequest(RequestId(turnId.value))) {
        is RequestLookup.Found -> Submission.Accepted(ExecutionId(found.runId.value))
        RequestLookup.NotFound -> Submission.Rejected(Failure.UNAVAILABLE)
        RequestLookup.Unavailable -> Submission.Unconfirmed
    }
    override suspend fun cancel(executionId: ExecutionId): StopResult = when (val result = client.cancel(CancelRequest(CommandId(UUID.randomUUID().toString()), RunId(executionId.value)))) {
        CommandResult.Accepted -> StopResult.Accepted
        CommandResult.AlreadyTerminal -> StopResult.AlreadyTerminal
        is CommandResult.Rejected -> StopResult.Rejected(result.error.failure())
    }
    override fun observe(executionId: ExecutionId): Flow<ExecutionFact> = client.observe(RunId(executionId.value)).mapNotNull {
        val snapshot = if (it is RuntimeUpdate.Baseline) it.snapshot else (client.snapshot(RunId(executionId.value)) as? SnapshotResult.Found)?.snapshot
        snapshot?.let { ExecutionFact(executionId, RunProjection.verifiedPhase(it).domain()) }
    }
}
internal class RuntimeSystemAdapter(private val client: RuntimeClient, private val admin: RuntimeAdminClient, private val diagnostics: RuntimeDiagnosticsClient) : SystemPort {
    override val status = combine(client.connection, admin.environment, diagnostics.state) { connection, environment, diagnostic ->
        SystemStatus(environment.phase == EnvironmentPhase.READY, connection == ConnectionState.CONNECTED,
            if (connection == ConnectionState.DISCONNECTED) "连接中断，结果待确认" else environment.summary,
            diagnostic.phase?.let { !it.terminal || it == RunPhase.OUTCOME_UNKNOWN } == true)
    }
    override val diagnostic = diagnostics.state.map { DiagnosticOutput(it.phase?.domain(), it.output) }
    override suspend fun agents(): List<AgentOption> = when (val result = client.capabilities()) {
        is CapabilityResult.Unavailable -> DomainAgent.values().map { AgentOption(it, emptyMap(), result.error.message(), false, emptySet()) }
        is CapabilityResult.Available -> result.capabilities.agents.map { AgentOption(DomainAgent.valueOf(it.agentId.name), it.models.associate { m -> m.id to m.reasoningLevels }, it.unavailableReason?.message(), it.supportsResume, it.skillCapabilities.map { ref -> ref.value }.toSet()) }
    }
    override suspend fun gateways(): List<GatewayProfile> = when (val result = admin.listGatewayProfiles()) {
        is AdminResult.Success -> result.value.map { GatewayProfile(DomainAgent.valueOf(it.agent.name), it.ref.id, it.ref.version, it.endpoint, it.model, it.protocol.name, it.hasCredential) }
        is AdminResult.Failed -> throw IllegalStateException(result.error.message())
    }
    override suspend fun saveGateway(edit: GatewayEdit): OperationResult {
        val secret = edit.credential?.let(::SecretInput)
        edit.credential?.fill('\u0000')
        return admin.saveGatewayProfile(SaveGatewayRequest(RuntimeAgent.valueOf(edit.agent.name), edit.endpoint, edit.model,
            GatewayProtocol.valueOf(edit.protocol), secret)).operation()
    }
    override suspend fun initialize() = admin.initialize().operation()
    override suspend fun shell(command: String) = diagnostics.executeShell(command).operation()
    override suspend fun stopShell() = diagnostics.stopShell().operation()
    private fun AdminResult<*>.operation(): OperationResult = when (this) { is AdminResult.Success -> OperationResult.Done; is AdminResult.Failed -> OperationResult.Failed(error.message()) }
    private fun CommandResult.operation(): OperationResult = when (this) { CommandResult.Accepted, CommandResult.AlreadyTerminal -> OperationResult.Done; is CommandResult.Rejected -> OperationResult.Failed(error.message()) }
}
