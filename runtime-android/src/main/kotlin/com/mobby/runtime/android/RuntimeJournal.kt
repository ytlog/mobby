package com.mobby.runtime.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mobby.runtime.api.*
import com.mobby.runtime.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Runtime is the only writer; separate from the conversation database. No credentials or input bodies. */
internal class RuntimeJournal(context: Context) : SQLiteOpenHelper(context, "runtime-journal.db", null, 1), JournalPort {
    private val json = Json { ignoreUnknownKeys = true }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE runs (id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL, digest TEXT NOT NULL, snapshot TEXT NOT NULL, sequence INTEGER NOT NULL, busy INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX execution_slot ON runs(busy) WHERE busy=1")
        db.execSQL("CREATE TABLE commands (id TEXT PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events (run_id TEXT NOT NULL REFERENCES runs(id), sequence INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(run_id,sequence))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Explicit runtime journal migration required") }
    override suspend fun command(id: CommandId): CommandRecord? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT body FROM commands WHERE id=?", arrayOf(id.value)).use {
            if (it.moveToFirst()) json.decodeFromString<CommandRecord>(it.getString(0)) else null
        }
    }
    override suspend fun recordCommand(command: CommandRecord) = withContext(Dispatchers.IO) { transaction { insertCommand(it, command) } }
    override suspend fun releaseRecoveredSlot(runId: RunId) = withContext(Dispatchers.IO) {
        writableDatabase.update("runs", ContentValues().apply { put("busy", 0) }, "id=?", arrayOf(runId.value))
        Unit
    }
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
                put("busy", if (!snapshot.phase.terminal || snapshot.phase == RunPhase.OUTCOME_UNKNOWN) 1 else 0)
            }, "id=? AND sequence=?", arrayOf(snapshot.runId.value, (snapshot.lastSequence - 1).toString())) == 1) { "Journal sequence conflict" }
            insertEvent(db, event)
            if (command != null) insertCommand(db, command)
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
        readableDatabase.rawQuery("SELECT snapshot FROM runs WHERE busy=1", emptyArray()).use {
            buildList { while (it.moveToNext()) add(json.decodeFromString<RunSnapshot>(it.getString(0))) }
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
