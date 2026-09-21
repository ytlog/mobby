package com.mobby.runtime.android

import com.mobby.runtime.api.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OutputRetentionTest {
    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val config = RunConfigSnapshot(AgentId.CLAUDE_CODE, WorkspaceRef("fixture"), "model", null, GatewayProfileRef("CLAUDE", 0), emptySet())
    private val unlimited = EventHistoryPolicy(Long.MAX_VALUE, Long.MAX_VALUE)
    @Before fun reset() { context.deleteDatabase("runtime-journal.db"); File(context.filesDir, "runtime-output").deleteRecursively() }
    @After fun cleanup() { context.deleteDatabase("runtime-journal.db"); File(context.filesDir, "runtime-output").deleteRecursively() }
    private suspend fun run(journal: RuntimeJournal, store: OutputStore, id: String, finished: Long?): RunSnapshot {
        val initial = RunSnapshot(RunId(id), RunPhase.ACCEPTED, 1, 1, config)
        journal.accept(RequestId(id), "digest-$id", initial, EventEnvelope("$id-1", initial.runId, 1, 1, RuntimeEvent.RunAccepted(config)))
        val ref = store.write(initial.runId, "0", id)
        if (finished == null) return initial
        val evidence = TerminalEvidence(true, 0)
        val terminal = initial.copy(phase = RunPhase.SUCCEEDED, revision = 2, lastSequence = 2, terminalEvidence = evidence,
            outputSegments = listOf(OutputSegment("message", 0, ref)))
        journal.append(terminal, EventEnvelope("$id-2", initial.runId, 2, finished, RuntimeEvent.RunFinished(terminal.phase, evidence)))
        return terminal
    }
    private suspend fun read(store: OutputStore, id: String) = store.read(ArtifactReadRequest(ResourceRef("$id/0"), 0, 1024))
    @Test fun `temporary output symlink cannot overwrite a file outside the store`() = runBlocking {
        val outside = File(context.filesDir, "outside-write-fixture").apply { writeText("keep") }
        val directory = File(context.filesDir, "runtime-output/fixture").apply { mkdirs() }
        Files.createSymbolicLink(File(directory, "0.tmp").toPath(), outside.toPath())
        try {
            val result = runCatching { OutputStore(context).write(RunId("fixture"), "0", "replacement") }
            assertEquals("keep", outside.readText())
            assertTrue("unsafe temporary path must reject the write", result.isFailure)
            assertFalse(File(directory, "0").exists())
        } finally { outside.delete() }
    }
    @Test fun `saved output limits apply at next cleanup without deleting files during save`() = runBlocking {
        val prefs = context.getSharedPreferences("runtime-storage-policy", 0)
        prefs.edit().clear().commit()
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val settings = EventHistorySettingsStore(context) { key }
        try {
            RuntimeJournal(context, unlimited).use { journal ->
                val store = OutputStore(context, journal::outputExpired, settings::outputPolicy)
                run(journal, store, "old", 100)
                run(journal, store, "active", null)
                val time = 3 * 86_400_000L
                store.compact(journal, now = time)
                assertTrue(read(store, "old") is ArtifactReadResult.Chunk)
                settings.save(EventHistorySettings(outputRetentionDays = 1, outputBudgetMiB = 1))
                assertTrue(read(store, "old") is ArtifactReadResult.Chunk)
                store.compact(journal, now = time)
                assertEquals(ArtifactReadResult.Expired, read(store, "old"))
                assertTrue(read(store, "active") is ArtifactReadResult.Chunk)
            }
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun `terminal output budget removes oldest files but preserves snapshots requests and active output`() = runBlocking {
        RuntimeJournal(context, unlimited).use { journal ->
            val store = OutputStore(context, journal::outputExpired)
            val old = run(journal, store, "old", 100)
            run(journal, store, "new", 500)
            run(journal, store, "active", null)
            store.compact(journal, OutputRetentionPolicy(Long.MAX_VALUE, 3), 1000)
            assertEquals(ArtifactReadResult.Expired, read(store, "old"))
            assertFalse(File(context.filesDir, "runtime-output/old").exists())
            assertEquals("new", (read(store, "new") as ArtifactReadResult.Chunk).bytes.toByteArray().toString(Charsets.UTF_8))
            assertEquals("active", (read(store, "active") as ArtifactReadResult.Chunk).bytes.toByteArray().toString(Charsets.UTF_8))
            assertEquals(old, journal.snapshot(old.runId))
            assertEquals(old.runId, journal.find(RequestId("old"))!!.runId)
            assertFalse(journal.expireOutput(RunId("active")))
        }
    }
    @Test fun `committed expiration survives reopen and unfinished physical deletion is retried`() = runBlocking {
        RuntimeJournal(context, unlimited).use { journal ->
            val store = OutputStore(context, journal::outputExpired)
            run(journal, store, "old", 100)
            assertTrue(journal.expireOutput(RunId("old"))) // Simulate death between metadata commit and file removal.
            assertTrue(File(context.filesDir, "runtime-output/old/0").exists())
            File(context.filesDir, "runtime-output/old/1.tmp").writeText("interrupted write")
        }
        RuntimeJournal(context, unlimited).use { journal ->
            val store = OutputStore(context, journal::outputExpired)
            assertEquals(ArtifactReadResult.Expired, read(store, "old"))
            try { store.write(RunId("old"), "1", "resurrection"); fail("expired run cannot be rewritten") } catch (_: IllegalStateException) { }
            store.compact(journal, OutputRetentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE), 1000)
            assertFalse(File(context.filesDir, "runtime-output/old").exists())
            assertEquals(ArtifactReadResult.Expired, read(store, "old"))
            assertTrue(read(store, "missing") is ArtifactReadResult.Unavailable)
        }
    }
    @Test fun `age cleanup cannot follow symlinks and retry completes after obstruction is removed`() = runBlocking {
        RuntimeJournal(context, unlimited).use { journal ->
            val store = OutputStore(context, journal::outputExpired)
            run(journal, store, "old", 100)
            val outside = File(context.filesDir, "outside-fixture").apply { writeText("keep") }
            val link = File(context.filesDir, "runtime-output/old/1")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            try { store.compact(journal, OutputRetentionPolicy(100, Long.MAX_VALUE), 1000); fail("unsafe entry") } catch (_: IllegalStateException) { }
            assertEquals("keep", outside.readText())
            assertFalse(journal.outputExpired(RunId("old")))
            assertEquals(ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.PERMISSION_DENIED)), store.read(ArtifactReadRequest(ResourceRef("old/1"), 0, 100)))
            assertTrue(link.delete())
            store.compact(journal, OutputRetentionPolicy(100, Long.MAX_VALUE), 1000)
            assertEquals(ArtifactReadResult.Expired, read(store, "old"))
            assertEquals("keep", outside.readText())
            outside.delete()
        }
        Unit
    }
    @Test fun `version one migration preserves state and grants a fresh age window when old events were compacted`() = runBlocking {
        lateinit var terminal: RunSnapshot
        RuntimeJournal(context, EventHistoryPolicy(0, 0)) { 1000 }.use { journal ->
            terminal = run(journal, OutputStore(context), "old", 100)
            assertTrue(journal.eventsAfter(terminal.runId, 0, 10).isEmpty())
            journal.writableDatabase.execSQL("DROP TABLE output_retention")
            journal.writableDatabase.execSQL("PRAGMA user_version=1")
        }
        RuntimeJournal(context, unlimited) { 1000 }.use { journal ->
            assertEquals(2, journal.readableDatabase.version)
            assertEquals(terminal, journal.snapshot(terminal.runId))
            assertEquals(1000L, journal.outputCandidates().single().finishedAt)
            val store = OutputStore(context, journal::outputExpired)
            store.compact(journal, OutputRetentionPolicy(100, Long.MAX_VALUE), 1000)
            assertTrue(read(store, "old") is ArtifactReadResult.Chunk)
        }
    }
}
