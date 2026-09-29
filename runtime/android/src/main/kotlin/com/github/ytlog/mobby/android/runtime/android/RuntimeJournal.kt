package com.github.ytlog.mobby.android.runtime.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Retention for redundant terminal events, excluding output files and durable request indexes. */
internal data class EventHistoryPolicy(val maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1000, val maxBytes: Long = 32L * 1024 * 1024) {
    init { require(maxAgeMillis >= 0 && maxBytes >= 0) }
}

/** Runtime is the only writer; separate from conversations. No gateway credentials or run prompts.
 * Approvals are stored as ApprovalSubject. Opening a newer journal drops the previous event log. */
internal class RuntimeJournal(context: Context, private val historyPolicy: EventHistoryPolicy = EventHistoryPolicy(),
    private val policyProvider: () -> EventHistoryPolicy = { historyPolicy },
    private val clock: () -> Long = System::currentTimeMillis) : SQLiteOpenHelper(context, "runtime-journal.db", null, 3), JournalPort {
    private val json = Json { ignoreUnknownKeys = true }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE runs (id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL, digest TEXT NOT NULL, snapshot TEXT NOT NULL, sequence INTEGER NOT NULL, busy INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX execution_slot ON runs(busy) WHERE busy=1")
        db.execSQL("CREATE TABLE commands (id TEXT PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events (run_id TEXT NOT NULL REFERENCES runs(id), sequence INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(run_id,sequence))")
        createOutputRetention(db)
    }
    private fun createOutputRetention(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE output_retention (run_id TEXT PRIMARY KEY NOT NULL REFERENCES runs(id), finished_at INTEGER NOT NULL, expired INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Development schemas are replaced, not migrated. Only this journal is reset;
        // encrypted settings, HOME and workspaces are owned by separate stores.
        db.execSQL("DROP TABLE IF EXISTS output_retention")
        db.execSQL("DROP TABLE IF EXISTS events")
        db.execSQL("DROP TABLE IF EXISTS commands")
        db.execSQL("DROP TABLE IF EXISTS runs")
        onCreate(db)
    }
    private fun recordOutputCompletion(db: SQLiteDatabase, id: RunId, time: Long) {
        db.insertWithOnConflict("output_retention", null, ContentValues().apply { put("run_id", id.value); put("finished_at", time) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    suspend fun outputCandidates(): List<OutputRetentionCandidate> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT o.run_id,o.finished_at,o.expired FROM output_retention o JOIN runs r ON r.id=o.run_id WHERE r.busy=0 ORDER BY o.finished_at,o.run_id", emptyArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(OutputRetentionCandidate(RunId(cursor.getString(0)), cursor.getLong(1), cursor.getInt(2) != 0)) }
        }
    }
    suspend fun outputExpired(id: RunId): Boolean = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT expired FROM output_retention WHERE run_id=?", arrayOf(id.value)).use { it.moveToFirst() && it.getInt(0) != 0 }
    }
    suspend fun expireOutput(id: RunId): Boolean = withContext(Dispatchers.IO) {
        writableDatabase.update("output_retention", ContentValues().apply { put("expired", 1) },
            "run_id=? AND run_id IN (SELECT id FROM runs WHERE busy=0)", arrayOf(id.value)) == 1
    }
    override suspend fun command(id: CommandId): CommandRecord? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT body FROM commands WHERE id=?", arrayOf(id.value)).use {
            if (it.moveToFirst()) json.decodeFromString<CommandRecord>(it.getString(0)) else null
        }
    }
    override suspend fun recordCommand(command: CommandRecord) = withContext(Dispatchers.IO) { transaction { insertCommand(it, command) } }
    override suspend fun find(requestId: RequestId): RequestRecord? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT digest,id FROM runs WHERE request_id=?", arrayOf(requestId.value)).use {
            if (it.moveToFirst()) RequestRecord(it.getString(0), RunId(it.getString(1))) else null
        }
    }
    override suspend fun accept(requestId: RequestId, digest: String, snapshot: RunSnapshot, event: EventEnvelope) = withContext(Dispatchers.IO) {
        transaction { db ->
            db.insertOrThrow("runs", null, ContentValues().apply {
                put("id", snapshot.runId.value); put("request_id", requestId.value); put("digest", digest)
                put("snapshot", json.encodeToString(snapshot)); put("sequence", snapshot.lastSequence); put("busy", 1)
            })
            insertEvent(db, event)
        }
    }
    override suspend fun append(snapshot: RunSnapshot, event: EventEnvelope, command: CommandRecord?) = withContext(Dispatchers.IO) {
        transaction { db ->
            check(db.update("runs", ContentValues().apply {
                put("snapshot", json.encodeToString(snapshot)); put("sequence", snapshot.lastSequence)
                // Unknown device effects do not keep the execution slot when the agent process has exited.
                put("busy", if (RunStateRules.occupiesExecution(snapshot)) 1 else 0)
            }, "id=? AND sequence=?", arrayOf(snapshot.runId.value, (snapshot.lastSequence - 1).toString())) == 1) { "Journal sequence conflict" }
            insertEvent(db, event)
            if (command != null) insertCommand(db, command)
            if (snapshot.phase.terminal) { recordOutputCompletion(db, snapshot.runId, event.occurredAtEpochMillis); compact(db) }
        }
    }
    /** Only redundant terminal event history is removed. Snapshots, request digests and receipts survive. */
    suspend fun compact() = withContext(Dispatchers.IO) { transaction { compact(it) } }
    private fun compact(db: SQLiteDatabase) {
        data class Candidate(val id: String, val finished: Long, val bytes: Long)
        val candidates = db.rawQuery("""SELECT r.id,r.snapshot,e.body,
            (SELECT SUM(length(CAST(x.body AS BLOB))) FROM events x WHERE x.run_id=r.id)
            FROM runs r JOIN events e ON e.run_id=r.id AND e.sequence=r.sequence WHERE r.busy=0""", emptyArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val snapshot = json.decodeFromString<RunSnapshot>(cursor.getString(1))
                    val event = json.decodeFromString<EventEnvelope>(cursor.getString(2))
                    if (snapshot.phase.terminal && (event.payload is RuntimeEvent.RunFinished || event.payload is RuntimeEvent.ProcessTerminationConfirmed))
                        add(Candidate(cursor.getString(0), event.occurredAtEpochMillis, cursor.getLong(3)))
                }
            }
        }.sortedWith(compareBy<Candidate> { it.finished }.thenBy { it.id })
        var bytes = candidates.sumOf { it.bytes }
        val policy = policyProvider()
        val cutoff = clock() - policy.maxAgeMillis
        for (candidate in candidates) {
            if (candidate.finished <= cutoff || bytes > policy.maxBytes) {
                db.delete("events", "run_id=?", arrayOf(candidate.id))
                bytes -= candidate.bytes
            }
        }
    }
    override suspend fun snapshot(runId: RunId): RunSnapshot? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT snapshot FROM runs WHERE id=?", arrayOf(runId.value)).use {
            if (it.moveToFirst()) json.decodeFromString<RunSnapshot>(it.getString(0)) else null
        }
    }
    override suspend fun eventsAfter(runId: RunId, sequence: Long, limit: Int): List<EventEnvelope> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT body FROM events WHERE run_id=? AND sequence>? ORDER BY sequence LIMIT ?",
            arrayOf(runId.value, sequence.toString(), limit.coerceIn(1, 128).toString())).use {
            buildList { while (it.moveToNext()) add(json.decodeFromString<EventEnvelope>(it.getString(0))) }
        }
    }
    override suspend fun unfinished(): List<RunSnapshot> = withContext(Dispatchers.IO) {
        // Include previously released unknown runs so recovery also updates their UI evidence.
        readableDatabase.rawQuery("SELECT snapshot,busy FROM runs", emptyArray()).use {
            buildList {
                while (it.moveToNext()) {
                    val snapshot = json.decodeFromString<RunSnapshot>(it.getString(0))
                    if (it.getInt(1) == 1 || RunStateRules.occupiesExecution(snapshot))
                        add(snapshot)
                }
            }
        }
    }
    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun insertCommand(db: SQLiteDatabase, command: CommandRecord) {
        db.insertOrThrow("commands", null, ContentValues().apply { put("id", command.id.value); put("body", json.encodeToString(command)) })
    }
    private fun insertEvent(db: SQLiteDatabase, event: EventEnvelope) {
        db.insertOrThrow("events", null, ContentValues().apply {
            put("run_id", event.runId.value); put("sequence", event.sequence); put("body", json.encodeToString(event))
        })
    }
}

internal data class OutputRetentionCandidate(val id: RunId, val finishedAt: Long, val expired: Boolean)
