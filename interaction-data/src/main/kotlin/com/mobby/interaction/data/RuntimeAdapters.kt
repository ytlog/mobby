package com.mobby.interaction.data

import com.mobby.interaction.domain.*
import com.mobby.runtime.api.*
import com.mobby.interaction.domain.AgentId as DomainAgent
import com.mobby.runtime.api.AgentId as RuntimeAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import java.util.UUID

internal fun RuntimeError.message(): String = when (code) {
    ErrorCode.INPUT_TOO_LARGE -> "文字与附件合计超出输入上限，请缩短文字或移除附件"
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
    ErrorCode.INPUT_TOO_LARGE -> Failure.INPUT_TOO_LARGE
    ErrorCode.BUSY -> Failure.BUSY
    ErrorCode.UNSUPPORTED_CAPABILITY -> Failure.UNSUPPORTED_CAPABILITY
    ErrorCode.INVALID_CONFIG, ErrorCode.PERMISSION_DENIED -> Failure.INVALID_CONFIG
    else -> Failure.UNAVAILABLE
}
internal fun TurnExecution.request() = RunRequest(RequestId(turnId.value), RuntimeAgent.valueOf(config.agent.name), WorkspaceRef(config.workspace),
    listOf(InputPart.Text(draft.text)) + draft.attachments.map { InputPart.Resource(ResourceRef(it)) }, config.model,
    GatewayProfileRef(config.gatewayProfile, config.gatewayVersion), config.reasoning, session?.let(::SessionRef), draft.capabilities.map(::CapabilityRef).toSet(), requestedOutput = if (creatingSkill) RequestedOutput.SKILL_PROPOSAL else RequestedOutput.TEXT)
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
internal class RuntimeSystemAdapter(private val context: android.content.Context, private val client: RuntimeClient, private val admin: RuntimeAdminClient, private val diagnostics: RuntimeDiagnosticsClient) : SystemPort {
    private val grants = context.getSharedPreferences("attachment-grants", android.content.Context.MODE_PRIVATE)
    private val grantLock = Any()
    override suspend fun retainAttachmentGrants(locations: Set<String>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = context.contentResolver
        val owned = synchronized(grantLock) { grants.getStringSet("owned", emptySet())!!.toSet() }
        resolver.persistedUriPermissions.filter { it.uri.toString() in owned && it.uri.toString() !in locations }.forEach { grant ->
            try { resolver.releasePersistableUriPermission(grant.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Provider already revoked the grant. */ }
        }
        synchronized(grantLock) { check(grants.edit().putStringSet("owned", owned.intersect(locations)).commit()) }
    }
    override suspend fun attachment(workspace: String, ref: String): DataResult<Attachment> = when (val result = admin.resource(ResourceRef(ref), WorkspaceRef(workspace))) {
        is AdminResult.Success -> DataResult.Loaded(Attachment(result.value.ref.value, result.value.name, result.value.sizeBytes))
        is AdminResult.Failed -> DataResult.Failed(result.error.message())
    }
    override suspend fun importAttachment(workspace: String, location: String): DataResult<Attachment> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val uri = android.net.Uri.parse(location)
            require(uri.scheme == "content")
            val resolver = context.contentResolver
            // Some providers offer only a transient grant. Import still works now; a later
            // retry reports permission loss explicitly and asks the user to select again.
            try {
                val alreadyGranted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (!alreadyGranted) synchronized(grantLock) { check(grants.edit().putStringSet("owned", grants.getStringSet("owned", emptySet())!!.toSet() + location).commit()) }
            }
            catch (_: SecurityException) { /* transient grant remains valid for this import */ }
            val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "文本附件"
            val bytes = java.io.ByteArrayOutputStream()
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; bytes.write(buffer, 0, n); require(bytes.size() <= 32 * 1024) }
            } ?: return@withContext DataResult.Failed("无法读取所选文件")
            when (val result = admin.importResource(ImportResourceRequest(WorkspaceRef(workspace), name, bytes.toByteArray()))) {
                is AdminResult.Success -> DataResult.Loaded(Attachment(result.value.ref.value, result.value.name, result.value.sizeBytes))
                is AdminResult.Failed -> DataResult.Failed(if (result.error.code in setOf(ErrorCode.INVALID_CONFIG, ErrorCode.UNSUPPORTED_CAPABILITY)) "请选择不超过 32 KiB 的 UTF-8 文本文件，不支持图片、PDF 或二进制文件" else result.error.message())
            }
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { DataResult.Failed("文件授权失效，请重新选择") }
        catch (_: Exception) { DataResult.Failed("文件读取失败或超过 32 KiB，请重试或移除") }
    }
    private fun SkillSummary.domain() = Skill(ref.value, DomainAgent.valueOf(agent.name), name, description, if (source == SkillSource.USER) "用户技能" else "CLI 内置", available, error?.let { "技能元信息、目录名称或文件路径无效" })
    private fun SkillPreview.domain() = SkillContent(name, description, body, markdown, issues.map { when (it) {
        SkillIssue.INVALID_FRONTMATTER -> "YAML 元信息无效，请修正后再导入"
        SkillIssue.UNCLOSED_FRONTMATTER -> "元信息缺少结束分隔符 ---"
        SkillIssue.INVALID_NAME -> "名称须为 1–63 位小写字母、数字或连字符，不能使用 synced"
        SkillIssue.INVALID_DESCRIPTION -> "请填写不超过 1024 字的用途和触发场景"
        SkillIssue.EMPTY_BODY -> "技能正文不能为空"
    } })
    private fun <T, R> AdminResult<T>.result(map: (T) -> R): DataResult<R> = when (this) {
        is AdminResult.Success -> DataResult.Loaded(map(value))
        is AdminResult.Failed -> DataResult.Failed(when (error.code) {
            ErrorCode.REQUEST_CONFLICT -> "同名技能已存在，请修改名称；原技能未被覆盖"
            ErrorCode.INVALID_CONFIG -> "技能内容或元信息无效，请检查名称、用途、正文和文件大小"
            ErrorCode.RESOURCE_MISSING -> "技能文件已改变或不可读取，请刷新列表"
            else -> error.message()
        })
    }
    override suspend fun skills(agent: DomainAgent) = admin.listSkills(RuntimeAgent.valueOf(agent.name)).result { list -> list.map { it.domain() } }
    override suspend fun readSkill(ref: String) = admin.readSkill(CapabilityRef(ref)).result { it.domain() }
    override suspend fun previewSkill(markdown: String) = admin.previewSkill(markdown).result { it.domain() }
    override suspend fun previewManualSkill(agent: DomainAgent, name: String, description: String, body: String) = admin.previewManualSkill(ManualSkillRequest(RuntimeAgent.valueOf(agent.name), name, description, body)).result { it.domain() }
    override suspend fun importSkill(agent: DomainAgent, markdown: String) = admin.importSkill(RuntimeAgent.valueOf(agent.name), markdown).result { it.domain() }
    override suspend fun saveManualSkill(agent: DomainAgent, name: String, description: String, body: String) = admin.saveManualSkill(ManualSkillRequest(RuntimeAgent.valueOf(agent.name), name, description, body)).result { it.domain() }
    override suspend fun readSkillImport(location: String): DataResult<SkillContent> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val uri = android.net.Uri.parse(location)
            require(uri.scheme == "content")
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (name?.endsWith(".md", true) != true) return@withContext DataResult.Failed("请选择 .md 技能文件")
            val bytes = java.io.ByteArrayOutputStream()
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; bytes.write(buffer, 0, n); require(bytes.size() <= 128 * 1024) }
            } ?: return@withContext DataResult.Failed("无法读取所选文件")
            previewSkill(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString())
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { DataResult.Failed("文件读取权限已失效，请重新选择") }
        catch (_: Exception) { DataResult.Failed("无法导入：请选择不超过 128 KiB 的 UTF-8 Markdown 文件") }
    }
    override val status = combine(client.connection, admin.environment, diagnostics.state) { connection, environment, diagnostic ->
        SystemStatus(environment.phase == EnvironmentPhase.READY, connection == ConnectionState.CONNECTED,
            if (connection == ConnectionState.DISCONNECTED) "连接中断，结果待确认" else environment.summary,
            diagnostic.phase?.let { !it.terminal || it == RunPhase.OUTCOME_UNKNOWN } == true)
    }
    override val diagnostic = diagnostics.state.map { DiagnosticOutput(it.phase?.domain(), it.output) }
    override suspend fun agents(): List<AgentOption> = when (val result = client.capabilities()) {
        is CapabilityResult.Unavailable -> DomainAgent.values().map { AgentOption(it, emptyMap(), result.error.message(), false, emptySet()) }
        is CapabilityResult.Available -> result.capabilities.agents.map { AgentOption(DomainAgent.valueOf(it.agentId.name), it.models.associate { m -> m.id to m.reasoningLevels }, it.unavailableReason?.message(), it.supportsResume, it.skillCapabilities.map { ref -> ref.value }.toSet(), it.supportsResources) }
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
