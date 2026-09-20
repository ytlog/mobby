package com.mobby.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobby.runtime.api.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Temporary diagnostic UI; execution belongs exclusively to the new Runtime service. */
enum class ConsoleMode(val label: String, val agent: AgentId?) { CODEX("Codex", AgentId.CODEX), CLAUDE("Claude Code", AgentId.CLAUDE_CODE), SHELL("Shell 诊断", null) }
data class OutputItem(val id: Long, val text: String, val error: Boolean = false)
data class RuntimeState(val ready: Boolean = false, val busy: Boolean = false, val dependenciesReady: Boolean = false,
    val initializing: Boolean = true, val status: String = "初始化中", val output: List<OutputItem> = emptyList())
data class ConsoleUiState(val input: String = "", val mode: ConsoleMode = ConsoleMode.CODEX, val runtime: RuntimeState = RuntimeState())

class TestConsoleViewModel(application: Application) : AndroidViewModel(application) {
    private val host = (application as MobbyApplication).runtime
    private val mutableState = MutableStateFlow(ConsoleUiState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var active: RunId? = null
    private var draftRevision = 0L
    private var submitting = false
    private val segments = mutableMapOf<ResourceRef, String>()
    init {
        viewModelScope.launch { host.admin.environment.collect { env ->
            mutableState.update { it.copy(runtime = it.runtime.copy(ready = env.phase == EnvironmentPhase.READY,
                dependenciesReady = env.phase == EnvironmentPhase.READY, initializing = env.phase == EnvironmentPhase.INITIALIZING,
                status = if (it.runtime.busy) it.runtime.status else env.summary)) }
        } }
        viewModelScope.launch { host.client.connection.collect { connection ->
            if (connection == ConnectionState.DISCONNECTED) mutableState.update { it.copy(runtime = it.runtime.copy(ready = false, status = "连接中断，结果待确认")) }
        } }
        viewModelScope.launch { host.diagnostics.state.collect { diagnostic ->
            if (state.value.mode == ConsoleMode.SHELL && diagnostic.phase != null) mutableState.update { it.copy(runtime = it.runtime.copy(
                busy = !diagnostic.phase!!.terminal, status = label(diagnostic.phase!!), output = diagnostic.output.mapIndexed { index, text -> OutputItem(index.toLong(), text) })) }
        } }
    }
    fun input(value: String) { draftRevision++; mutableState.update { it.copy(input = value) } }
    fun mode(value: ConsoleMode) { if (!state.value.runtime.busy && !submitting) mutableState.update { it.copy(mode = value) } }
    fun send() {
        if (submitting) return
        val current = state.value
        val revision = draftRevision
        if (current.input.isBlank()) return
        submitting = true
        viewModelScope.launch {
            try {
                if (current.mode == ConsoleMode.SHELL) {
                    val result = host.diagnostics.executeShell(current.input)
                    if (result == CommandResult.Accepted && revision == draftRevision) input("")
                    if (result is CommandResult.Rejected) notice(result.error.code.name)
                    return@launch
                }
                val profiles = host.admin.listGatewayProfiles()
                val profile = (profiles as? AdminResult.Success)?.value?.firstOrNull { it.agent == current.mode.agent }
                if (profile == null) { notice("无法读取网关配置"); return@launch }
                val request = RunRequest(RequestId(UUID.randomUUID().toString()), current.mode.agent!!, WorkspaceRef("default"),
                    listOf(InputPart.Text(current.input)), profile.model, profile.ref)
                when (val result = host.client.submit(request)) {
                    is SubmitResult.Rejected -> notice("请求未接纳：${result.error.code}")
                    is SubmitResult.Accepted -> {
                        if (revision == draftRevision) input("")
                        active = result.runId
                        segments.clear()
                        observation?.cancel()
                        observation = viewModelScope.launch {
                            host.client.observe(result.runId).collect { update ->
                                val snapshot = if (update is RuntimeUpdate.Baseline) update.snapshot
                                    else (host.client.snapshot(result.runId) as? SnapshotResult.Found)?.snapshot
                                if (snapshot != null) render(snapshot)
                            }
                        }
                    }
                }
            } finally { submitting = false }
        }
    }
    private suspend fun render(snapshot: RunSnapshot) {
        val all = snapshot.outputSegments + snapshot.steps.flatMap { it.output }
        for (part in all) if (part.ref !in segments) {
            val bytes = ByteArrayOutputStream()
            var offset: Long? = 0
            do {
                val read = host.client.readArtifact(ArtifactReadRequest(part.ref, offset!!, 65536))
                if (read !is ArtifactReadResult.Chunk) break
                bytes.write(read.bytes.toByteArray()); offset = read.nextOffset
            } while (offset != null)
            segments[part.ref] = bytes.toString("UTF-8")
        }
        val lines = all.sortedBy { it.chunkIndex }.map { OutputItem(it.chunkIndex, segments[it.ref].orEmpty(), it.messageId.startsWith("diagnostic:")) }
        mutableState.update { it.copy(runtime = it.runtime.copy(busy = !snapshot.phase.terminal || snapshot.phase == RunPhase.OUTCOME_UNKNOWN, status = label(snapshot.phase), output = lines)) }
    }
    fun stop() { viewModelScope.launch {
        if (state.value.mode == ConsoleMode.SHELL) host.diagnostics.stopShell()
        else active?.let { host.client.cancel(CancelRequest(CommandId(UUID.randomUUID().toString()), it)) }
    } }
    fun clear() { mutableState.update { it.copy(runtime = it.runtime.copy(output = emptyList())) } }
    fun retry() { viewModelScope.launch { host.admin.initialize() } }
    private fun notice(text: String) { mutableState.update { it.copy(runtime = it.runtime.copy(status = text)) } }
    private fun label(phase: RunPhase): String = when (phase) {
        RunPhase.ACCEPTED, RunPhase.STARTING -> "准备执行"
        RunPhase.RUNNING -> "执行中"
        RunPhase.AWAITING_APPROVAL -> "待确认"
        RunPhase.CANCELLING -> "停止中"
        RunPhase.SUCCEEDED -> "完成"
        RunPhase.FAILED -> "失败"
        RunPhase.CANCELLED -> "已停止"
        RunPhase.TIMED_OUT -> "超时"
        RunPhase.INTERRUPTED -> "异常中断"
        RunPhase.OUTCOME_UNKNOWN -> "结果待确认"
    }
}
