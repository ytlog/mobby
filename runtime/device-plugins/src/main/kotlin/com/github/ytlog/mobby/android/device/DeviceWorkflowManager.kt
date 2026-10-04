package com.github.ytlog.mobby.android.device

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/** The only owner of screen workflow recording, persistence and checked replay. */
internal class DeviceWorkflowManager(context: Context, private val inspect: (String?, String?) -> ScreenEvidence) : AutoCloseable {
    private val store = WorkflowStore(context)
    private var currentId = UUID.randomUUID().toString()
    private var historyError = false
    override fun close() = store.close()

    fun handle(line: String, token: String, allow: Map<String, Set<String>>, execute: (String) -> String): String {
        val raw = try { DeviceJson.parseToJsonElement(line) as? JsonObject } catch (_: Exception) { null }
            ?: return execute(line)
        val request = try { DeviceJson.decodeFromJsonElement<DeviceRequest>(JsonObject(raw - "token")) } catch (_: Exception) { return execute(line) }
        if (request.plugin == "workflow") return command(request, raw, token, allow, execute)
        if (request.plugin != "screen" || request.action !in setOf("click", "type", "back") ||
            (raw["token"] as? JsonPrimitive)?.contentOrNull != token || request.protocolVersion != 1 ||
            request.action !in allow["screen"].orEmpty()) return execute(line)
        if (try { store.hasRequest(request.requestId) } catch (_: Exception) { false }) return execute(line)
        val args = try { DeviceActionDefinition("screen", request.action).validate(request.args) } catch (_: Exception) { return execute(line) }
        val query = args["query"]
        val before = observe(query, null)
        val response = execute(line)
        val receipt = try { DeviceJson.decodeFromString<DeviceResponse>(response) } catch (_: Exception) { null }
        val after = observeAfter(request.action, before, query, args["text"], receipt)
        val checked = stepVerified(request.action, before, after, receipt, null)
        val step = WorkflowStep(request.action, query?.takeIf(::safeLabel), if (request.action == "type") "input" else null,
            before?.mark(), after?.mark(), receipt?.status?.name ?: "unknown", checked, requestId = request.requestId)
        // Device execution remains truthful even when optional history storage is unavailable.
        try {
            if (store.size(currentId) >= 100) currentId = UUID.randomUUID().toString()
            store.append(currentId, step)
        } catch (_: Exception) { historyError = true }
        return response
    }

