package com.mobby.runtime.android

import com.mobby.runtime.api.*
import com.mobby.runtime.engine.CommandRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RuntimeJournalApprovalTest {
    @Test fun `pending approval reopens and decision event snapshot and command commit atomically`() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.deleteDatabase("runtime-journal.db")
        val id = RunId("fixture-run")
        val config = RunConfigSnapshot(AgentId.CLAUDE_CODE, WorkspaceRef("fixture"), "model", null, GatewayProfileRef("CLAUDE", 0), emptySet())
        val initial = RunSnapshot(id, RunPhase.ACCEPTED, 1, 1, config)
        val pending = PendingApproval("permission", 2, "Write", "/fixture/file")
        val awaiting = initial.copy(phase = RunPhase.AWAITING_APPROVAL, revision = 2, lastSequence = 2, pendingApprovals = listOf(pending))
        fun event(sequence: Long, value: RuntimeEvent) = EventEnvelope("e-$sequence", id, sequence, 0, value)
        RuntimeJournal(context).use { journal ->
            journal.accept(RequestId("request"), "fixture-digest", initial, event(1, RuntimeEvent.RunAccepted(config)))
            journal.append(awaiting, event(2, RuntimeEvent.ApprovalRequired(pending)))
        }
        val command = CommandRecord(CommandId("decision"), "fixture-fingerprint", CommandResult.Accepted)
        val resolved = awaiting.copy(phase = RunPhase.RUNNING, revision = 3, lastSequence = 3, pendingApprovals = emptyList())
        RuntimeJournal(context).use { journal ->
            assertEquals(awaiting, journal.snapshot(id))
            assertEquals(listOf(awaiting), journal.unfinished())
            // Force a command uniqueness conflict after snapshot and event writes inside the transaction.
            journal.recordCommand(command)
            try {
                journal.append(resolved, event(3, RuntimeEvent.ApprovalResolved(pending.approvalId, ApprovalChoice.ALLOW_ONCE)), command)
                fail("conflicting command must roll back the whole decision")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(awaiting, journal.snapshot(id))
            assertEquals(listOf(1L, 2L), journal.eventsAfter(id, 0, 10).map { it.sequence })
            journal.append(resolved, event(3, RuntimeEvent.ApprovalResolved(pending.approvalId, ApprovalChoice.DENY)), command.copy(id = CommandId("fresh")))
        }
        RuntimeJournal(context).use { journal ->
            assertEquals(resolved, journal.snapshot(id))
            assertEquals(command.copy(id = CommandId("fresh")), journal.command(CommandId("fresh")))
            assertEquals(RuntimeEvent.ApprovalResolved(pending.approvalId, ApprovalChoice.DENY), journal.eventsAfter(id, 2, 10).single().payload)
        }
        context.deleteDatabase("runtime-journal.db")
        Unit
    }
}
