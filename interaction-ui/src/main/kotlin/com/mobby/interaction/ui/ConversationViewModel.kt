package com.mobby.interaction.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobby.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal data class ReadingTarget(val conversation: ConversationId, val key: String, val sequence: Long)
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
    val skillEditor = MutableStateFlow<SkillEditor?>(null)
    fun loadSkills(agent: AgentId) = enqueue {
        skillsLoading.value = true; skillsError.value = null
        try {
            when (val result = actions.skills(agent)) {
                is DataResult.Loaded -> skills.value = result.value
                is DataResult.Failed -> { skills.value = emptyList(); skillsError.value = result.message }
            }
        } finally { skillsLoading.value = false }
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