    private fun command(request: DeviceRequest, raw: JsonObject, token: String, allow: Map<String, Set<String>>,
        execute: (String) -> String): String {
        fun failure(code: DeviceErrorCode, message: String = "") = DeviceJson.encodeToString(DeviceResponse(
            requestId = request.requestId, accepted = false, error = DeviceError(code, message)))
        fun uncertain(code: DeviceErrorCode) = DeviceJson.encodeToString(DeviceResponse(
            requestId = request.requestId, accepted = true, status = DeviceStatus.UNCONFIRMED,
            error = DeviceError(code, effectState = EffectState.UNKNOWN)))
        if (lineSize(raw) > 65_536) return failure(DeviceErrorCode.INVALID_ARGUMENT)
        if ((raw["token"] as? JsonPrimitive)?.contentOrNull != token) return failure(DeviceErrorCode.UNAUTHORIZED)
        if (request.protocolVersion != 1) return failure(DeviceErrorCode.INCOMPATIBLE_VERSION)
        if (!request.requestId.matches(DeviceOperationRules.ID)) return failure(DeviceErrorCode.INVALID_ARGUMENT)
        if (request.parentOperationId != null) return failure(DeviceErrorCode.INVALID_ARGUMENT)
        if ("snapshot" !in allow["screen"].orEmpty()) return failure(DeviceErrorCode.UNAUTHORIZED)
        if (request.args.size > 3) return failure(DeviceErrorCode.INVALID_ARGUMENT)
        val mutating = request.action in setOf("start", "finish", "run", "replay")
        if (mutating) {
            when (val claim = try { store.claim(request.requestId, request.action) } catch (_: Exception) { return failure(DeviceErrorCode.STORAGE_FULL) }) {
                is WorkflowClaim.Done -> return claim.response
                WorkflowClaim.InProgress -> return DeviceJson.encodeToString(DeviceResponse(requestId = request.requestId,
                    accepted = true, status = DeviceStatus.UNCONFIRMED,
                    error = DeviceError(DeviceErrorCode.RESULT_UNCONFIRMED, effectState = EffectState.UNKNOWN)))
                WorkflowClaim.Conflict -> return failure(DeviceErrorCode.REQUEST_CONFLICT)
                WorkflowClaim.New -> Unit
            }
        }
        var dispatched = false
        val response = try {
            val data = when (request.action) {
                "start" -> {
                    require(request.args.keys.all { it == "name" })
                    val name = string(request.args, "name", 80)
                    currentId = UUID.randomUUID().toString()
                    store.create(currentId, name)
                    historyError = false
                    buildJsonObject { put("workflowId", currentId) }
                }
                "finish" -> {
                    require(request.args.isEmpty())
                    val id = currentId
                    val saved = !historyError && store.seal(id)
                    currentId = UUID.randomUUID().toString()
                    buildJsonObject { put("workflowId", id); put("recordedWorkflowReplayable", saved) }
                }
                "list" -> {
                    require(request.args.isEmpty())
                    buildJsonObject { put("workflows", JsonArray(store.list())); put("recordingAvailable", !historyError) }
                }
                "run" -> {
                    require(request.args.keys == setOf("steps"))
                    val plans = request.args["steps"] as? JsonArray ?: throw IllegalArgumentException("steps must be an array")
                    require(plans.size in 1..3)
                    val steps = plans.map { parsePlan(it) }
                    val result = runSteps(steps, emptyMap(), token, allow, execute) { dispatched = true }
                    val saved = result["verified"]?.jsonPrimitive?.boolean == true && !historyError && store.seal(currentId)
                    currentId = UUID.randomUUID().toString()
                    JsonObject(result + ("recordedWorkflowReplayable" to JsonPrimitive(saved)))
                }
                "replay" -> {
                    require(request.args.keys in setOf(setOf("workflowId"), setOf("workflowId", "values")))
                    val id = string(request.args, "workflowId", 128)
                    store.prune()
                    val values = (request.args["values"] ?: JsonObject(emptyMap())) as? JsonObject ?: throw IllegalArgumentException("values must be an object")
                    require(values.size <= 12)
                    val inputs = values.mapValues { (key, value) ->
                        require(key.matches(DeviceOperationRules.ID))
                        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Invalid value")
                        require(text.length <= 2000)
                        text
                    }
                    val steps = store.steps(id)
                    require(store.sealed(id) && steps.isNotEmpty() && steps.size <= 12 && steps.all { it.replayable })
                    currentId = UUID.randomUUID().toString()
                    val result = runSteps(steps, inputs, token, allow, execute) { dispatched = true }
                    val saved = result["verified"]?.jsonPrimitive?.boolean == true && !historyError && store.seal(currentId)
                    currentId = UUID.randomUUID().toString()
                    JsonObject(result + ("recordedWorkflowReplayable" to JsonPrimitive(saved)))
                }
                else -> return failure(DeviceErrorCode.UNSUPPORTED_CAPABILITY)
            }
            val verified = data["verified"]?.jsonPrimitive?.booleanOrNull ?: true
            val dispatched = data["dispatched"]?.jsonPrimitive?.intOrNull ?: 0
            val unknown = data["stoppedReason"]?.jsonPrimitive?.contentOrNull == "effect_unknown"
            val status = when {
                verified -> DeviceStatus.SUCCEEDED
                unknown -> DeviceStatus.UNCONFIRMED
                dispatched > 0 -> DeviceStatus.PARTIAL
                else -> DeviceStatus.FAILED
            }
            val effect = when (status) {
                DeviceStatus.UNCONFIRMED -> EffectState.UNKNOWN
                DeviceStatus.PARTIAL -> EffectState.PARTIAL
                else -> EffectState.NONE
            }
            DeviceJson.encodeToString(DeviceResponse(requestId = request.requestId, accepted = true,
                status = status, result = DeviceResult("workflow", effect, data),
                error = if (status in setOf(DeviceStatus.FAILED, DeviceStatus.UNCONFIRMED))
                    DeviceError(DeviceErrorCode.RESULT_UNCONFIRMED, effectState = effect) else null))
        } catch (error: IllegalArgumentException) {
            if (dispatched) uncertain(DeviceErrorCode.RESULT_UNCONFIRMED) else failure(DeviceErrorCode.INVALID_ARGUMENT, error.message.orEmpty().take(160))
        } catch (error: IllegalStateException) {
            if (dispatched) uncertain(DeviceErrorCode.RESULT_UNCONFIRMED) else failure(DeviceErrorCode.UNAVAILABLE, error.message.orEmpty().take(160))
        } catch (_: Exception) { if (dispatched) uncertain(DeviceErrorCode.STORAGE_FULL) else failure(DeviceErrorCode.STORAGE_FULL) }
        if (mutating) try { store.complete(request.requestId, response) } catch (_: Exception) {
            return uncertain(DeviceErrorCode.STORAGE_FULL)
        }
        return response
    }

