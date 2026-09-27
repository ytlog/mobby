package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.engine.CommandRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RuntimeJournalRetentionTest {
    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val config = RunConfigSnapshot(AgentId.CLAUDE_CODE, WorkspaceRef("fixture"), "model", null, GatewayProfileRef("CLAUDE", 0), emptySet())
    private val generous = EventHistoryPolicy(Long.MAX_VALUE, Long.MAX_VALUE)
    @Before fun reset() { context.deleteDatabase("runtime-journal.db") }
    @After fun cleanup() { context.deleteDatabase("runtime-journal.db") }
    private fun event(snapshot: RunSnapshot, time: Long, payload: RuntimeEvent) = EventEnvelope("${snapshot.runId.value}-${snapshot.lastSequence}", snapshot.runId, snapshot.lastSequence, time, payload)
    private suspend fun accept(journal: RuntimeJournal, id: String, time: Long): RunSnapshot {
        val snapshot = RunSnapshot(RunId(id), RunPhase.ACCEPTED, 1, 1, config)
        journal.accept(RequestId(id), "digest-$id", snapshot, event(snapshot, time, RuntimeEvent.RunAccepted(config)))
        return snapshot
    }
    private suspend fun finish(journal: RuntimeJournal, id: String, time: Long): RunSnapshot {
        val initial = accept(journal, id, time - 1)
        val ref = OutputStore(context).write(initial.runId, "0", "retained-$id")
        val evidence = TerminalEvidence(protocolSucceeded = true, exitCode = 0)
        val snapshot = initial.copy(phase = RunPhase.SUCCEEDED, revision = 2, lastSequence = 2, terminalEvidence = evidence,
            outputSegments = listOf(OutputSegment("message", 0, ref)))
        journal.append(snapshot, event(snapshot, time, RuntimeEvent.RunFinished(snapshot.phase, evidence)))
        return snapshot
    }
    @Test fun `age expiry keeps snapshot output request receipt and active approval across reopen`() = runBlocking {
        lateinit var old: RunSnapshot
        lateinit var awaiting: RunSnapshot
        val receipt = CommandRecord(CommandId("receipt"), "fingerprint", CommandResult.Accepted)
        RuntimeJournal(context, generous) { 600 }.use { journal ->
            old = finish(journal, "old", 100)
            finish(journal, "new", 500)
            journal.recordCommand(receipt)
            val initial = accept(journal, "active", 10)
            val permission = PendingApproval("permission", 2, ApprovalSubject.FileWrite("/fixture", ""))
            awaiting = initial.copy(phase = RunPhase.AWAITING_APPROVAL, revision = 2, lastSequence = 2, pendingApprovals = listOf(permission))
            journal.append(awaiting, event(awaiting, 20, RuntimeEvent.ApprovalRequired(permission)))
        }
        RuntimeJournal(context, EventHistoryPolicy(200, Long.MAX_VALUE)) { 600 }.use { journal ->
            journal.compact()
            assertTrue(journal.eventsAfter(old.runId, 0, 128).isEmpty())
            assertEquals(2, journal.eventsAfter(RunId("new"), 0, 128).size)
            assertEquals(2, journal.eventsAfter(awaiting.runId, 0, 128).size)
        }
        RuntimeJournal(context, generous) { 600 }.use { journal ->
            assertEquals(old, journal.snapshot(old.runId))
            assertEquals("digest-old", journal.find(RequestId("old"))!!.digest)
            assertEquals(old.runId, journal.find(RequestId("old"))!!.runId)
            assertEquals(receipt, journal.command(receipt.id))
            assertEquals(listOf(awaiting), journal.unfinished())
            val content = OutputStore(context).read(ArtifactReadRequest(old.outputSegments.single().ref, 0, 1024)) as ArtifactReadResult.Chunk
            assertEquals("retained-old", content.bytes.toByteArray().toString(Charsets.UTF_8))
        }
    }
    @Test fun `byte budget prunes oldest terminal events and protects unconfirmed execution`() = runBlocking {
        var budget = 0L
        lateinit var uncertain: RunSnapshot
        RuntimeJournal(context, generous) { 600 }.use { journal ->
            finish(journal, "old", 100); finish(journal, "new", 500)
            budget = journal.eventsAfter(RunId("new"), 0, 128).sumOf { Json.encodeToString(it).toByteArray().size.toLong() }
            val initial = accept(journal, "uncertain", 10)
            val evidence = TerminalEvidence(null, null)
            uncertain = initial.copy(phase = RunPhase.OUTCOME_UNKNOWN, revision = 2, lastSequence = 2, terminalEvidence = evidence)
            journal.append(uncertain, event(uncertain, 20, RuntimeEvent.RunFinished(uncertain.phase, evidence)))
        }
        RuntimeJournal(context, EventHistoryPolicy(Long.MAX_VALUE, budget)) { 600 }.use { journal ->
            journal.compact()
            assertTrue(journal.eventsAfter(RunId("old"), 0, 128).isEmpty())
            assertEquals(2, journal.eventsAfter(RunId("new"), 0, 128).size)
            assertEquals(2, journal.eventsAfter(uncertain.runId, 0, 128).size)
            assertEquals(listOf(uncertain), journal.unfinished())
        }
    }
    @Test fun `confirmed process exit releases slot even when a device effect is unknown`() = runBlocking {
        RuntimeJournal(context, generous).use { journal ->
            val initial = accept(journal, "uncertain-effect", 10)
            val evidence = TerminalEvidence(true, 0, RuntimeError(ErrorCode.INTERRUPTED))
            val terminal = initial.copy(phase = RunPhase.OUTCOME_UNKNOWN, revision = 2, lastSequence = 2, terminalEvidence = evidence)
            journal.append(terminal, event(terminal, 20, RuntimeEvent.RunFinished(terminal.phase, evidence)))
            assertEquals(terminal, journal.snapshot(initial.runId))
            assertTrue(journal.unfinished().isEmpty())
            val next = accept(journal, "next", 30)
            assertEquals(listOf(next), journal.unfinished())
        }
    }
    @Test fun `saved policy is used by the next cleanup without restarting the journal`() = runBlocking {
        context.getSharedPreferences("runtime-storage-policy", 0).edit().clear().commit()
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EventHistorySettingsStore(context) { key }
        RuntimeJournal(context, policyProvider = store::policy, clock = { 2 * 86_400_000L }).use { journal ->
            val old = finish(journal, "old", 100)
            assertEquals(2, journal.eventsAfter(old.runId, 0, 128).size)
            store.save(EventHistorySettings(1, 32))
            assertEquals(2, journal.eventsAfter(old.runId, 0, 128).size) // Saving alone does not delete history.
            journal.compact()
            assertTrue(journal.eventsAfter(old.runId, 0, 128).isEmpty())
            assertEquals(old, journal.snapshot(old.runId))
        }
        context.getSharedPreferences("runtime-storage-policy", 0).edit().clear().commit()
        Unit
    }
    @Test fun `terminal append compacts atomically and cleanup failure rolls back snapshot event and receipt`() = runBlocking {
        RuntimeJournal(context, EventHistoryPolicy(0, 0)) { 600 }.use { journal ->
            val initial = accept(journal, "run", 100)
            val evidence = TerminalEvidence(protocolSucceeded = true, exitCode = 0)
            val terminal = initial.copy(phase = RunPhase.SUCCEEDED, revision = 2, lastSequence = 2, terminalEvidence = evidence)
            val receipt = CommandRecord(CommandId("terminal"), "fingerprint", CommandResult.Accepted)
            journal.writableDatabase.execSQL("CREATE TRIGGER fail_cleanup BEFORE DELETE ON events BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            try {
                journal.append(terminal, event(terminal, 200, RuntimeEvent.RunFinished(terminal.phase, evidence)), receipt)
                fail("cleanup failure must roll back the transaction")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(initial, journal.snapshot(initial.runId))
            assertEquals(1, journal.eventsAfter(initial.runId, 0, 128).size)
            assertNull(journal.command(receipt.id))
            journal.writableDatabase.execSQL("DROP TRIGGER fail_cleanup")
            journal.append(terminal, event(terminal, 200, RuntimeEvent.RunFinished(terminal.phase, evidence)), receipt)
            assertTrue(journal.eventsAfter(initial.runId, 0, 128).isEmpty())
            assertEquals(terminal, journal.snapshot(initial.runId))
            assertEquals(receipt, journal.command(receipt.id))
        }
    }
}
