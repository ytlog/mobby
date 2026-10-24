package com.github.ytlog.mobby.android.programs.base.storage

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteProgram
import com.github.ytlog.mobby.android.programs.capability.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * `storage.sql`: one SQLite database per program space. One statement per call, typed
 * positional parameters, and statements that could reach other files are refused.
 */
class SqlCapability(private val limits: StorageLimits = StorageLimits.DEFAULT) : CapabilityHandler {
    private val paramType = buildJsonObject {
        put("type", JsonArray(listOf("string", "number", "boolean", "null").map(::JsonPrimitive)))
        put("maxLength", 64 * 1024)
    }
    private val paramsType = buildJsonObject { put("type", "array"); put("maxItems", 100); put("items", paramType) }
    override val descriptor = storageDescriptor("storage.sql", CapabilityKind.COMMAND,
        schema(listOf("op"), "op" to enumType("query", "execute", "transaction"), "sql" to stringType(64 * 1024),
            "params" to paramsType,
            "statements" to buildJsonObject {
                put("type", "array"); put("maxItems", 100)
                put("items", schema(listOf("sql"), "sql" to stringType(64 * 1024), "params" to paramsType))
            }),
        setOf(SideEffect.READ, SideEffect.WRITE, SideEffect.DELETE))

    private val databases = ConcurrentHashMap<String, Pair<SQLiteDatabase, Mutex>>()

    override fun availability(): Availability = Availability.Available

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome {
        val file = File(call.space.files.parentFile, "sql/main.db")
        val (db, lock) = open(file)
        return lock.withLock {
            try {
                when (call.args.text("op")) {
                    "query" -> CapabilityOutcome(Effect.NONE, query(db, statement(call.args), params(call.args)))
                    "execute" -> CapabilityOutcome(Effect.CONFIRMED, execute(db, statement(call.args), params(call.args)))
                    "transaction" -> {
                        val statements = (call.args["statements"] as? JsonArray ?: invalid("statements_required")).map { it.jsonObject }
                        if (statements.isEmpty()) invalid("statements_required")
                        db.beginTransaction()
                        try {
                            val results = statements.map { execute(db, statement(it), params(it)) }
                            db.setTransactionSuccessful()
                            CapabilityOutcome(Effect.CONFIRMED, JsonArray(results))
                        } finally { db.endTransaction() }
                    }
                    else -> invalid("unknown_op")
                }
            } catch (_: SQLiteFullException) {
                throw CapabilityException(ProgramErrorCode.QUOTA_EXCEEDED, "sql_quota")
            } catch (error: SQLiteException) {
                throw CapabilityException(ProgramErrorCode.INVALID_ARGUMENT, "sql_error: ${error.message?.substringBefore(" (code")}")
            }
        }
    }

    /** Closes cached connections, e.g. before deleting program data. */
    fun close(programSpaceRoot: File) {
        val prefix = programSpaceRoot.canonicalPath + File.separator
        databases.keys.filter { it.startsWith(prefix) }.forEach { key -> databases.remove(key)?.first?.close() }
    }

    private fun open(file: File): Pair<SQLiteDatabase, Mutex> = databases.getOrPut(file.canonicalPath) {
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.pageSize.takeIf { it > 0 }?.let { page -> db.maximumSize = limits.sqlBytes.coerceAtLeast(page) }
        runCatching { db.execSQL("PRAGMA trusted_schema = OFF") }
        db to Mutex()
    }

    private fun statement(args: JsonObject): String {
        val sql = args.text("sql") ?: invalid("sql_required")
        SqlGuard.problem(sql)?.let(::invalid)
        return sql
    }

    private fun params(args: JsonObject): List<JsonPrimitive> = (args["params"] as? JsonArray)?.map { it as? JsonPrimitive ?: invalid("params_must_be_scalars") } ?: emptyList()

    private fun bind(program: SQLiteProgram, params: List<JsonPrimitive>) {
        params.forEachIndexed { i, value ->
            val index = i + 1
            when {
                value is JsonNull -> program.bindNull(index)
                value.isString -> program.bindString(index, value.content)
                value.booleanOrNull != null -> program.bindLong(index, if (value.boolean) 1 else 0)
                value.longOrNull != null -> program.bindLong(index, value.long)
                else -> program.bindDouble(index, value.double)
            }
        }
    }

    private fun query(db: SQLiteDatabase, sql: String, params: List<JsonPrimitive>): JsonObject {
        val cursor = db.rawQueryWithFactory({ database, driver, table, query -> bind(query, params); SQLiteCursor(driver, table, query) }, sql, null, "")
        return cursor.use { c ->
            var bytes = 0
            val rows = mutableListOf<JsonArray>()
            while (c.moveToNext()) {
                if (rows.size >= limits.sqlRows) invalid("result_has_more_than_${limits.sqlRows}_rows")
                val row = JsonArray((0 until c.columnCount).map { column -> value(c, column) })
                bytes += row.toString().length
                if (bytes > limits.sqlResultBytes) invalid("result_too_large")
                rows += row
            }
            buildJsonObject {
                put("columns", JsonArray(c.columnNames.map(::JsonPrimitive)))
                put("rows", JsonArray(rows))
            }
        }
    }

    private fun execute(db: SQLiteDatabase, sql: String, params: List<JsonPrimitive>): JsonObject = db.compileStatement(sql).use { statement ->
        bind(statement, params)
        val verb = SqlGuard.firstKeyword(sql)
        when (verb) {
            "INSERT", "REPLACE" -> buildJsonObject { put("lastInsertRowId", statement.executeInsert()); put("changes", 1) }
            "UPDATE", "DELETE" -> buildJsonObject { put("changes", statement.executeUpdateDelete()) }
            else -> { statement.execute(); buildJsonObject { put("changes", 0) } }
        }
    }

    private fun value(cursor: Cursor, column: Int): JsonElement = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(column))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(column))
        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(java.util.Base64.getEncoder().encodeToString(cursor.getBlob(column)))
        else -> JsonPrimitive(cursor.getString(column))
    }
}

