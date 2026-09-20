package com.mobby.runtime.engine

import com.mobby.runtime.api.*
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
        override suspend fun releaseRecoveredSlot(runId: RunId) {}
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
        override suspend fun unfinished() = states.values.filter { !it.phase.terminal }
    }
    private class MemoryOutput : OutputStorePort {
        val content = mutableMapOf<ResourceRef, String>()
        override suspend fun write(runId: RunId, name: String, text: String) = ResourceRef("${runId.value}/$name").also { content[it] = text }
        override suspend fun read(request: ArtifactReadRequest) = ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING))
    }
    private fun process(block: suspend (RunRequest, StateFlow<StopCause?>, suspend (String, Boolean) -> Unit) -> ProcessResult) = object : ProcessPort {
        override suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, output: suspend (String, Boolean) -> Unit) = block(request, stop, output)
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
            emit("""{"type":"item.completed","item":{"id":"proposal","type":"agent_message","text":"````SKILL.md\n---\nname: draft\ndescription: test\n---\nInstructions\n````"}}""", false)
            emit("""{"type":"turn.completed"}""", false)
            ProcessResult(0, true)
        }, journal, output)
        runtime.recover(); val id = (runtime.submit(request().copy(requestedOutput = RequestedOutput.SKILL_PROPOSAL)) as SubmitResult.Accepted).runId
        runCurrent()
        val snapshot = (runtime.observe(id).first() as RuntimeUpdate.Baseline).snapshot
        assertEquals(RunPhase.SUCCEEDED, snapshot.phase)
        assertEquals("---\nname: draft\ndescription: test\n---\nInstructions\n", output.content[snapshot.artifacts.single()])
        val artifactIndex = journal.events.indexOfFirst { it.payload is RuntimeEvent.ArtifactAvailable }
        assertTrue(artifactIndex >= 0)
        assertTrue(artifactIndex < journal.events.indexOfFirst { it.payload is RuntimeEvent.RunFinished })
    }
    @Test fun `ordinary or failed turns never claim a saved skill proposal`() = runTest {
        for ((purpose, exit) in listOf(RequestedOutput.TEXT to 0, RequestedOutput.SKILL_PROPOSAL to 1)) {
            val journal = MemoryJournal()
            val runtime = RunCoordinator(backgroundScope, environment, process { _, _, emit ->
                emit("""{"type":"item.completed","item":{"id":"proposal","type":"agent_message","text":"````SKILL.md\nbody\n````"}}""", false)
                emit("""{"type":"turn.completed"}""", false)
                ProcessResult(exit, true)
            }, journal, MemoryOutput())
            runtime.recover(); val id = (runtime.submit(request().copy(requestedOutput = purpose)) as SubmitResult.Accepted).runId
            runCurrent()
            assertTrue(journal.states.getValue(id).artifacts.isEmpty())
        }
    }
    @Test fun `disk admission failure never starts process`() = runTest {
        val journal = MemoryJournal(); var started = false
        val runtime = RunCoordinator(backgroundScope, environment, process { _, _, _ -> started = true; ProcessResult(0, true) }, journal, MemoryOutput())
        runtime.recover(); journal.broken = true
        assertEquals(ErrorCode.STORAGE_FULL, (runtime.submit(request()) as SubmitResult.Rejected).error.code)
        runCurrent(); assertFalse(started)
        assertTrue(journal.requests.isEmpty())
    }
}