    private fun parsePlan(value: JsonElement): WorkflowStep {
        val obj = value as? JsonObject ?: throw IllegalArgumentException("step must be an object")
        require(obj.keys.all { it in setOf("action", "query", "text") })
        val action = string(obj, "action", 16)
        require(action in setOf("click", "type", "back"))
        val query = if (action == "click") string(obj, "query", 200) else null
        if (query != null) require(safeLabel(query))
        if (action == "type") string(obj, "text", 2000)
        require(obj.keys == when (action) {
            "click" -> setOf("action", "query")
            "type" -> setOf("action", "text")
            else -> setOf("action")
        })
        // The actual text is kept in this request, never in a WorkflowStep.
        return WorkflowStep(action, query, if (action == "type") "input" else null, null, null, "proposed", false,
            transientText = if (action == "type") string(obj, "text", 2000) else null)
    }

    private fun runSteps(steps: List<WorkflowStep>, values: Map<String, String>, token: String,
        allow: Map<String, Set<String>>, execute: (String) -> String, onDispatch: () -> Unit): JsonObject {
        var completed = 0
        var dispatched = 0
        var reason = ""
        for ((index, step) in steps.withIndex()) {
            if (step.action !in allow["screen"].orEmpty()) { reason = "action_not_authorized"; break }
            val input = if (step.action == "type") step.transientText ?: values["input_${index + 1}"] ?: step.slot?.let(values::get) else null
            if (step.action == "type" && input.isNullOrBlank()) { reason = "missing_input_${index + 1}"; break }
            val before = observe(step.query, null)
            if (before == null || before.packageName.isBlank() ||
                (step.before != null && !step.before.matches(before)) ||
                (step.action == "click" && before.matches != 1) ||
                (step.action == "type" && (before.passwordInput || (!before.focusedEditable && before.editable != 1)))) {
                reason = "page_or_target_mismatch"; break
            }
            val args = when (step.action) {
                "click" -> buildJsonObject { put("query", step.query) }
                "type" -> buildJsonObject { put("text", input) }
                else -> JsonObject(emptyMap())
            }
            DeviceActionDefinition("screen", step.action).validate(args)
            val inner = buildJsonObject {
                put("token", token); put("protocolVersion", 1); put("requestId", UUID.randomUUID().toString())
                put("plugin", "screen"); put("action", step.action); put("args", args)
            }
            dispatched++
            onDispatch()
            val response = DeviceJson.decodeFromString<DeviceResponse>(handle(inner.toString(), token, allow, execute))
            if (historyError) { reason = "history_storage_unavailable"; break }
            val after = observe(step.query, input)
            if (!stepVerified(step.action, before, after, response, step.after)) {
                reason = if (response.status == DeviceStatus.UNCONFIRMED) "effect_unknown" else "postcondition_failed"
                break
            }
            completed++
        }
        return buildJsonObject {
            put("completed", completed); put("dispatched", dispatched); put("total", steps.size); put("verified", completed == steps.size)
            if (reason.isNotEmpty()) put("stoppedReason", reason)
            put("recordedWorkflowId", currentId)
        }
    }