/** Lexical checks outside string literals, quoted identifiers and comments. */
internal object SqlGuard {
    private val FORBIDDEN = setOf("ATTACH", "DETACH")
    private val ALLOWED_PRAGMAS = setOf("user_version", "foreign_keys", "table_info", "index_list", "index_info", "table_list")

    fun problem(sql: String): String? {
        val words = tokens(sql) ?: return "unterminated_literal_or_comment"
        if (words.isEmpty()) return "empty_statement"
        val separators = words.indexOfFirst { it == ";" }
        if (separators >= 0 && words.drop(separators + 1).any { it != ";" }) return "one_statement_per_call"
        val upper = words.map { it.uppercase() }
        upper.firstOrNull { it in FORBIDDEN }?.let { return "${it.lowercase()}_not_allowed" }
        if (upper.windowed(2).any { it == listOf("VACUUM", "INTO") }) return "vacuum_into_not_allowed"
        if (upper.any { it == "LOAD_EXTENSION" }) return "load_extension_not_allowed"
        if (upper.first() == "PRAGMA") {
            val name = words.getOrNull(1)?.lowercase()?.substringAfter('.')
            if (name !in ALLOWED_PRAGMAS) return "pragma_not_allowed"
        }
        return null
    }

    fun firstKeyword(sql: String) = tokens(sql)?.firstOrNull { it != ";" && it != "(" }?.uppercase() ?: ""

    /** Words and `;`; null when a literal or comment is not closed. */
    private fun tokens(sql: String): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    var j = i + 1
                    while (j < sql.length && sql[j] != close) j++
                    if (j >= sql.length) return null
                    i = j + 1
                }
                c == '-' && sql.getOrNull(i + 1) == '-' -> { val end = sql.indexOf('\n', i); i = if (end < 0) sql.length else end + 1 }
                c == '/' && sql.getOrNull(i + 1) == '*' -> { val end = sql.indexOf("*/", i + 2); if (end < 0) return null; i = end + 2 }
                c == ';' -> { out += ";"; i++ }
                c == '(' -> { out += "("; i++ }
                c.isLetter() || c == '_' -> { var j = i; while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_' || sql[j] == '.')) j++; out += sql.substring(i, j); i = j }
                else -> i++
            }
        }
        return out
    }
}
