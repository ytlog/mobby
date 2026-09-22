package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal data class CreatedWorkspace(val owner: String, val workspace: WorkspaceOption)
internal data class ReadingTarget(val conversation: ConversationId, val key: String, val sequence: Long)
internal data class SkillProposalEditor(val proposal: SkillProposal, val value: TextFieldValue = TextFieldValue(proposal.markdown),
    val preview: SkillContent? = null, val busy: Boolean = false, val error: String? = null, val operation: Long = 0)
internal data class SkillProposalSaved(val operation: Long, val ref: String)
internal class ConversationViewModel(val actions: InteractionUseCases) : ViewModel() {
    fun importAttachment(id: ConversationId, workspace: String, location: String) = enqueue {
        actions.importAttachment(id, workspace, location)
    }
    val readingTarget = MutableStateFlow<ReadingTarget?>(null)
    private var readingSequence = 0L
    fun jumpTo(id: ConversationId, hit: SearchHit) {
        enqueue {
            actions.revealTurn(id, hit.turnId)
            readingTarget.value = ReadingTarget(id, hit.targetKey, ++readingSequence)
        }
    }
    fun consumed(target: ReadingTarget) { readingTarget.compareAndSet(target, null) }
    val state = actions.state.stateIn(viewModelScope, SharingStarted.Eagerly, InteractionState())
    val status = actions.status.stateIn(viewModelScope, SharingStarted.Eagerly, SystemStatus())
    val diagnostic = actions.diagnostic.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticOutput(null, emptyList()))
    val composer = MutableStateFlow(ComposerState())
    val agents = MutableStateFlow<List<AgentOption>>(emptyList())
    val gateways = MutableStateFlow<List<GatewayProfile>>(emptyList())
    val skills = MutableStateFlow<List<Skill>>(emptyList())
    val skillsError = MutableStateFlow<String?>(null)
    val skillsLoading = MutableStateFlow(false)
    val plugins = MutableStateFlow<List<Plugin>>(emptyList())
    val pluginsError = MutableStateFlow<String?>(null)
    val pluginsLoading = MutableStateFlow(false)
    val permissionBusy = MutableStateFlow<Set<PermissionKey>>(emptySet())
    val permissionSubmitted = MutableStateFlow<Set<PermissionKey>>(emptySet())
    private val permissionAttempts = mutableMapOf<PermissionKey, PermissionDecision>()
    fun decidePermission(execution: ExecutionId, permission: PermissionRequest, allow: Boolean) {
        val key = PermissionKey(execution, permission.id, permission.revision)
        if (key in permissionBusy.value || key in permissionSubmitted.value) return
        permissionAttempts.keys.removeAll { it.execution != execution }
        val decision = permissionAttempts.getOrPut(key) { PermissionDecision(java.util.UUID.randomUUID().toString(), key, allow) }
        if (decision.allow != allow) { feedback.trySend("上次决定尚待确认，请重试同一决定"); return }
        permissionBusy.value = permissionBusy.value + key
        enqueue {
            try {
                when (val result = actions.resolvePermission(decision)) {
                    OperationResult.Done -> permissionSubmitted.value = permissionSubmitted.value.filter { it.execution == execution }.toSet() + key
                    is OperationResult.Failed -> feedback.send(result.message)
                }
            } finally { permissionBusy.value = permissionBusy.value - key }
        }
    }
    val skillProposal = MutableStateFlow<SkillProposalEditor?>(null)
    val skillProposalSaved = MutableStateFlow<SkillProposalSaved?>(null)
    private val retainedSkillProposals = mutableMapOf<String, SkillProposalEditor>()
    private var proposalOperation = 0L
    fun openSkillProposal(proposal: SkillProposal) {
        val current = skillProposal.value
        if (current?.proposal?.ref == proposal.ref || current?.busy == true) return
        current?.let { retainedSkillProposals[it.proposal.ref] = it }
        val retained = retainedSkillProposals.remove(proposal.ref)
        skillProposal.value = retained?.copy(proposal = proposal) ?: SkillProposalEditor(proposal, operation = ++proposalOperation)
        if (retained == null) validateSkillProposal()
    }
    fun dismissSkillProposal() {
        val editor = skillProposal.value?.takeUnless { it.busy } ?: return
        retainedSkillProposals[editor.proposal.ref] = editor
        skillProposal.value = null
    }
    fun consumeSkillProposalSaved(operation: Long) {
        skillProposalSaved.value?.takeIf { it.operation == operation }?.let { skillProposalSaved.compareAndSet(it, null) }
    }
    fun editSkillProposal(value: TextFieldValue) {
        skillProposal.update { current ->
            current?.takeUnless { it.busy }?.let {
                val changed = value.text != it.value.text
                it.copy(value = value, preview = if (changed) null else it.preview, error = if (changed) null else it.error)
            } ?: current
        }
    }
    fun validateSkillProposal() {
        val editor = skillProposal.value?.takeUnless { it.busy } ?: return
        val operation = ++proposalOperation
        skillProposal.value = editor.copy(busy = true, error = null, operation = operation)
        viewModelScope.launch {
            try {
                val result = actions.previewSkill(editor.value.text)
                skillProposal.update { current -> if (current?.operation != operation) current else when (result) {
                    is DataResult.Loaded -> current.copy(preview = result.value)
                    is DataResult.Failed -> current.copy(preview = null, error = result.message)
                } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { skillProposal.update { if (it?.operation == operation) it.copy(error = "校验未完成，编辑内容已保留，请重试") else it } }
            finally { skillProposal.update { if (it?.operation == operation) it.copy(busy = false) else it } }
        }
    }
    fun saveSkillProposal() {
        val editor = skillProposal.value?.takeUnless { it.busy } ?: return
        if (editor.preview?.issues?.isEmpty() != true) return
        val operation = ++proposalOperation
        skillProposal.value = editor.copy(busy = true, error = null, operation = operation)
        viewModelScope.launch {
            try {
                when (val result = actions.saveSkillProposal(editor.proposal, editor.value.text)) {
                    is DataResult.Loaded -> {
                        retainedSkillProposals.remove(editor.proposal.ref)
                        skillProposal.update { if (it?.operation == operation) null else it }
                        skillProposalSaved.value = SkillProposalSaved(operation, editor.proposal.ref)
                        feedback.send("技能已保存")
                    }
                    is DataResult.Failed -> skillProposal.update { if (it?.operation == operation) it.copy(error = result.message) else it }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { skillProposal.update { if (it?.operation == operation) it.copy(error = "保存结果未确认，编辑内容已保留，请核对技能目录") else it } }
            finally { skillProposal.update { if (it?.operation == operation) it.copy(busy = false) else it } }
        }
    }
    val projectEditor = MutableStateFlow<ProjectEditor?>(null)
    private var projectOperation = 0L
    fun openProject(project: Project?) {
        if (projectEditor.value?.busy == true) return
        projectEditor.value = ProjectEditor(project?.name.orEmpty(), project?.defaultWorkspace ?: "default", project != null, operation = ++projectOperation)
    }
    fun editProject(value: ProjectEditor) {
        projectEditor.update { if (it?.operation == value.operation && !it.busy) value.copy(error = null) else it }
    }
    fun dismissProject() { if (projectEditor.value?.busy != true) projectEditor.value = null }
    fun saveProject() {
        if (workspaceCreating.value) return
        val editor = projectEditor.value?.takeUnless { it.busy } ?: return
        projectEditor.value = editor.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = actions.saveProject(Project(editor.name.trim(), editor.workspace), createOnly = !editor.existing)
                projectEditor.update { current -> if (current?.operation != editor.operation) current else when (result) {
                    OperationResult.Done -> null
                    is OperationResult.Failed -> current.copy(error = result.message)
                } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { projectEditor.update { if (it?.operation == editor.operation) it.copy(error = "项目保存未完成，请核对后重试") else it } }
            finally { projectEditor.update { if (it?.operation == editor.operation) it.copy(busy = false) else it } }
        }
    }
    val workspaces = MutableStateFlow<List<WorkspaceOption>>(emptyList())
    val workspaceError = MutableStateFlow<String?>(null)
    val workspaceCreating = MutableStateFlow(false)
    val workspaceCreated = MutableStateFlow<CreatedWorkspace?>(null)
    private var workspaceQuery = 0L
    fun loadWorkspaces() {
        val query = ++workspaceQuery
        workspaceError.value = null
        viewModelScope.launch {
            try {
                val result = actions.workspaces()
                if (query == workspaceQuery) when (result) {
                    is DataResult.Loaded -> workspaces.value = result.value
                    is DataResult.Failed -> { workspaces.value = emptyList(); workspaceError.value = result.message }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (query == workspaceQuery) workspaceError.value = "工作区读取失败，请重试" }
        }
    }
    fun createWorkspace(name: String, owner: String) {
        if (workspaceCreating.value) return
        workspaceCreating.value = true; workspaceError.value = null
        viewModelScope.launch {
            try {
                when (val result = actions.createWorkspace(name)) {
                    is DataResult.Loaded -> { workspaceCreated.value = CreatedWorkspace(owner, result.value); loadWorkspaces() }
                    is DataResult.Failed -> workspaceError.value = result.message
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { workspaceError.value = "工作区创建结果未确认，请刷新目录核对" }
            finally { workspaceCreating.value = false }
        }
    }
    fun consumeWorkspaceCreated(value: CreatedWorkspace) { workspaceCreated.compareAndSet(value, null) }
    val skillEditor = MutableStateFlow<SkillEditor?>(null)
    val skillEditorSaved = MutableStateFlow<Long?>(null)
    private var editorOperation = 0L
    fun openManualSkill(agent: AgentId) {
        skillEditorSaved.value = null
        skillEditor.value = SkillEditor(agent, manual = true, operation = ++editorOperation)
    }
    fun editSkill(value: SkillEditor) {
        skillEditor.update { current ->
            if (current?.operation == value.operation && !current.busy) value.copy(error = null) else current
        }
    }
    fun consumeSkillEditorSaved(operation: Long) { skillEditorSaved.compareAndSet(operation, null) }
    fun importSkillFile(agent: AgentId, location: String) {
        skillEditorSaved.value = null
        skillEditor.value = SkillEditor(agent, manual = false, operation = ++editorOperation)
        runSkillEditor("文件读取未完成，请重新选择") { editor ->
            when (val result = actions.readSkillImport(location)) {
                is DataResult.Loaded -> editor.copy(name = result.value.name, description = result.value.description,
                    body = result.value.body, markdown = result.value.markdown,
                    preview = result.value.takeIf { it.issues.isEmpty() },
                    error = result.value.issues.joinToString("\n").ifBlank { null })
                is DataResult.Failed -> editor.copy(error = result.message)
            }
        }
    }
    fun validateSkillEditor() = runSkillEditor("校验未完成，编辑内容已保留，请重试") { editor ->
        when (val result = if (editor.manual) actions.previewManualSkill(editor.agent, editor.name, editor.description, editor.body)
            else actions.previewSkill(editor.markdown)) {
            is DataResult.Loaded -> editor.copy(preview = result.value)
            is DataResult.Failed -> editor.copy(error = result.message)
        }
    }
    fun saveSkillEditor() {
        if (skillEditor.value?.preview?.issues?.isEmpty() != true) return
        runSkillEditor("保存结果未确认，编辑内容已保留，请核对技能目录") { editor ->
            when (val result = if (editor.manual) actions.saveManualSkill(editor.agent, editor.name, editor.description, editor.body)
                else actions.importSkill(editor.agent, requireNotNull(editor.preview).markdown)) {
                is DataResult.Loaded -> null
                is DataResult.Failed -> editor.copy(error = result.message)
            }
        }
    }
    // Each operation owns only the editor version it started from. A late import,
    // validation or save cannot replace a newer draft or navigate its page away.
    private fun runSkillEditor(failure: String, action: suspend (SkillEditor) -> SkillEditor?) {
        val original = skillEditor.value?.takeUnless { it.busy } ?: return
        val editor = original.copy(busy = true, error = null, operation = ++editorOperation)
        skillEditor.value = editor
        viewModelScope.launch {
            try {
                val result = action(editor)
                if (skillEditor.value?.operation == editor.operation) {
                    skillEditor.value = result?.copy(busy = false)
                    if (result == null) skillEditorSaved.value = editor.operation
                }
                if (result == null) {
                    loadSkills(catalogueAgent ?: editor.agent)
                    feedback.trySend("技能已保存，可加入本轮草稿")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                skillEditor.update { if (it?.operation == editor.operation) it.copy(error = failure) else it }
            } finally {
                skillEditor.update { if (it?.operation == editor.operation) it.copy(busy = false) else it }
            }
        }
    }
    private var catalogueOperation = 0L
    private var catalogueAgent: AgentId? = null
    fun loadSkills(agent: AgentId) {
        catalogueAgent = agent
        val operation = ++catalogueOperation
        skillsLoading.value = true; skillsError.value = null
        viewModelScope.launch {
            try {
                val result = actions.skills(agent)
                if (operation == catalogueOperation) when (result) {
                    is DataResult.Loaded -> skills.value = result.value
                    is DataResult.Failed -> { skills.value = emptyList(); skillsError.value = result.message }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (operation == catalogueOperation) skillsError.value = "技能目录读取未完成，请重试" }
            finally { if (operation == catalogueOperation) skillsLoading.value = false }
        }
    }
    private var pluginCatalogueOperation = 0L
    fun loadPlugins() {
        val operation = ++pluginCatalogueOperation
        pluginsLoading.value = true; pluginsError.value = null
        viewModelScope.launch {
            try {
                val result = actions.plugins()
                if (operation == pluginCatalogueOperation) when (result) {
                    is DataResult.Loaded -> plugins.value = result.value
                    is DataResult.Failed -> { plugins.value = emptyList(); pluginsError.value = result.message }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (operation == pluginCatalogueOperation) pluginsError.value = "插件目录读取未完成，请重试" }
            finally { if (operation == pluginCatalogueOperation) pluginsLoading.value = false }
        }
    }
    val feedback = Channel<String>(Channel.BUFFERED)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val edits = mutableMapOf<ConversationId, Int>()
    private val minimumRevision = mutableMapOf<ConversationId, Long>()
    init {
        viewModelScope.launch { for (action in queue) safe(action) }
        viewModelScope.launch { state.collect { syncComposer() } }
        viewModelScope.launch { status.map { it.ready }.distinctUntilChanged().collect { safe { refresh() } } }
    }
    private fun syncComposer() {
        val c = state.value.selected?.conversation ?: return
        if (composer.value.conversation != c.id || (edits[c.id] ?: 0) == 0 && c.draft.revision >= (minimumRevision[c.id] ?: 0)) {
            composer.value = composer.value.synchronize(c)
        }
    }
    fun edit(value: TextFieldValue) {
        val id = composer.value.conversation ?: return
        composer.value = ComposerState(id, value)
        edits[id] = (edits[id] ?: 0) + 1
        enqueue {
            try {
                val draft = actions.draft(id, value.text, value.selection.start, value.selection.end)
                minimumRevision[id] = draft.revision
            } finally { edits[id] = (edits[id] ?: 1) - 1; syncComposer() }
        }
    }
    fun insertVoice(original: ComposerState, text: String): Boolean {
        val updated = composer.value.insertVoice(original, text) ?: return false
        edit(updated.value)
        return true
    }
    /** Writes the transcript over the original selection, then sends. A changed draft is left untouched. */
    fun sendVoice(original: ComposerState, text: String): String? {
        if (text.isBlank()) return "未识别到文字，请重新录音"
        if (!insertVoice(original, text)) return "原草稿已改变，未发送语音"
        send()
        return null
    }
    fun enqueue(action: suspend () -> Unit) { queue.trySend(action) }
    fun send() {
        val id = composer.value.conversation ?: return
        enqueue {
            when (val prepared = actions.prepareSend(id)) {
                is PrepareTurnResult.Rejected -> feedback.send(failure(prepared.reason))
                is PrepareTurnResult.Prepared -> viewModelScope.launch { safe {
                    when (val result = actions.sendPrepared(prepared.turn)) {
                        is Submission.Rejected -> feedback.send(failure(result.reason))
                        Submission.Unconfirmed -> feedback.send("请求结果待确认，已保留草稿；请查询原请求")
                        is Submission.Accepted -> Unit
                    }
                } }
            }
        }
    }
    fun stop(id: ExecutionId) = enqueue {
        val result = actions.stop(id)
        if (result is StopResult.Rejected) feedback.send(failure(result.reason))
    }
    suspend fun refresh() { gateways.value = actions.gateways(); agents.value = actions.agents() }
    fun report(result: OperationResult) { if (result is OperationResult.Failed) feedback.trySend(result.message) }
    private suspend fun safe(action: suspend () -> Unit) {
        try { action() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { feedback.send("操作未完成，请检查连接或存储后重试；已有内容已保留") }
    }
    private fun failure(reason: Failure): String = when (reason) {
        Failure.PENDING_ATTACHMENT -> "请先完成或移除待处理附件"
        Failure.INPUT_TOO_LARGE -> "文字与附件合计超出 64 KiB 输入上限（含附件标记），请缩短文字或移除附件；草稿已保留"
        Failure.BUSY -> "已有任务执行中，草稿已保留"
        Failure.INVALID_CONFIG -> "请先检查网关和模型设置"
        Failure.UNSUPPORTED_CAPABILITY -> "当前 Agent 不支持所选能力"
        Failure.EMPTY_DRAFT -> "请先输入任务"
        Failure.PENDING_SUBMISSION -> "上次请求尚未确认，请先查询原请求"
        Failure.UNAVAILABLE -> "运行服务不可用，请检查连接后重试"
    }
}
