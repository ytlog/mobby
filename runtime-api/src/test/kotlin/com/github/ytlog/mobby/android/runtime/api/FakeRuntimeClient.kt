package com.github.ytlog.mobby.android.runtime.api

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Test-only protocol oracle. In-memory admission is NOT production durability or CLI execution. */
internal class FakeRuntimeClient : RuntimeClient {
    private val mutex = Mutex()
    override val connection = MutableStateFlow(ConnectionState.CONNECTED)
    private data class Journal(val snapshots: List<RunSnapshot>, val events: List<EventEnvelope>)
    private val requests = mutableMapOf<RequestId, Pair<RunRequest, RunId>>()
    private val journals = mutableMapOf<RunId, MutableStateFlow<Journal>>()
    private val decisions = mutableMapOf<CommandId, Pair<Any, CommandResult>>()
    private var active: RunId? = null
    private var nextId = 0
    var starts = 0; private set
    override suspend fun capabilities(): CapabilityResult = CapabilityResult.Available(RuntimeCapabilities("fake", listOf(
        AgentCapability(AgentId.CODEX, listOf(ModelCapability("test-model", setOf("low"))), supportsApproval = true)
    )))
    override suspend fun submit(request: RunRequest): SubmitResult = mutex.withLock {
        requests[request.requestId]?.let { (prior, id) ->
            return@withLock if (prior == request) SubmitResult.Accepted(id, current(id).acceptedConfig)
            else SubmitResult.Rejected(RuntimeError(ErrorCode.REQUEST_CONFLICT))
        }
        if (request.apiMajor != 1) return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.INCOMPATIBLE_VERSION))
        active?.let { return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.BUSY, true), it) }
        if (request.agentId != AgentId.CODEX || request.capabilityRefs.isNotEmpty() || request.inputParts.any { it is InputPart.Resource })
            return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
        if (request.modelId != "test-model" || request.reasoningLevel !in listOf(null, "low") ||
            request.inputParts.isEmpty() || request.inputParts.filterIsInstance<InputPart.Text>().all { it.text.isBlank() })
            return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.INVALID_CONFIG))
        // Detach collections from caller-owned state before accepting the request.
        val frozen = request.copy(inputParts = request.inputParts.toList(), capabilityRefs = request.capabilityRefs.toSet())
        val id = RunId("run-${++nextId}")
        val config = RunConfigSnapshot(frozen.agentId, frozen.workspaceRef, frozen.modelId, frozen.reasoningLevel,
            frozen.gatewayProfileRef, frozen.capabilityRefs)
        val snapshot = RunSnapshot(id, RunPhase.ACCEPTED, 1, 1, config)
        val event = EventEnvelope("${id.value}:1", id, 1, 0, RuntimeEvent.RunAccepted(config))
        journals[id] = MutableStateFlow(Journal(listOf(snapshot), listOf(event)))
        requests[frozen.requestId] = frozen to id
        active = id
        starts++
        SubmitResult.Accepted(id, config)
    }
    override suspend fun findByRequest(requestId: RequestId): RequestLookup = mutex.withLock {
        requests[requestId]?.let { RequestLookup.Found(it.second) } ?: RequestLookup.NotFound
    }
    override suspend fun cancel(request: CancelRequest): CommandResult = mutex.withLock {
        command(request.commandId, request) {
            val state = journals[request.runId]?.value?.snapshots?.last()
                ?: return@command CommandResult.Rejected(RuntimeError(ErrorCode.NOT_FOUND))
            if (state.phase.terminal) return@command CommandResult.AlreadyTerminal
            if (state.phase != RunPhase.CANCELLING) append(request.runId, RuntimeEvent.CancellationRequested,
                state.copy(phase = RunPhase.CANCELLING, pendingApprovals = emptyList()))
            CommandResult.Accepted
        }
    }
    override suspend fun resolveApproval(request: ApprovalDecision): CommandResult = mutex.withLock {
        command(request.commandId, request) {
            val state = journals[request.runId]?.value?.snapshots?.last()
                ?: return@command CommandResult.Rejected(RuntimeError(ErrorCode.NOT_FOUND))
            if (state.phase != RunPhase.AWAITING_APPROVAL || state.pendingApprovals.none {
                it.approvalId == request.approvalId && it.revision == request.expectedRevision
            }) return@command CommandResult.Rejected(RuntimeError(ErrorCode.STALE_APPROVAL))
            append(request.runId, RuntimeEvent.ApprovalResolved(request.approvalId, request.choice),
                state.copy(phase = RunPhase.RUNNING, pendingApprovals = emptyList()))
            CommandResult.Accepted
        }
    }
    private fun command(id: CommandId, request: Any, action: () -> CommandResult): CommandResult {
        decisions[id]?.let { return if (it.first == request) it.second else CommandResult.Rejected(RuntimeError(ErrorCode.REQUEST_CONFLICT)) }
        return action().also { decisions[id] = request to it }
    }
    override suspend fun snapshot(runId: RunId): SnapshotResult = mutex.withLock {
        journals[runId]?.value?.snapshots?.last()?.let { SnapshotResult.Found(it) }
            ?: SnapshotResult.Unavailable(RuntimeError(ErrorCode.NOT_FOUND))
    }
    override fun observe(runId: RunId, after: EventCursor?): Flow<RuntimeUpdate> = flow {
        val journal = mutex.withLock { journals[runId] }
        if (journal == null) { emit(RuntimeUpdate.ResyncRequired(ResyncReason.RUN_NOT_FOUND)); return@flow }
        val initial = journal.value
        val base = if (after == null) initial.snapshots.last() else {
            if (after.runId != runId || after.sequence !in 1..initial.snapshots.last().lastSequence) {
                emit(RuntimeUpdate.ResyncRequired(ResyncReason.INVALID_CURSOR)); return@flow
            }
            initial.snapshots.first { it.lastSequence == after.sequence }
        }
        var cursor = base.lastSequence
        emit(RuntimeUpdate.Baseline(base, EventCursor(runId, cursor)))
        // StateFlow only wakes readers; the full journal provides lossless replay even if wakes coalesce.
        journal.collect { record ->
            record.events.filter { it.sequence > cursor }.forEach {
                emit(RuntimeUpdate.Event(it)); cursor = it.sequence
            }
        }
    }
    override suspend fun readArtifact(request: ArtifactReadRequest): ArtifactReadResult =
        ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING))

    suspend fun requireApproval(id: RunId, approval: PendingApproval) = mutex.withLock {
        check(!current(id).phase.terminal && current(id).phase != RunPhase.CANCELLING)
        append(id, RuntimeEvent.ApprovalRequired(approval), current(id).copy(phase = RunPhase.AWAITING_APPROVAL, pendingApprovals = listOf(approval)))
    }
    suspend fun output(id: RunId, segment: OutputSegment) = mutex.withLock {
        check(!current(id).phase.terminal)
        append(id, RuntimeEvent.AssistantDelta(segment), current(id).copy(outputSegments = current(id).outputSegments + segment))
    }
    suspend fun finish(id: RunId, evidence: TerminalEvidence) = mutex.withLock {
        val state = current(id)
        if (state.phase.terminal) return@withLock
        val phase = when {
            evidence.exitCode == null -> RunPhase.OUTCOME_UNKNOWN
            state.phase == RunPhase.CANCELLING -> RunPhase.CANCELLED
            evidence.error?.code == ErrorCode.TIMEOUT -> RunPhase.TIMED_OUT
            evidence.error != null -> RunPhase.FAILED
            evidence.protocolSucceeded == true && evidence.exitCode == 0 -> RunPhase.SUCCEEDED
            else -> RunPhase.FAILED
        }
        append(id, RuntimeEvent.RunFinished(phase, evidence), state.copy(phase = phase, terminalEvidence = evidence, pendingApprovals = emptyList()))
        // Unknown exit cannot safely release the global execution slot.
        if (evidence.exitCode != null) active = null
    }
    private fun current(id: RunId) = journals.getValue(id).value.snapshots.last()
    private fun append(id: RunId, event: RuntimeEvent, snapshot: RunSnapshot) {
        val flow = journals.getValue(id)
        val sequence = current(id).lastSequence + 1
        flow.value = Journal(flow.value.snapshots + snapshot.copy(revision = sequence, lastSequence = sequence),
            flow.value.events + EventEnvelope("${id.value}:$sequence", id, sequence, 0, event))
    }
}
