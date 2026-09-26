package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.deviceinteraction.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/** Single runtime authority, owned by a Service scope; no UI lifecycle or Android dependencies. */
class RunCoordinator(
    private val scope: CoroutineScope,
    private val environment: EnvironmentPort,
    private val process: ProcessPort,
    private val journal: JournalPort,
    private val outputStore: OutputStorePort,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : RuntimeClient {
    private val mutex = Mutex()
    private val wake = MutableStateFlow(0L)
    private val connected = MutableStateFlow(ConnectionState.CONNECTING)
    override val connection = connected.asStateFlow()
    private val activeState = MutableStateFlow<RunId?>(null)
    val active = activeState.asStateFlow()
    private var stop: MutableStateFlow<StopCause?>? = null
    private var activeRequest: RequestId? = null
    private var healthy = true

    /** Called once before admission. Never resumes side effects after host death. */
    suspend fun recover() = mutex.withLock {
        try {
            for (old in journal.unfinished()) {
                if (old.phase.terminal) { journal.releaseRecoveredSlot(old.runId); continue }
                val evidence = TerminalEvidence(null, null, RuntimeError(ErrorCode.INTERRUPTED))
                append(old, RuntimeEvent.RunFinished(RunPhase.INTERRUPTED, evidence),
                    old.copy(phase = RunPhase.INTERRUPTED, terminalEvidence = evidence, pendingApprovals = emptyList(), deviceOperations = old.deviceOperations.map { it.copy(operation = DeviceOperationRules.stopped(it.operation, true)) }))
            }
            connected.value = ConnectionState.CONNECTED
        } catch (_: Exception) { failStorage() }
    }
    suspend fun acquireDiagnostic(id: RunId): Boolean = mutex.withLock {
        if (!healthy || connected.value != ConnectionState.CONNECTED || active.value != null) false
        else { activeState.value = id; true }
    }
    suspend fun releaseDiagnostic(id: RunId, terminationConfirmed: Boolean) = mutex.withLock {
        if (active.value == id && terminationConfirmed) activeState.value = null
    }
    override suspend fun capabilities() = environment.capabilities()

    override suspend fun submit(request: RunRequest): SubmitResult = withContext(NonCancellable) {
        mutex.withLock {
            if (!healthy || connected.value != ConnectionState.CONNECTED) return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true))
            try {
                val frozen = request.copy(inputParts = request.inputParts.toList(), capabilityRefs = request.capabilityRefs.toSortedSet(compareBy { it.value }))
                val digest = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(frozen).toByteArray()).joinToString("") { "%02x".format(it) }
                journal.find(request.requestId)?.let { prior ->
                    return@withLock if (prior.digest != digest) SubmitResult.Rejected(RuntimeError(ErrorCode.REQUEST_CONFLICT))
                    else journal.snapshot(prior.runId)?.let { SubmitResult.Accepted(it.runId, it.acceptedConfig) }
                        ?: SubmitResult.Rejected(RuntimeError(ErrorCode.PROTOCOL_ERROR))
                }
                if (frozen.apiMajor != 1) return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.INCOMPATIBLE_VERSION))
                active.value?.let { return@withLock SubmitResult.Rejected(RuntimeError(ErrorCode.BUSY, true), it) }
                environment.validate(frozen)?.let { return@withLock SubmitResult.Rejected(it) }
                val id = RunId(newId())
                val config = RunConfigSnapshot(frozen.agentId, frozen.workspaceRef, frozen.modelId, frozen.reasoningLevel, frozen.gatewayProfileRef, frozen.capabilityRefs, frozen.requestedOutput)
                val snapshot = RunSnapshot(id, RunPhase.ACCEPTED, 1, 1, config, sessionRef = frozen.sessionRef)
                journal.accept(frozen.requestId, digest, snapshot, envelope(id, 1, RuntimeEvent.RunAccepted(config)))
                val signal = MutableStateFlow<StopCause?>(null)
                stop = signal; activeState.value = id; activeRequest = frozen.requestId; wake.value++
                scope.launch { execute(id, frozen, signal) }
                SubmitResult.Accepted(id, config)
            } catch (_: Exception) { failStorage(); SubmitResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL)) }
        }
    }
    override suspend fun findByRequest(requestId: RequestId): RequestLookup = mutex.withLock {
        try { journal.find(requestId)?.let { RequestLookup.Found(it.runId) } ?: RequestLookup.NotFound }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failStorage(); RequestLookup.Unavailable }
    }
    override suspend fun insert(request: InsertRequest): CommandResult = withContext(NonCancellable) {
        mutex.withLock {
            try {
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(request).toByteArray())
                    .joinToString("") { "%02x".format(it) }
                journal.command(request.commandId)?.let {
                    return@withLock if (it.fingerprint == fingerprint) it.result else CommandResult.Rejected(RuntimeError(ErrorCode.REQUEST_CONFLICT))
                }
                val current = journal.snapshot(request.runId)
                val result = when {
                    request.text.isBlank() || request.text.toByteArray().size > 64 * 1024 -> CommandResult.Rejected(RuntimeError(ErrorCode.INPUT_TOO_LARGE))
                    current == null -> CommandResult.Rejected(RuntimeError(ErrorCode.NOT_FOUND))
                    current.phase.terminal -> CommandResult.AlreadyTerminal
                    active.value != request.runId || activeRequest == null || stop?.value != null ||
                        current.phase !in setOf(RunPhase.RUNNING, RunPhase.AWAITING_APPROVAL) ->
                        CommandResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true))
                    else -> when (runCatching { process.offerInsertion(activeRequest!!, request.text) }.getOrDefault(InsertionOffer.NOT_READY)) {
                        InsertionOffer.ACCEPTED -> CommandResult.Accepted
                        InsertionOffer.NOT_READY -> CommandResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true))
                        InsertionOffer.UNSUPPORTED -> CommandResult.Rejected(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
                    }
                }
                journal.recordCommand(CommandRecord(request.commandId, fingerprint, result))
                result
            } catch (_: Exception) { failStorage(); CommandResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL)) }
        }
    }
    override suspend fun snapshot(runId: RunId): SnapshotResult = mutex.withLock {
        if (!healthy) return@withLock SnapshotResult.Unavailable(RuntimeError(ErrorCode.STORAGE_FULL))
        try { journal.snapshot(runId)?.let { SnapshotResult.Found(it) } ?: SnapshotResult.Unavailable(RuntimeError(ErrorCode.NOT_FOUND)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failStorage(); SnapshotResult.Unavailable(RuntimeError(ErrorCode.STORAGE_FULL)) }
    }
    override suspend fun cancel(request: CancelRequest): CommandResult = withContext(NonCancellable) {
        mutex.withLock {
            try {
                val fingerprint = Json.encodeToString(request)
                journal.command(request.commandId)?.let {
                    return@withLock if (it.fingerprint == fingerprint) it.result else CommandResult.Rejected(RuntimeError(ErrorCode.REQUEST_CONFLICT))
                }
                stopLocked(request.runId, StopCause.USER, request.commandId, fingerprint)
            } catch (_: Exception) { failStorage(); CommandResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL)) }
        }
    }
    suspend fun requestStop(runId: RunId, cause: StopCause): CommandResult = withContext(NonCancellable) {
        mutex.withLock {
            try { stopLocked(runId, cause) }
            catch (_: Exception) { failStorage(); CommandResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL)) }
        }
    }
    private suspend fun stopLocked(runId: RunId, cause: StopCause, commandId: CommandId? = null, fingerprint: String = ""): CommandResult {
        val old = journal.snapshot(runId)
        val result = when {
            old == null -> CommandResult.Rejected(RuntimeError(ErrorCode.NOT_FOUND))
            old.phase.terminal -> CommandResult.AlreadyTerminal
            active.value != runId -> CommandResult.Rejected(RuntimeError(ErrorCode.DISCONNECTED))
            else -> CommandResult.Accepted
        }
        val command = commandId?.let { CommandRecord(it, fingerprint, result) }
        if (result == CommandResult.Accepted && old!!.phase != RunPhase.CANCELLING) {
            append(old, RuntimeEvent.CancellationRequested(CancelReason.USER_REQUEST), old.copy(phase = RunPhase.CANCELLING, pendingApprovals = emptyList()), command)
            stop?.value = cause
        } else if (command != null) journal.recordCommand(command)
        return result
    }
    override suspend fun resolveApproval(request: ApprovalDecision): CommandResult = withContext(NonCancellable) {
        mutex.withLock {
            try {
                val fingerprint = Json.encodeToString(request)
                journal.command(request.commandId)?.let {
                    return@withLock if (it.fingerprint == fingerprint) it.result else CommandResult.Rejected(RuntimeError(ErrorCode.REQUEST_CONFLICT))
                }
                if (!healthy) return@withLock CommandResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL))
                val old = journal.snapshot(request.runId)
                val pending = old?.pendingApprovals?.firstOrNull { it.approvalId == request.approvalId }
                val result = when {
                    old == null -> CommandResult.Rejected(RuntimeError(ErrorCode.NOT_FOUND))
                    active.value != request.runId || activeRequest == null || stop?.value != null ||
                        old.phase != RunPhase.AWAITING_APPROVAL || pending?.revision != request.expectedRevision ->
                        CommandResult.Rejected(RuntimeError(ErrorCode.STALE_APPROVAL))
                    else -> CommandResult.Accepted
                }
                val command = CommandRecord(request.commandId, fingerprint, result)
                if (result != CommandResult.Accepted) journal.recordCommand(command)
                else {
                    val remaining = old!!.pendingApprovals.filterNot { it.approvalId == request.approvalId }
                    append(old, RuntimeEvent.ApprovalResolved(request.approvalId, request.choice),
                        old.copy(pendingApprovals = remaining, phase = if (remaining.isEmpty()) RunPhase.RUNNING else RunPhase.AWAITING_APPROVAL), command)
                    // Persist before handing a decision to the live process. Recovery never replays it.
                    val queued = try { process.offerApproval(activeRequest!!, request.approvalId, request.choice) } catch (_: Exception) { false }
                    if (!queued) stopLocked(request.runId, StopCause.PROTOCOL_FAILURE)
                }
                result
            } catch (_: Exception) { failStorage(); CommandResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL)) }
        }
    }
    override suspend fun respondToDevice(request: DeviceInteractionResponse): CommandResult = withContext(NonCancellable) {
        mutex.withLock {
            try {
                if (!healthy) return@withLock CommandResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL))
                val fingerprint = Json.encodeToString(request)
                val key = CommandId(request.commandId)
                journal.command(key)?.let { return@withLock if (it.fingerprint == fingerprint) it.result else CommandResult.Rejected(RuntimeError(ErrorCode.REQUEST_CONFLICT)) }
                val state = journal.snapshot(RunId(request.runId))
                val record = state?.deviceOperations?.firstOrNull { it.operation.operationId == request.operationId }
                val operation = record?.operation
                val attention = operation?.requiresAttention
                val valid = state != null && !state.phase.terminal && state.phase != RunPhase.CANCELLING && active.value == state.runId &&
                    operation?.revision == request.expectedRevision && attention?.interactionId == request.interactionId &&
                    request.response in attention.allowedResponses
                val result = if (valid) CommandResult.Accepted else CommandResult.Rejected(RuntimeError(ErrorCode.STALE_INTERACTION))
                val command = CommandRecord(key, fingerprint, result)
                if (valid) {
                    // Consume this wait point durably before delivery. Recovery must never replay a response.
                    val consumed = record!!.copy(operation = operation!!.copy(revision = operation.revision + 1,
                        status = DeviceStatus.RUNNING, phaseCode = "response.accepted", requiresAttention = null,
                        availableActions = listOf(DeviceButton.STOP_RUN, DeviceButton.OPEN_CONVERSATION)))
                    append(state!!, RuntimeEvent.DeviceOperationUpdated(consumed), state.copy(deviceOperations = state.deviceOperations.map {
                        if (it.operation.operationId == request.operationId) consumed else it
                    }), command)
                    val delivered = try { process.offerDeviceResponse(request) } catch (_: Exception) { false }
                    if (!delivered) stopLocked(state.runId, StopCause.PROTOCOL_FAILURE)
                } else journal.recordCommand(command)
                result
            } catch (_: Exception) { failStorage(); CommandResult.Rejected(RuntimeError(ErrorCode.STORAGE_FULL)) }
        }
    }

    override suspend fun readArtifact(request: ArtifactReadRequest) = outputStore.read(request)

    override fun observe(runId: RunId, after: EventCursor?): Flow<RuntimeUpdate> = flow {
        val baseline = snapshot(runId)
        if (baseline !is SnapshotResult.Found) { emit(RuntimeUpdate.ResyncRequired(ResyncReason.RUN_NOT_FOUND)); return@flow }
        val current = baseline.snapshot
        if (after != null && (after.runId != runId || after.sequence < 0 || after.sequence > current.lastSequence)) {
            emit(RuntimeUpdate.ResyncRequired(ResyncReason.INVALID_CURSOR)); return@flow
        }
        // A complete current snapshot supersedes earlier deltas, so consumers replace, never append.
        var cursor = current.lastSequence
        emit(RuntimeUpdate.Baseline(current, EventCursor(runId, cursor)))
        wake.collect {
            do {
                val (events, replacement) = mutex.withLock {
                    val batch = journal.eventsAfter(runId, cursor, 128)
                    val latest = checkNotNull(journal.snapshot(runId))
                    val gap = batch.withIndex().any { (index, event) -> event.sequence != cursor + index + 1 } ||
                        (batch.isEmpty() && latest.lastSequence > cursor)
                    batch to latest.takeIf { gap }
                }
                if (replacement != null) {
                    emit(RuntimeUpdate.ResyncRequired(ResyncReason.CURSOR_EXPIRED))
                    cursor = replacement.lastSequence
                    emit(RuntimeUpdate.Baseline(replacement, EventCursor(runId, cursor)))
                } else for (event in events) { emit(RuntimeUpdate.Event(event)); cursor = event.sequence }
            } while (replacement == null && events.size == 128)
        }
    }

    private suspend fun execute(id: RunId, request: RunRequest, signal: StateFlow<StopCause?>) {
        var protocolSuccess: Boolean? = null
        var protocolError: ErrorCode? = null
        var outputBytes = 0
        var chunk = 0L
        var truncated = false
        val decoder = ProtocolDecoder(request.agentId, request.requestedOutput)
        var proposal: String? = null
        val seenApprovals = mutableMapOf<String, String>()
        var eligible = true
        suspend fun emitFact(fact: AgentFact) = mutex.withLock {
            if (!healthy) return@withLock
            val old = journal.snapshot(id) ?: return@withLock
            if (old.phase.terminal) return@withLock
            suspend fun segment(messageId: String, text: String): OutputSegment? {
                val bytes = text.toByteArray().size
                if (outputBytes + bytes > 4 * 1024 * 1024 || chunk >= 2048) {
                    if (!truncated) {
                        truncated = true
                        // This fact may already have persisted ToolStarted before writing its output.
                        val current = journal.snapshot(id)!!
                        append(current, RuntimeEvent.Progress(ProgressNotice.OUTPUT_TRUNCATED), current.copy(progress = ProgressNotice.OUTPUT_TRUNCATED))
                    }
                    return null
                }
                val index = chunk++
                val ref = outputStore.write(id, index.toString(), text)
                outputBytes += bytes
                return OutputSegment(messageId, index, ref)
            }
            when (fact) {
                is AgentFact.Approval -> {
                    if (old.phase == RunPhase.CANCELLING || signal.value != null) return@withLock
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(fact.subject).toByteArray()).joinToString("") { "%02x".format(it) }
                    val previous = seenApprovals[fact.id]
                    if (previous != null) {
                        if (previous != fingerprint || old.pendingApprovals.none { it.approvalId == fact.id })
                            stopLocked(id, StopCause.PROTOCOL_FAILURE)
                    } else if (seenApprovals.size >= 512 || old.pendingApprovals.size >= 16) stopLocked(id, StopCause.PROTOCOL_FAILURE)
                    else {
                        val pending = PendingApproval(fact.id, old.revision + 1, fact.subject)
                        append(old, RuntimeEvent.ApprovalRequired(pending), old.copy(phase = RunPhase.AWAITING_APPROVAL, pendingApprovals = old.pendingApprovals + pending))
                        seenApprovals[fact.id] = fingerprint
                    }
                }
                AgentFact.InvalidApproval -> if (signal.value == null) stopLocked(id, StopCause.PROTOCOL_FAILURE)
                is AgentFact.Session -> if (old.sessionRef?.value != fact.id) append(old, RuntimeEvent.RunStarted(SessionRef(fact.id)), old.copy(sessionRef = SessionRef(fact.id)))
                is AgentFact.Text -> {
                    segment(fact.messageId, fact.text)?.let { part ->
                        append(old, RuntimeEvent.AssistantDelta(part), old.copy(outputSegments = old.outputSegments + part))
                    }
                }
                is AgentFact.Proposal -> if (request.requestedOutput == RequestedOutput.SKILL_PROPOSAL) proposal = fact.markdown
                is AgentFact.Tool -> {
                    val existing = old.steps.firstOrNull { it.stepId == fact.id }
                    if (existing == null) {
                        if (old.steps.size >= 512) return@withLock
                        val order = chunk++
                        val body = fact.body ?: StepBody.Action("tool", "")
                        val tool = ToolSnapshot(fact.id, body, order = order)
                        append(old, RuntimeEvent.ToolStarted(fact.id, body, order), old.copy(steps = old.steps + tool))
                    } else if (fact.body != null && fact.body.supersedes(existing.body)) {
                        val tool = existing.copy(body = fact.body)
                        append(old, RuntimeEvent.ToolStarted(fact.id, fact.body, existing.order), old.copy(steps = old.steps.map { if (it.stepId == fact.id) tool else it }))
                    }
                    if (fact.output != null) segment("tool:${fact.id}", fact.output)?.let { part ->
                        val state = journal.snapshot(id)!!
                        append(state, RuntimeEvent.ToolOutput(fact.id, part), state.copy(steps = state.steps.map { if (it.stepId == fact.id) it.copy(output = it.output + part) else it }))
                    }
                    if (fact.outcome != null) {
                        val state = journal.snapshot(id)!!
                        append(state, RuntimeEvent.ToolFinished(fact.id, fact.outcome), state.copy(steps = state.steps.map { if (it.stepId == fact.id) it.copy(outcome = fact.outcome) else it }))
                    }
                }
                is AgentFact.Diagnostic -> segment("diagnostic:${fact.kind}", fact.text)?.let { part ->
                    append(old, RuntimeEvent.Unknown(fact.kind, part.ref), old.copy(outputSegments = old.outputSegments + part))
                }
                is AgentFact.Completed -> {
                    if (protocolSuccess != false) protocolSuccess = fact.success
                    if (fact.error != null) protocolError = fact.error
                }
            }
        }
        var result: ProcessResult
        try {
            mutex.withLock {
                val old = journal.snapshot(id)!!
                if (old.phase.terminal) eligible = false
                else if (old.phase != RunPhase.CANCELLING) append(old, RuntimeEvent.RunStarted(old.sessionRef), old.copy(phase = RunPhase.RUNNING))
            }
            if (!eligible) return
            result = if (signal.value != null) ProcessResult(null, true) else process.execute(request, signal, devicePort(id) { chunk++ }) { line, stderr ->
                try {
                    if (stderr) emitFact(AgentFact.Diagnostic("stderr", line))
                    else decoder.decode(line).forEach { emitFact(it) }
                } catch (e: Exception) {
                    mutex.withLock { failStorage() }
                    throw e
                }
            }
        } catch (e: CancellationException) {
            result = ProcessResult(null, false, ErrorCode.INTERRUPTED)
        } catch (_: Exception) {
            signal.let { (it as? MutableStateFlow)?.value = StopCause.STORAGE_FAILURE }
            result = ProcessResult(null, false, ErrorCode.PROTOCOL_ERROR)
        }
        withContext(NonCancellable) {
            mutex.withLock {
                try {
                    val old = journal.snapshot(id) ?: return@withLock
                    if (old.phase.terminal) return@withLock
                    val cause = signal.value
                    val phase = when {
                        !result.terminationConfirmed -> RunPhase.OUTCOME_UNKNOWN
                        cause == StopCause.TIMEOUT || result.error == ErrorCode.TIMEOUT -> RunPhase.TIMED_OUT
                        cause == StopCause.HOST_STOP -> RunPhase.INTERRUPTED
                        cause == StopCause.STORAGE_FAILURE || cause == StopCause.PROTOCOL_FAILURE -> RunPhase.FAILED
                        old.phase == RunPhase.CANCELLING -> RunPhase.CANCELLED
                        result.error != null || protocolError != null || old.pendingApprovals.isNotEmpty() -> RunPhase.FAILED
                        old.deviceOperations.any { !it.operation.status.terminal || it.operation.status in setOf(DeviceStatus.UNCONFIRMED, DeviceStatus.INTERRUPTED) } -> RunPhase.OUTCOME_UNKNOWN
                        (result.retained || result.exitCode == 0) && protocolSuccess == true -> RunPhase.SUCCEEDED
                        else -> RunPhase.FAILED
                    }
                    val error = when (phase) {
                        RunPhase.SUCCEEDED, RunPhase.CANCELLED -> null
                        RunPhase.TIMED_OUT -> RuntimeError(ErrorCode.TIMEOUT)
                        RunPhase.INTERRUPTED, RunPhase.OUTCOME_UNKNOWN -> RuntimeError(ErrorCode.INTERRUPTED)
                        else -> RuntimeError(if (cause == StopCause.STORAGE_FAILURE) ErrorCode.STORAGE_FULL else result.error ?: protocolError ?: ErrorCode.PROTOCOL_ERROR)
                    }
                    var terminalBase = old
                    if (phase == RunPhase.SUCCEEDED && request.requestedOutput == RequestedOutput.SKILL_PROPOSAL) {
                        for (content in listOfNotNull(proposal)) {
                            val ref = outputStore.write(id, (chunk++).toString(), content)
                            append(terminalBase, RuntimeEvent.ArtifactAvailable(ref), terminalBase.copy(artifacts = terminalBase.artifacts + ref))
                            terminalBase = journal.snapshot(id)!!
                        }
                    }
                    val evidence = TerminalEvidence(protocolSuccess, result.exitCode, error, result.terminationConfirmed)
                    append(terminalBase, RuntimeEvent.RunFinished(phase, evidence), terminalBase.copy(phase = phase, terminalEvidence = evidence, pendingApprovals = emptyList(), deviceOperations = terminalBase.deviceOperations.map { it.copy(operation = DeviceOperationRules.stopped(it.operation, phase != RunPhase.CANCELLED)) }))
                    if (result.terminationConfirmed) { activeState.value = null; activeRequest = null; stop = null }
                } catch (_: Exception) { failStorage() }
            }
        }
    }
    /** A per-run trusted fact port. CLI output and model text can never call this path. */
    private fun devicePort(id: RunId, order: () -> Long) = object : DeviceOperationPort {
        override suspend fun admit(request: DeviceAdmission): AdmittedDevice = mutex.withLock {
            if (!healthy) deviceFailure(DeviceErrorCode.STORAGE_FULL)
            val old = journal.snapshot(id) ?: deviceFailure(DeviceErrorCode.NOT_FOUND)
            old.deviceOperations.firstOrNull { it.operation.requestId == request.requestId }?.let { record ->
                if (record.fingerprint != request.fingerprint) deviceFailure(DeviceErrorCode.REQUEST_CONFLICT)
                return@withLock AdmittedDevice(record.operation, false)
            }
            if (old.phase.terminal || old.phase == RunPhase.CANCELLING || active.value != id || stop?.value != null)
                deviceFailure(DeviceErrorCode.CANCELLED)
            if (old.deviceOperations.size >= 512) deviceFailure(DeviceErrorCode.UNAVAILABLE, "Device operation limit reached")
            val operation = DeviceOperation(newId(), request.requestId, 1, request.plugin, request.action, request.displayType,
                DeviceStatus.QUEUED, "queued", request.subject, request.input,
                availableActions = listOf(DeviceButton.STOP_RUN, DeviceButton.OPEN_CONVERSATION))
            DeviceOperationRules.validate(operation)
            val record = DeviceRecord(operation, request.fingerprint, order())
            try { append(old, RuntimeEvent.DeviceOperationUpdated(record), old.copy(deviceOperations = old.deviceOperations + record)) }
            catch (error: Exception) { failStorage(); throw DeviceFailure(DeviceError(DeviceErrorCode.STORAGE_FULL)) }
            AdmittedDevice(operation, true)
        }
        override suspend fun find(requestId: String): DeviceOperation? = mutex.withLock {
            journal.snapshot(id)?.deviceOperations?.firstOrNull { it.operation.requestId == requestId }?.operation
        }
        override suspend fun update(operation: DeviceOperation): DeviceOperation = mutex.withLock {
            if (!healthy) deviceFailure(DeviceErrorCode.STORAGE_FULL)
            val old = journal.snapshot(id) ?: deviceFailure(DeviceErrorCode.NOT_FOUND)
            val previous = old.deviceOperations.firstOrNull { it.operation.operationId == operation.operationId }
                ?: deviceFailure(DeviceErrorCode.NOT_FOUND)
            if (old.phase.terminal) return@withLock previous.operation
            val next = if (old.phase == RunPhase.CANCELLING || stop?.value != null) {
                if (!operation.status.terminal) operation.copy(status = DeviceStatus.CANCELLING, phaseCode = "cancelling", requiresAttention = null,
                    availableActions = listOf(DeviceButton.OPEN_CONVERSATION))
                else operation.copy(status = if ((operation.result?.effectState ?: operation.error?.effectState) in setOf(EffectState.UNKNOWN, EffectState.SUBMITTED)) DeviceStatus.UNCONFIRMED else DeviceStatus.CANCELLED,
                    phaseCode = "cancelled", requiresAttention = null, availableActions = listOf(DeviceButton.OPEN_CONVERSATION))
            } else operation
            if (!DeviceOperationRules.advances(previous.operation, next)) return@withLock previous.operation
            val record = previous.copy(operation = next)
            try { append(old, RuntimeEvent.DeviceOperationUpdated(record), old.copy(deviceOperations = old.deviceOperations.map {
                if (it.operation.operationId == operation.operationId) record else it
            })) } catch (error: Exception) { failStorage(); throw DeviceFailure(DeviceError(DeviceErrorCode.STORAGE_FULL)) }
            next
        }
    }

    private suspend fun append(old: RunSnapshot, payload: RuntimeEvent, next: RunSnapshot, command: CommandRecord? = null) {
        val sequence = old.lastSequence + 1
        journal.append(next.copy(lastSequence = sequence, revision = old.revision + 1), envelope(old.runId, sequence, payload), command)
        wake.value++
    }
    private fun envelope(id: RunId, sequence: Long, payload: RuntimeEvent) = EventEnvelope("${id.value}:$sequence", id, sequence, now(), payload)
    private fun failStorage() { healthy = false; connected.value = ConnectionState.DISCONNECTED; stop?.value = StopCause.STORAGE_FAILURE; wake.value++ }
}
