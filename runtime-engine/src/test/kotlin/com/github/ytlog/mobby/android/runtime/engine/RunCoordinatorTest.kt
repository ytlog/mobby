package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.deviceinteraction.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunCoordinatorTest {
    private fun request(id: String = "request") = RunRequest(RequestId(id), AgentId.CODEX, WorkspaceRef("default"),
        listOf(InputPart.Text("hello")), "model", GatewayProfileRef("CODEX", 0))
    private val environment = object : EnvironmentPort {
        override suspend fun capabilities() = CapabilityResult.Available(RuntimeCapabilities("test", emptyList()))
        override suspend fun validate(request: RunRequest): RuntimeError? = null
    }
    private class MemoryJournal : JournalPort {
        val requests = mutableMapOf<RequestId, RequestRecord>()
        val states = mutableMapOf<RunId, RunSnapshot>()
        val events = mutableListOf<EventEnvelope>()
        var broken = false
        val commands = mutableMapOf<CommandId, CommandRecord>()
        override suspend fun command(id: CommandId) = commands[id]
        override suspend fun recordCommand(command: CommandRecord) { commands[command.id] = command }
        override suspend fun find(requestId: RequestId) = requests[requestId]
        override suspend fun accept(requestId: RequestId, digest: String, snapshot: RunSnapshot, event: EventEnvelope) {
            check(!broken)
            check(states.values.none { !it.phase.terminal })
            requests[requestId] = RequestRecord(digest, snapshot.runId); states[snapshot.runId] = snapshot; events += event
        }
        override suspend fun append(snapshot: RunSnapshot, event: EventEnvelope, command: CommandRecord?) {
            check(!broken)
            check(states.getValue(snapshot.runId).lastSequence + 1 == snapshot.lastSequence)
            states[snapshot.runId] = snapshot; events += event
            if (command != null) recordCommand(command)
        }
        override suspend fun snapshot(runId: RunId) = states[runId]
        override suspend fun eventsAfter(runId: RunId, sequence: Long, limit: Int) = events.filter { it.runId == runId && it.sequence > sequence }.take(limit)
        override suspend fun unfinished() = states.values.filter { !it.phase.terminal || it.phase == RunPhase.OUTCOME_UNKNOWN && it.terminalEvidence?.terminationConfirmed != true }
    }
    private class MemoryOutput : OutputStorePort {
        val content = mutableMapOf<ResourceRef, String>()
        override suspend fun write(runId: RunId, name: String, text: String) = ResourceRef("${runId.value}/$name").also { content[it] = text }
        override suspend fun read(request: ArtifactReadRequest) = ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING))
    }
    private fun process(block: suspend (RunRequest, StateFlow<StopCause?>, suspend (String, Boolean) -> Unit) -> ProcessResult) = object : ProcessPort {
        override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, devices: DeviceOperationPort, output: suspend (String, Boolean) -> Unit) = block(request, stop, output)
    }
    @Test fun `insertion targets only the live run and a command is delivered once`() = runTest {
        val journal = MemoryJournal()
        val exit = CompletableDeferred<Unit>()
        val inserted = mutableListOf<String>()
        val process = object : ProcessPort {
            override fun offerInsertion(requestId: RequestId, text: String): InsertionOffer {
                if (requestId != RequestId("request")) return InsertionOffer.NOT_READY
                inserted += text
                return InsertionOffer.ACCEPTED
            }
            override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, devices: DeviceOperationPort,
                output: suspend (String, Boolean) -> Unit): ProcessResult {
                exit.await()
                output("""{"type":"turn.completed"}""", false)
                return ProcessResult(0, true)
            }
        }
        val runtime = RunCoordinator(backgroundScope, environment, process, journal, MemoryOutput())
        runtime.recover()
        val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
        runCurrent()
        val command = InsertRequest(CommandId("message-1"), id, "more context")
        assertEquals(CommandResult.Accepted, runtime.insert(command))
        assertEquals(CommandResult.Accepted, runtime.insert(command))
        assertEquals(listOf("more context"), inserted)
        assertEquals(ErrorCode.REQUEST_CONFLICT,
            (runtime.insert(command.copy(text = "changed")) as CommandResult.Rejected).error.code)
        assertEquals(ErrorCode.NOT_FOUND,
            (runtime.insert(InsertRequest(CommandId("other"), RunId("missing"), "text")) as CommandResult.Rejected).error.code)
        exit.complete(Unit); runCurrent()
        assertEquals(CommandResult.AlreadyTerminal, runtime.insert(InsertRequest(CommandId("after"), id, "late")))
        assertEquals(listOf("more context"), inserted)
    }
    @Test fun `device facts are durable before dispatch and duplicate admission never dispatches again`() = runTest {
        val journal = MemoryJournal()
        val process = object : ProcessPort {
            override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, devices: DeviceOperationPort, output: suspend (String, Boolean) -> Unit): ProcessResult {
                val admission = DeviceAdmission("device-request", "digest", "screen", "snapshot", "screen_control", DeviceSubject(), kotlinx.serialization.json.JsonObject(emptyMap()))
                val admitted = devices.admit(admission)
                assertTrue(admitted.dispatch)
                assertEquals(admitted.operation, journal.states.values.single().deviceOperations.single().operation)
                assertFalse(devices.admit(admission).dispatch)
                val running = devices.update(admitted.operation.copy(revision = 2, status = DeviceStatus.RUNNING, phaseCode = "screen.snapshot"))
                devices.update(running.copy(revision = 3, status = DeviceStatus.SUCCEEDED, phaseCode = "succeeded",
                    availableActions = emptyList(), result = DeviceResult("screen_observation", EffectState.NONE, kotlinx.serialization.json.JsonObject(emptyMap()))))
                output("""{"type":"turn.completed"}""", false)
                return ProcessResult(0, true)
            }
        }
        val runtime = RunCoordinator(backgroundScope, environment, process, journal, MemoryOutput())
        runtime.recover()
        val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
        runCurrent()
        assertEquals(RunPhase.SUCCEEDED, journal.states.getValue(id).phase)
        assertEquals(3, journal.events.count { it.payload is RuntimeEvent.DeviceOperationUpdated })
        assertEquals(1, journal.states.getValue(id).deviceOperations.size)
    }
    @Test fun `CLI success cannot hide an unfinished device action`() = runTest {
        val journal = MemoryJournal()
        val runtime = RunCoordinator(backgroundScope, environment, object : ProcessPort {
            override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, devices: DeviceOperationPort, output: suspend (String, Boolean) -> Unit): ProcessResult {
                devices.admit(DeviceAdmission("capture", "digest", "camera", "photo", "capture", DeviceSubject(), kotlinx.serialization.json.JsonObject(emptyMap())))
                output("""{"type":"turn.completed"}""", false)
                return ProcessResult(0, true)
            }
        }, journal, MemoryOutput())
        runtime.recover()
        val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
        runCurrent()
        assertEquals(RunPhase.OUTCOME_UNKNOWN, journal.states.getValue(id).phase)
        assertEquals(DeviceStatus.INTERRUPTED, journal.states.getValue(id).deviceOperations.single().operation.status)
    }
    @Test fun `first completed tool beyond output budget preserves its result and terminal evidence`() = runTest {
        val journal = MemoryJournal(); val output = MemoryOutput()
        val runtime = RunCoordinator(backgroundScope, environment, process { _, _, emit ->
            val body = "x".repeat(65536)
            repeat(64) { index -> emit("""{"type":"item.completed","item":{"id":"message-$index","type":"agent_message","text":"$body"}}""", false) }
            emit("""{"type":"item.completed","item":{"id":"tool","type":"command_execution","command":"fixture","aggregated_output":"over budget","exit_code":0}}""", false)
            emit("""{"type":"turn.completed"}""", false)
            ProcessResult(0, true)
        }, journal, output)
        runtime.recover()
        val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
        runCurrent()
        val snapshot = journal.states.getValue(id)
        assertEquals(RunPhase.SUCCEEDED, snapshot.phase)
        assertEquals(ToolOutcome.SUCCEEDED, snapshot.steps.single().outcome)
        assertTrue(snapshot.steps.single().output.isEmpty())
        assertEquals(1, journal.events.count { it.payload is RuntimeEvent.Progress })
        assertEquals(ProgressNotice.OUTPUT_TRUNCATED, snapshot.progress)
        assertEquals(4 * 1024 * 1024, output.content.values.sumOf { it.toByteArray().size })
        assertEquals(ConnectionState.CONNECTED, runtime.connection.value)
    }
    @Test fun `native permission request is durable and survives observer reconnect`() = runTest {
        val journal = MemoryJournal()
        val runtime = RunCoordinator(backgroundScope, environment, process { _, signal, emit ->
            emit("""{"type":"control_request","request_id":"approval-1","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"file_path":"/workspace/approved.txt","content":"fixture"}}}""", false)
            signal.filterNotNull().first(); ProcessResult(143, true)
        }, journal, MemoryOutput())
        runtime.recover()
        val id = (runtime.submit(request().copy(agentId = AgentId.CLAUDE_CODE)) as SubmitResult.Accepted).runId
        runCurrent()
        val snapshot = (runtime.snapshot(id) as SnapshotResult.Found).snapshot
        assertEquals(RunPhase.AWAITING_APPROVAL, snapshot.phase)
        val pending = snapshot.pendingApprovals.single()
        assertEquals("approval-1", pending.approvalId)
        assertEquals(snapshot.revision, pending.revision)
        assertEquals(ApprovalSubject.FileWrite("/workspace/approved.txt", "fixture"), pending.subject)
        assertEquals(snapshot, (runtime.observe(id).first() as RuntimeUpdate.Baseline).snapshot)
        assertEquals(1, journal.events.count { it.payload is RuntimeEvent.ApprovalRequired })
        runtime.cancel(CancelRequest(CommandId("cancel"), id)); runCurrent()
        assertTrue(journal.states.getValue(id).pendingApprovals.isEmpty())
    }
    private fun approvalLine(id: String = "approval-1", path: String = "/workspace/file") =
        """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"file_path":"$path"}}}"""

    @Test fun `approval is persisted before single delivery and stale or conflicting decisions do not reach process`() = runTest {
        for (choice in ApprovalChoice.values()) {
            val journal = MemoryJournal(); val deliveries = mutableListOf<Pair<String, ApprovalChoice>>()
            val gate = CompletableDeferred<Unit>()
            val port = object : ProcessPort {
                override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, devices: DeviceOperationPort, output: suspend (String, Boolean) -> Unit): ProcessResult {
                    output(approvalLine(), false); output(approvalLine(), false) // Pending duplicate is idempotent.
                    output(approvalLine("approval-2"), false)
                    gate.await()
                    output("""{"type":"result","subtype":"success","is_error":false,"permission_denials":${if (choice == ApprovalChoice.DENY) "[{}]" else "[]"}}""", false)
                    return ProcessResult(0, true)
                }
                override fun offerApproval(requestId: RequestId, approvalId: String, choice: ApprovalChoice): Boolean {
                    assertEquals(RequestId("request"), requestId)
                    assertTrue(journal.commands.values.any { it.result == CommandResult.Accepted })
                    assertTrue(journal.states.values.single().pendingApprovals.none { it.approvalId == approvalId })
                    assertTrue(journal.events.any { (it.payload as? RuntimeEvent.ApprovalResolved)?.approvalId == approvalId })
                    deliveries += approvalId to choice
                    return true
                }
            }
            val runtime = RunCoordinator(backgroundScope, environment, port, journal, MemoryOutput())
            runtime.recover(); val id = (runtime.submit(request().copy(agentId = AgentId.CLAUDE_CODE)) as SubmitResult.Accepted).runId
            runCurrent()
            val state = journal.states.getValue(id)
            assertEquals(2, state.pendingApprovals.size)
            val first = state.pendingApprovals.first()
            val decision = ApprovalDecision(CommandId("decision"), id, first.approvalId, choice, first.revision)
            assertEquals(ErrorCode.STALE_APPROVAL, (runtime.resolveApproval(decision.copy(commandId = CommandId("old"), expectedRevision = first.revision - 1)) as CommandResult.Rejected).error.code)
            assertEquals(CommandResult.Accepted, runtime.resolveApproval(decision))
            assertEquals(CommandResult.Accepted, runtime.resolveApproval(decision))
            assertEquals(ErrorCode.REQUEST_CONFLICT, (runtime.resolveApproval(decision.copy(choice = if (choice == ApprovalChoice.DENY) ApprovalChoice.ALLOW_ONCE else ApprovalChoice.DENY)) as CommandResult.Rejected).error.code)
            assertEquals(ErrorCode.STALE_APPROVAL, (runtime.resolveApproval(decision.copy(commandId = CommandId("new-id"))) as CommandResult.Rejected).error.code)
            assertEquals(listOf(first.approvalId to choice), deliveries)
            assertEquals(RunPhase.AWAITING_APPROVAL, journal.states.getValue(id).phase)
            val second = journal.states.getValue(id).pendingApprovals.single()
            runtime.resolveApproval(decision.copy(commandId = CommandId("second"), approvalId = second.approvalId, expectedRevision = second.revision))
            assertEquals(RunPhase.RUNNING, journal.states.getValue(id).phase)
            gate.complete(Unit); runCurrent()
            assertEquals(if (choice == ApprovalChoice.DENY) RunPhase.FAILED else RunPhase.SUCCEEDED, journal.states.getValue(id).phase)
            assertEquals(CommandResult.Accepted, runtime.resolveApproval(decision))
            assertEquals(2, deliveries.size)
        }
    }

    @Test fun `cancellation storage failure restart and lost transport never grant pending permission`() = runTest {
        for (scenario in listOf("cancel", "storage", "restart", "transport")) {
            val journal = MemoryJournal(); var deliveries = 0
            val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
            val port = object : ProcessPort {
                override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, devices: DeviceOperationPort, output: suspend (String, Boolean) -> Unit): ProcessResult {
                    output(approvalLine(), false); stop.filterNotNull().first(); return ProcessResult(143, true)
                }
                override fun offerApproval(requestId: RequestId, approvalId: String, choice: ApprovalChoice): Boolean { deliveries++; return false }
            }
            val runtime = RunCoordinator(scope, environment, port, journal, MemoryOutput())
            runtime.recover(); val id = (runtime.submit(request().copy(agentId = AgentId.CLAUDE_CODE)) as SubmitResult.Accepted).runId
            runCurrent()
            val pending = journal.states.getValue(id).pendingApprovals.single()
            val decision = ApprovalDecision(CommandId("decision"), id, pending.approvalId, ApprovalChoice.ALLOW_ONCE, pending.revision)
            when (scenario) {
                "cancel" -> {
                    runtime.cancel(CancelRequest(CommandId("cancel"), id))
                    assertTrue(journal.states.getValue(id).pendingApprovals.isEmpty())
                    assertEquals(ErrorCode.STALE_APPROVAL, (runtime.resolveApproval(decision) as CommandResult.Rejected).error.code)
                    runCurrent(); assertEquals(RunPhase.CANCELLED, journal.states.getValue(id).phase)
                }
                "storage" -> {
                    journal.broken = true
                    assertEquals(ErrorCode.STORAGE_FULL, (runtime.resolveApproval(decision) as CommandResult.Rejected).error.code)
                    assertEquals(ConnectionState.DISCONNECTED, runtime.connection.value)
                    assertFalse(journal.commands.containsKey(decision.commandId))
                }
                "restart" -> {
                    scope.cancel(); runCurrent()
                    // An unclean process death leaves the durable pending snapshot for recovery.
                    journal.states[id] = journal.states.getValue(id).copy(phase = RunPhase.AWAITING_APPROVAL, pendingApprovals = listOf(pending))
                    val recovered = RunCoordinator(backgroundScope, environment, port, journal, MemoryOutput())
                    recovered.recover()
                    assertEquals(RunPhase.INTERRUPTED, journal.states.getValue(id).phase)
                    assertTrue(journal.states.getValue(id).pendingApprovals.isEmpty())
                    assertEquals(ErrorCode.STALE_APPROVAL, (recovered.resolveApproval(decision) as CommandResult.Rejected).error.code)
                }
                "transport" -> {
                    assertEquals(CommandResult.Accepted, runtime.resolveApproval(decision))
                    runCurrent()
                    assertEquals(RunPhase.FAILED, journal.states.getValue(id).phase)
                    assertEquals(ErrorCode.PROTOCOL_ERROR, journal.states.getValue(id).terminalEvidence!!.error!!.code)
                    assertEquals(CommandResult.Accepted, runtime.resolveApproval(decision))
                }
            }
            assertEquals(if (scenario == "transport") 1 else 0, deliveries)
            scope.cancel()
        }
    }

    @Test fun `conflicting reused or malformed approval and completion while pending cannot succeed`() = runTest {
        for (scenario in listOf("conflict", "malformed", "completed", "reused", "flood")) {
            val journal = MemoryJournal(); val advance = CompletableDeferred<Unit>()
            val port = object : ProcessPort {
                override fun offerApproval(requestId: RequestId, approvalId: String, choice: ApprovalChoice) = true
                override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, devices: DeviceOperationPort, output: suspend (String, Boolean) -> Unit): ProcessResult {
                    output(approvalLine(), false)
                    advance.await()
                    if (scenario == "conflict") output(approvalLine(path = "/different"), false)
                    if (scenario == "flood") repeat(1000) { output(approvalLine("flood-$it"), false) }
                    if (scenario == "malformed") output("""{"type":"control_request","request_id":"bad","request":{"subtype":"future"}}""", false)
                    if (scenario == "reused") output(approvalLine(), false)
                    if (scenario != "completed") assertEquals(StopCause.PROTOCOL_FAILURE, stop.filterNotNull().first())
                    output("""{"type":"result","subtype":"success","is_error":false}""", false)
                    return ProcessResult(0, true)
                }
            }
            val runtime = RunCoordinator(backgroundScope, environment, port, journal, MemoryOutput())
            runtime.recover(); val id = (runtime.submit(request().copy(agentId = AgentId.CLAUDE_CODE)) as SubmitResult.Accepted).runId
            runCurrent()
            if (scenario == "reused") {
                val pending = journal.states.getValue(id).pendingApprovals.single()
                runtime.resolveApproval(ApprovalDecision(CommandId("allow"), id, pending.approvalId, ApprovalChoice.ALLOW_ONCE, pending.revision))
            }
            advance.complete(Unit); runCurrent()
            assertEquals(RunPhase.FAILED, journal.states.getValue(id).phase)
            if (scenario == "flood") assertTrue(journal.events.count { it.payload is RuntimeEvent.ApprovalRequired } < 1000)
            assertEquals(ErrorCode.PROTOCOL_ERROR, journal.states.getValue(id).terminalEvidence!!.error!!.code)
        }
    }
    @Test fun `production coordinator persists admission before starting and deduplicates after completion`() = runTest {
        val journal = MemoryJournal(); val output = MemoryOutput(); var starts = 0
        val runtime = RunCoordinator(backgroundScope, environment, process { req, _, emit ->
            assertNotNull(journal.find(req.requestId)); starts++
            emit("""{"type":"turn.completed"}""", false)
            ProcessResult(0, true)
        }, journal, output)
        runtime.recover()
        val results = List(10) { async { runtime.submit(request()) } }.awaitAll()
        runCurrent()
        val accepted = results.first() as SubmitResult.Accepted
        assertEquals(1, starts)
        assertTrue(results.all { it == accepted })
        assertEquals(RunPhase.SUCCEEDED, journal.states.getValue(accepted.runId).phase)
        assertEquals(accepted, runtime.submit(request()))
        assertEquals(ErrorCode.REQUEST_CONFLICT, (runtime.submit(request().copy(modelId = "changed")) as SubmitResult.Rejected).error.code)
    }
    @Test fun `zero exit without protocol success fails and stderr alone does not fail`() = runTest {
        for (terminal in listOf(false, true)) {
            val journal = MemoryJournal()
            val runtime = RunCoordinator(backgroundScope, environment, process { _, _, emit ->
                emit("ordinary diagnostic", true)
                if (terminal) emit("""{"type":"turn.completed"}""", false)
                ProcessResult(0, true)
            }, journal, MemoryOutput())
            runtime.recover(); val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
            runCurrent()
            assertEquals(if (terminal) RunPhase.SUCCEEDED else RunPhase.FAILED, journal.states.getValue(id).phase)
        }
    }
    @Test fun `cancel is not terminal until cleanup confirms exit and late success loses`() = runTest {
        val journal = MemoryJournal(); val allowExit = CompletableDeferred<Unit>()
        val runtime = RunCoordinator(backgroundScope, environment, process { _, signal, emit ->
            signal.filterNotNull().first(); allowExit.await()
            emit("""{"type":"turn.completed"}""", false)
            ProcessResult(0, true)
        }, journal, MemoryOutput())
        runtime.recover(); val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
        runCurrent()
        assertEquals(CommandResult.Accepted, runtime.cancel(CancelRequest(CommandId("cancel"), id)))
        assertEquals(RunPhase.CANCELLING, journal.states.getValue(id).phase)
        assertEquals(ErrorCode.BUSY, (runtime.submit(request("next")) as SubmitResult.Rejected).error.code)
        allowExit.complete(Unit); runCurrent()
        assertEquals(RunPhase.CANCELLED, journal.states.getValue(id).phase)
        assertEquals(1, journal.events.count { it.payload is RuntimeEvent.RunFinished })
        assertEquals(CommandResult.AlreadyTerminal, runtime.cancel(CancelRequest(CommandId("later"), id)))
    }
    @Test fun `host timeout and shutdown wait for exit and cannot become success`() = runTest {
        for ((cause, phase) in listOf(StopCause.TIMEOUT to RunPhase.TIMED_OUT, StopCause.HOST_STOP to RunPhase.INTERRUPTED)) {
            val journal = MemoryJournal(); val allowExit = CompletableDeferred<Unit>()
            val runtime = RunCoordinator(backgroundScope, environment, process { _, signal, emit ->
                assertEquals(cause, signal.filterNotNull().first()); allowExit.await()
                emit("""{"type":"turn.completed"}""", false)
                ProcessResult(0, true)
            }, journal, MemoryOutput())
            runtime.recover(); val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
            runCurrent(); runtime.requestStop(id, cause)
            assertEquals(RunPhase.CANCELLING, journal.states.getValue(id).phase)
            assertEquals(id, runtime.active.value)
            allowExit.complete(Unit); runCurrent()
            assertEquals(phase, journal.states.getValue(id).phase)
            assertTrue(journal.states.getValue(id).terminalEvidence!!.terminationConfirmed)
            assertNull(runtime.active.value)
        }
    }
    @Test fun `cancel command replay remains idempotent after terminal and rejects ID reuse`() = runTest {
        val journal = MemoryJournal()
        val runtime = RunCoordinator(backgroundScope, environment, process { _, signal, _ ->
            signal.filterNotNull().first(); ProcessResult(143, true)
        }, journal, MemoryOutput())
        runtime.recover(); val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
        runCurrent()
        val command = CancelRequest(CommandId("same-command"), id)
        assertEquals(CommandResult.Accepted, runtime.cancel(command))
        runCurrent()
        assertEquals(CommandResult.Accepted, runtime.cancel(command))
        assertEquals(ErrorCode.REQUEST_CONFLICT, (runtime.cancel(command.copy(runId = RunId("another"))) as CommandResult.Rejected).error.code)
    }
    @Test fun `unconfirmed process termination keeps slot occupied`() = runTest {
        val journal = MemoryJournal()
        val runtime = RunCoordinator(backgroundScope, environment, process { _, _, _ -> ProcessResult(null, false) }, journal, MemoryOutput())
        runtime.recover(); val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
        runCurrent()
        assertEquals(RunPhase.OUTCOME_UNKNOWN, journal.states.getValue(id).phase)
        assertEquals(ErrorCode.BUSY, (runtime.submit(request("next")) as SubmitResult.Rejected).error.code)
    }
    @Test fun `recovery confirms termination of an unknown run without claiming task success`() = runTest {
        val journal = MemoryJournal()
        val first = RunCoordinator(backgroundScope, environment, process { _, _, _ -> ProcessResult(null, false) }, journal, MemoryOutput())
        first.recover()
        val admitted = first.submit(request()) as SubmitResult.Accepted
        runCurrent()
        assertFalse(journal.states.getValue(admitted.runId).terminalEvidence!!.terminationConfirmed)
        val restarted = RunCoordinator(backgroundScope, environment, process { _, _, _ -> error("Must not replay work") }, journal, MemoryOutput())
        restarted.recover()
        val recovered = (restarted.snapshot(admitted.runId) as SnapshotResult.Found).snapshot
        assertEquals(RunPhase.OUTCOME_UNKNOWN, recovered.phase)
        assertTrue(recovered.terminalEvidence!!.terminationConfirmed)
        assertNull(recovered.terminalEvidence!!.protocolSucceeded)
        assertNull(recovered.terminalEvidence!!.exitCode)
        assertEquals(CommandResult.AlreadyTerminal, restarted.cancel(CancelRequest(CommandId("stop"), admitted.runId)))
        assertEquals(1, journal.events.count { it.runId == admitted.runId && it.payload is RuntimeEvent.RunFinished })
        restarted.recover()
        assertEquals(1, journal.events.count { it.runId == admitted.runId && it.payload is RuntimeEvent.ProcessTerminationConfirmed })
        assertTrue(restarted.submit(request("next")) is SubmitResult.Accepted)
    }
    @Test fun `restart marks admitted run interrupted and never starts it again`() = runTest {
        val journal = MemoryJournal(); val output = MemoryOutput()
        val first = RunCoordinator(backgroundScope, environment, process { _, _, _ -> awaitCancellation() }, journal, output)
        first.recover(); val admitted = first.submit(request()) as SubmitResult.Accepted
        var starts = 0
        val restarted = RunCoordinator(backgroundScope, environment, process { _, _, _ -> starts++; ProcessResult(0, true) }, journal, output)
        restarted.recover()
        assertEquals(admitted, restarted.submit(request()))
        assertEquals(RunPhase.INTERRUPTED, journal.states.getValue(admitted.runId).phase)
        assertEquals(0, starts)
    }
    @Test fun `slow observer replaces projection when terminal history is compacted`() = runTest {
        for (keepTerminalEvent in listOf(false, true)) {
            val journal = MemoryJournal(); val finish = CompletableDeferred<Unit>(); val resumeObserver = CompletableDeferred<Unit>()
            val runtime = RunCoordinator(backgroundScope, environment, process { _, _, emit ->
                finish.await()
                emit("""{"type":"item.completed","item":{"id":"answer","type":"agent_message","text":"retained answer"}}""", false)
                emit("""{"type":"turn.completed"}""", false)
                ProcessResult(0, true)
            }, journal, MemoryOutput())
            runtime.recover()
            val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
            runCurrent()
            val updates = mutableListOf<RuntimeUpdate>()
            val observer = backgroundScope.launch { runtime.observe(id).collect {
                updates += it
                if (updates.size == 1) resumeObserver.await()
            } }
            runCurrent()
            finish.complete(Unit); runCurrent()
            val terminal = journal.states.getValue(id)
            assertEquals(RunPhase.SUCCEEDED, terminal.phase)
            journal.events.removeAll { !keepTerminalEvent || it.sequence < terminal.lastSequence }
            resumeObserver.complete(Unit); runCurrent()
            assertEquals(listOf(RuntimeUpdate.ResyncRequired(ResyncReason.CURSOR_EXPIRED)), updates.filterIsInstance<RuntimeUpdate.ResyncRequired>())
            val restored = updates.filterIsInstance<RuntimeUpdate.Baseline>().last()
            assertEquals(terminal, restored.snapshot)
            assertEquals(terminal.lastSequence, restored.cursor.sequence)
            assertTrue(updates.none { it is RuntimeUpdate.Event })
            observer.cancel()
        }
    }
    @Test fun `observe baseline has complete output and unsubscribing does not stop process`() = runTest {
        val journal = MemoryJournal(); val output = MemoryOutput()
        val runtime = RunCoordinator(backgroundScope, environment, process { _, signal, emit ->
            emit("""{"type":"item.completed","item":{"id":"m","type":"agent_message","text":"hello"}}""", false)
            signal.filterNotNull().first(); ProcessResult(143, true)
        }, journal, output)
        runtime.recover(); val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
        runCurrent()
        val base = runtime.observe(id).first() as RuntimeUpdate.Baseline
        assertEquals(listOf("hello"), base.snapshot.outputSegments.map { output.content[it.ref] })
        assertEquals(RunPhase.RUNNING, journal.states.getValue(id).phase)
        runtime.cancel(CancelRequest(CommandId("stop"), id)); runCurrent()
        val restored = runtime.observe(id, base.cursor).first() as RuntimeUpdate.Baseline
        assertEquals(RunPhase.CANCELLED, restored.snapshot.phase)
        assertEquals(1, restored.snapshot.outputSegments.size)
    }
    @Test fun `generated artifacts are durable events before terminal and replay in baseline`() = runTest {
        val journal = MemoryJournal(); val output = MemoryOutput()
        val runtime = RunCoordinator(backgroundScope, environment, process { _, _, emit ->
            emit(kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("item.completed"))
                put("item", kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive("proposal")); put("type", kotlinx.serialization.json.JsonPrimitive("agent_message"))
                    put("text", kotlinx.serialization.json.JsonPrimitive("""{"kind":"proposal","message":"Ready","name":"draft","description":"test","body":"Instructions"}"""))
                })
            }.toString(), false)
            emit("""{"type":"turn.completed"}""", false)
            ProcessResult(0, true)
        }, journal, output)
        runtime.recover(); val id = (runtime.submit(request().copy(requestedOutput = RequestedOutput.SKILL_PROPOSAL)) as SubmitResult.Accepted).runId
        runCurrent()
        val snapshot = (runtime.observe(id).first() as RuntimeUpdate.Baseline).snapshot
        assertEquals(RunPhase.SUCCEEDED, snapshot.phase)
        assertEquals(SkillDocument.manual("draft", "test", "Instructions").markdown, output.content[snapshot.artifacts.single()])
        val artifactIndex = journal.events.indexOfFirst { it.payload is RuntimeEvent.ArtifactAvailable }
        assertTrue(artifactIndex >= 0)
        assertTrue(artifactIndex < journal.events.indexOfFirst { it.payload is RuntimeEvent.RunFinished })
    }
    @Test fun `ordinary or failed turns never claim a saved skill proposal`() = runTest {
        for ((purpose, exit) in listOf(RequestedOutput.TEXT to 0, RequestedOutput.SKILL_PROPOSAL to 1)) {
            val journal = MemoryJournal()
            val runtime = RunCoordinator(backgroundScope, environment, process { _, _, emit ->
            emit(kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("item.completed"))
                put("item", kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive("proposal")); put("type", kotlinx.serialization.json.JsonPrimitive("agent_message"))
                    put("text", kotlinx.serialization.json.JsonPrimitive("""{"kind":"proposal","message":"Ready","name":"draft","description":"test","body":"Instructions"}"""))
                })
            }.toString(), false)
                emit("""{"type":"turn.completed"}""", false)
                ProcessResult(exit, true)
            }, journal, MemoryOutput())
            runtime.recover(); val id = (runtime.submit(request().copy(requestedOutput = purpose)) as SubmitResult.Accepted).runId
            runCurrent()
            assertTrue(journal.states.getValue(id).artifacts.isEmpty())
        }
    }

    @Test fun `skill request with only freeform response fails despite zero exit and CLI completion`() = runTest {
        val journal = MemoryJournal()
        val runtime = RunCoordinator(backgroundScope, environment, process { _, _, emit ->
            emit("""{"type":"item.completed","item":{"id":"m","type":"agent_message","text":"ordinary skill description"}}""", false)
            emit("""{"type":"turn.completed"}""", false)
            ProcessResult(0, true)
        }, journal, MemoryOutput())
        runtime.recover()
        val id = (runtime.submit(request().copy(requestedOutput = RequestedOutput.SKILL_PROPOSAL)) as SubmitResult.Accepted).runId
        runCurrent()
        val snapshot = journal.states.getValue(id)
        assertEquals(RunPhase.FAILED, snapshot.phase)
        assertTrue(snapshot.artifacts.isEmpty())
        assertEquals(ErrorCode.PROTOCOL_ERROR, snapshot.terminalEvidence?.error?.code)
    }
    @Test fun `disk admission failure never starts process`() = runTest {
        val journal = MemoryJournal(); var started = false
        val runtime = RunCoordinator(backgroundScope, environment, process { _, _, _ -> started = true; ProcessResult(0, true) }, journal, MemoryOutput())
        runtime.recover(); journal.broken = true
        assertEquals(ErrorCode.STORAGE_FULL, (runtime.submit(request()) as SubmitResult.Rejected).error.code)
        runCurrent(); assertFalse(started)
        assertTrue(journal.requests.isEmpty())
    }
    @Test fun `cancelling a pending query or observer does not stop an admitted run`() = runTest {
        for (operation in listOf("request lookup", "snapshot", "observer")) {
            val stored = MemoryJournal()
            var blockReads = false
            val entered = CompletableDeferred<Unit>()
            val journal = object : JournalPort by stored {
                override suspend fun find(requestId: RequestId): RequestRecord? {
                    if (blockReads) { entered.complete(Unit); awaitCancellation() }
                    return stored.find(requestId)
                }
                override suspend fun snapshot(runId: RunId): RunSnapshot? {
                    if (blockReads) { entered.complete(Unit); awaitCancellation() }
                    return stored.snapshot(runId)
                }
            }
            val runtime = RunCoordinator(backgroundScope, environment, process { _, signal, _ ->
                signal.filterNotNull().first()
                ProcessResult(143, true)
            }, journal, MemoryOutput())
            runtime.recover()
            val id = (runtime.submit(request()) as SubmitResult.Accepted).runId
            runCurrent()
            blockReads = true
            val reader = launch {
                when (operation) {
                    "request lookup" -> runtime.findByRequest(RequestId("request"))
                    "snapshot" -> runtime.snapshot(id)
                    else -> runtime.observe(id).collect()
                }
            }
            entered.await()
            reader.cancelAndJoin()
            blockReads = false
            runCurrent()
            assertEquals(operation, ConnectionState.CONNECTED, runtime.connection.value)
            assertEquals(operation, RunPhase.RUNNING, stored.states.getValue(id).phase)
            assertEquals(RequestLookup.Found(id), runtime.findByRequest(RequestId("request")))
            assertEquals(CommandResult.Accepted, runtime.cancel(CancelRequest(CommandId("stop"), id)))
            runCurrent()
            assertEquals(RunPhase.CANCELLED, stored.states.getValue(id).phase)
        }
    }

}
