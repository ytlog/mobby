package com.github.ytlog.mobby.android.runtime.api

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RuntimeContractTest {
    private fun request(id: String = "request") = RunRequest(RequestId(id), AgentId.CODEX, WorkspaceRef("workspace"),
        listOf(InputPart.Text("hello")), "test-model", GatewayProfileRef("gateway", 1))
    private suspend fun FakeRuntimeClient.admit() = (submit(request()) as SubmitResult.Accepted).runId
    private suspend fun FakeRuntimeClient.state(id: RunId) = (snapshot(id) as SnapshotResult.Found).snapshot

    @Test fun `duplicate concurrent submit starts once and lost response is discoverable`() = runTest {
        val runtime = FakeRuntimeClient()
        val replies = List(20) { async { runtime.submit(request()) } }.awaitAll()
        assertEquals(1, replies.distinct().size)
        assertEquals(1, runtime.starts)
        val id = (replies.first() as SubmitResult.Accepted).runId
        assertEquals(RequestLookup.Found(id), runtime.findByRequest(request().requestId))
        assertEquals(ErrorCode.REQUEST_CONFLICT, (runtime.submit(request().copy(modelId = "changed")) as SubmitResult.Rejected).error.code)
        val busy = runtime.submit(request("another")) as SubmitResult.Rejected
        assertEquals(ErrorCode.BUSY, busy.error.code)
        assertEquals(id, busy.activeRunId)
    }
    @Test fun `cancel acceptance waits for exit and beats late success`() = runTest {
        val runtime = FakeRuntimeClient(); val id = runtime.admit()
        val cancel = CancelRequest(CommandId("stop"), id)
        assertEquals(CommandResult.Accepted, runtime.cancel(cancel))
        assertEquals(RunPhase.CANCELLING, runtime.state(id).phase)
        assertEquals(CommandResult.Accepted, runtime.cancel(cancel))
        runtime.finish(id, TerminalEvidence(true, 0))
        assertEquals(RunPhase.CANCELLED, runtime.state(id).phase)
        val revision = runtime.state(id).revision
        runtime.finish(id, TerminalEvidence(true, 0))
        assertEquals(revision, runtime.state(id).revision)
        assertEquals(CommandResult.AlreadyTerminal, runtime.cancel(cancel.copy(commandId = CommandId("later"))))
    }
    @Test fun `missing or contradictory evidence cannot succeed`() = runTest {
        for ((evidence, expected) in listOf(
            TerminalEvidence(true, null) to RunPhase.OUTCOME_UNKNOWN,
            TerminalEvidence(null, 0) to RunPhase.FAILED,
            TerminalEvidence(true, 1) to RunPhase.FAILED,
            TerminalEvidence(true, 0, RuntimeError(ErrorCode.TIMEOUT)) to RunPhase.TIMED_OUT,
            TerminalEvidence(true, 0) to RunPhase.SUCCEEDED
        )) {
            val runtime = FakeRuntimeClient(); val id = runtime.admit()
            runtime.finish(id, evidence)
            assertEquals(expected, runtime.state(id).phase)
        }
    }
    @Test fun `replay starts with consistent baseline and never duplicates baseline output`() = runTest {
        val runtime = FakeRuntimeClient(); val id = runtime.admit()
        val cursor = EventCursor(id, 1)
        runtime.output(id, OutputSegment("message", 0, ResourceRef("chunk-0")))
        runtime.output(id, OutputSegment("message", 1, ResourceRef("chunk-1")))
        val replay = runtime.observe(id, cursor).take(3).toList()
        assertEquals(1L, (replay[0] as RuntimeUpdate.Baseline).snapshot.lastSequence)
        assertEquals(listOf(2L, 3L), replay.drop(1).map { (it as RuntimeUpdate.Event).envelope.sequence })
        val baseline = runtime.observe(id).first() as RuntimeUpdate.Baseline
        assertEquals(2, baseline.snapshot.outputSegments.size)
        assertEquals(3L, baseline.cursor.sequence)
        // Cancelling subscriptions does not cancel the execution.
        assertEquals(RunPhase.ACCEPTED, runtime.state(id).phase)
        assertTrue(runtime.observe(id, EventCursor(id, 99)).first() is RuntimeUpdate.ResyncRequired)
    }
    @Test fun `event emitted while baseline is consumed is not lost`() = runTest {
        val runtime = FakeRuntimeClient(); val id = runtime.admit()
        val updates = runtime.observe(id).onEach {
            if (it is RuntimeUpdate.Baseline) runtime.output(id, OutputSegment("m", 0, ResourceRef("chunk")))
        }.take(2).toList()
        assertEquals(2L, (updates.last() as RuntimeUpdate.Event).envelope.sequence)
    }
    @Test fun `stale approval cannot approve newer request and pending approval survives reconnect`() = runTest {
        val runtime = FakeRuntimeClient(); val id = runtime.admit()
        runtime.requireApproval(id, PendingApproval("approval", 2, "Read", "workspace"))
        val decision = ApprovalDecision(CommandId("decision"), id, "approval", ApprovalChoice.ALLOW_ONCE, 1)
        assertEquals(ErrorCode.STALE_APPROVAL, (runtime.resolveApproval(decision) as CommandResult.Rejected).error.code)
        assertEquals(1, (runtime.observe(id).first() as RuntimeUpdate.Baseline).snapshot.pendingApprovals.size)
        val current = decision.copy(commandId = CommandId("current"), expectedRevision = 2)
        assertEquals(CommandResult.Accepted, runtime.resolveApproval(current))
        runtime.requireApproval(id, PendingApproval("new", 4, "Write", "workspace"))
        assertEquals(CommandResult.Accepted, runtime.resolveApproval(current))
        assertEquals("new", runtime.state(id).pendingApprovals.single().approvalId)
    }
    @Test fun `unsupported capabilities and incompatible protocol reject without starting`() = runTest {
        val runtime = FakeRuntimeClient()
        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, (runtime.submit(request().copy(apiMajor = 2)) as SubmitResult.Rejected).error.code)
        assertEquals(ErrorCode.UNSUPPORTED_CAPABILITY, (runtime.submit(request().copy(capabilityRefs = setOf(CapabilityRef("creator")))) as SubmitResult.Rejected).error.code)
        assertEquals(0, runtime.starts)
    }
    @Test fun `artifact reads are bounded`() {
        assertThrows(IllegalArgumentException::class.java) { ArtifactReadRequest(ResourceRef("file"), -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { ArtifactReadRequest(ResourceRef("file"), 0, 65537) }
    }
}