    private fun stepVerified(action: String, before: ScreenEvidence?, after: ScreenEvidence?,
        response: DeviceResponse?, expectedAfter: PageMark?): Boolean {
        if (response?.accepted != true || response.status != DeviceStatus.SUCCEEDED || before == null || after == null) return false
        if (expectedAfter != null && !expectedAfter.matches(after)) return false
        return if (action == "type") !before.passwordInput && after.inputMatches else
            before.packageName != after.packageName || before.shape != after.shape
    }

    private fun observe(query: String?, input: String?): ScreenEvidence? = try { inspect(query, input) } catch (_: Exception) { null }

    private fun observeAfter(action: String, before: ScreenEvidence?, query: String?, input: String?,
        receipt: DeviceResponse?): ScreenEvidence? {
        if (receipt?.status != DeviceStatus.SUCCEEDED || before == null) return observe(query, input)
        val deadline = System.nanoTime() + 2_000_000_000L
        var latest: ScreenEvidence?
        do {
            latest = observe(query, input)
            if (latest != null && if (action == "type") latest.inputMatches else
                    latest.packageName != before.packageName || latest.shape != before.shape) return latest
            if (System.nanoTime() >= deadline) return latest
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return latest
            }
        } while (true)
    }

    private fun lineSize(raw: JsonObject) = raw.toString().toByteArray().size
    private fun safeLabel(value: String): Boolean = value.isNotBlank() && value.length <= 40 &&
        value.none { it.isDigit() || it in "@:/\\\n\r" }
    private fun string(obj: JsonObject, key: String, max: Int): String {
        val value = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Missing $key")
        require(value.isNotBlank() && value.length <= max)
        return value
    }
}

@Serializable internal data class PageMark(val packageName: String, val shape: String) {
    fun matches(state: ScreenEvidence) = packageName == state.packageName && shape == state.shape
}
private fun ScreenEvidence.mark() = PageMark(packageName, shape)

private sealed interface WorkflowClaim {
    data object New : WorkflowClaim
    data object InProgress : WorkflowClaim
    data object Conflict : WorkflowClaim
    data class Done(val response: String) : WorkflowClaim
}

@Serializable internal data class WorkflowStep(val action: String, val query: String?, val slot: String?,
    val before: PageMark?, val after: PageMark?, val status: String, val replayable: Boolean,
    val requestId: String? = null,
    @kotlinx.serialization.Transient val transientText: String? = null)

private class WorkflowStore(context: Context) : SQLiteOpenHelper(context, "device-workflow-history.db", null, 3) {
    private val json = Json { encodeDefaults = true }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE workflow (id TEXT PRIMARY KEY, name TEXT NOT NULL, created INTEGER NOT NULL, sealed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE step (workflow_id TEXT NOT NULL REFERENCES workflow(id) ON DELETE CASCADE, idx INTEGER NOT NULL, request_id TEXT UNIQUE, body TEXT NOT NULL, PRIMARY KEY(workflow_id,idx))")
        db.execSQL("CREATE TABLE command (id TEXT PRIMARY KEY, action TEXT NOT NULL, response TEXT)")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS command"); db.execSQL("DROP TABLE IF EXISTS step"); db.execSQL("DROP TABLE IF EXISTS workflow"); onCreate(db)
    }
    fun claim(id: String, action: String): WorkflowClaim {
        val db = writableDatabase
        val inserted = db.insertWithOnConflict("command", null, ContentValues().apply {
            put("id", id); put("action", action)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted != -1L) return WorkflowClaim.New
        return db.rawQuery("SELECT action,response FROM command WHERE id=?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) WorkflowClaim.InProgress
            else if (cursor.getString(0) != action) WorkflowClaim.Conflict
            else if (cursor.isNull(1)) WorkflowClaim.InProgress
            else WorkflowClaim.Done(cursor.getString(1))
        }
    }
    fun complete(id: String, response: String) {
        check(writableDatabase.update("command", ContentValues().apply { put("response", response) },
            "id=? AND response IS NULL", arrayOf(id)) == 1)
    }
    fun hasRequest(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM step WHERE request_id=?", arrayOf(id)).use { it.moveToFirst() }
    fun create(id: String, name: String) {
        writableDatabase.insertOrThrow("workflow", null, ContentValues().apply {
            put("id", id); put("name", name); put("created", System.currentTimeMillis())
        })
    }
    fun append(id: String, step: WorkflowStep) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("workflow", null, ContentValues().apply {
                put("id", id); put("name", "手机操作"); put("created", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val index = db.rawQuery("SELECT COALESCE(MAX(idx),0)+1 FROM step WHERE workflow_id=?", arrayOf(id)).use {
                it.moveToFirst(); it.getInt(0)
            }
            val stored = step.copy(slot = if (step.action == "type") "input_$index" else null, transientText = null)
            db.insertOrThrow("step", null, ContentValues().apply {
                put("workflow_id", id); put("idx", index); put("request_id", stored.requestId); put("body", json.encodeToString(stored))
            })
            prune(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun size(id: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM step WHERE workflow_id=?", arrayOf(id)).use {
        it.moveToFirst(); it.getInt(0)
    }
    fun seal(id: String): Boolean {
        val entries = steps(id)
        if (entries.isEmpty() || entries.size > 12 || entries.any { !it.replayable }) return false
        return writableDatabase.update("workflow", ContentValues().apply { put("sealed", 1) }, "id=?", arrayOf(id)) == 1
    }
    fun sealed(id: String): Boolean = readableDatabase.rawQuery("SELECT sealed FROM workflow WHERE id=?", arrayOf(id)).use {
        it.moveToFirst() && it.getInt(0) == 1
    }
    fun prune() = prune(writableDatabase)
    private fun prune(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM workflow WHERE created < ?", arrayOf(System.currentTimeMillis() - 30L * 86_400_000))
        db.execSQL("DELETE FROM workflow WHERE id NOT IN (SELECT id FROM workflow ORDER BY created DESC LIMIT 100)")
    }
    fun steps(id: String): List<WorkflowStep> = readableDatabase.rawQuery(
        "SELECT body FROM step WHERE workflow_id=? ORDER BY idx", arrayOf(id)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(json.decodeFromString<WorkflowStep>(cursor.getString(0))) }
    }
    fun list(): List<JsonObject> { prune(); return readableDatabase.rawQuery(
        "SELECT id,name,sealed FROM workflow ORDER BY created DESC LIMIT 20", emptyArray()).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val steps = steps(id)
                if (steps.isEmpty()) continue
                add(buildJsonObject {
                    put("workflowId", id); put("name", cursor.getString(1)); put("replayable", cursor.getInt(2) == 1 && steps.size <= 12 && steps.all { it.replayable })
                    put("stepCount", steps.size)
                    put("steps", JsonArray(steps.take(12).mapIndexed { index, step -> buildJsonObject {
                        put("index", index + 1); put("action", step.action); step.query?.let { put("query", it) }
                        step.slot?.let { put("slot", it) }; put("status", step.status)
                    } }))
                })
            }
        }
    } }
}
