package com.github.ytlog.mobby.android.localmodel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

class LocalModelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var auth: LocalModelAuth
    private lateinit var store: LocalModelStore
    private lateinit var engine: ModelEngine
    private var server: ApplicationEngine? = null
    private val instanceId = UUID.randomUUID().toString()
    private val port = 11435

    override fun onCreate() {
        super.onCreate()
        auth = LocalModelAuth(this)
        store = LocalModelStore(this)
        engine = ModelEngine(store)
        val channels = getSystemService(NotificationManager::class.java)
        channels.createNotificationChannel(NotificationChannel("local-model", "Local model server", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "local-model").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Local model service").setContentText("HTTP server on 127.0.0.1:$port")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(342, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(342, notification)
        scope.launch {
            try { server = embeddedServer(CIO, host = "127.0.0.1", port = port) { routes() }.start(wait = false) }
            catch (_: Exception) { stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        server?.stop(100, 1_000)
        engine.unload()
        scope.cancel()
        super.onDestroy()
    }

    private fun token(call: ApplicationCall): String? {
        val bearer = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        val key = call.request.headers["x-api-key"]
        val google = call.request.headers["x-goog-api-key"]
        val values = listOfNotNull(bearer, key, google).distinct()
        return values.singleOrNull()
    }

    private suspend fun permitted(call: ApplicationCall, admin: Boolean = false): Boolean {
        if (auth.allows(token(call), admin)) return true
        call.respondText(Protocol.error("Unauthorized", "authentication_error"), ContentType.Application.Json, HttpStatusCode.Unauthorized)
        return false
    }

    private suspend fun receiveJson(call: ApplicationCall): JsonObject {
        val body = call.receiveText()
        if (body.length > 1_048_576) throw BadRequest("Request too large")
        return Protocol.json.parseToJsonElement(body).jsonObject
    }

    private fun Application.routes() {
        routing {
            get("/local/v1/health") {
                if (!permitted(call)) return@get
                call.respondText(buildJsonObject { put("instanceId", instanceId); put("status", "LISTENING"); put("port", port); put("loadedModel", engine.current()) }.toString(), ContentType.Application.Json)
            }
            get("/local/v1/engines") {
                if (!permitted(call)) return@get
                call.respondText("""{"engines":[{"id":"llama","packaged":true,"formats":["gguf"]}]}""", ContentType.Application.Json)
            }
            get("/local/v1/catalog/models") {
                if (!permitted(call)) return@get
                val backend = call.request.queryParameters["backend"] ?: "llama"
                if (backend != "llama") { call.respondText(Protocol.error("Backend not packaged"), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable); return@get }
                val source = call.request.queryParameters["source"] ?: "modelscope"
                if (ModelDownloadSource.fromId(source) == null) { call.respondText(Protocol.error("Unknown model source"), ContentType.Application.Json, HttpStatusCode.BadRequest); return@get }
                try {
                    val candidates = withContext(Dispatchers.IO) { store.catalog(call.request.queryParameters["family"], source) }
                    call.respondText(Protocol.json.encodeToString(candidates), ContentType.Application.Json)
                } catch (e: Exception) { call.respondText(Protocol.error(e.message ?: "Catalog unavailable"), ContentType.Application.Json, HttpStatusCode.BadGateway) }
            }
            get("/local/v1/models") {
                if (!permitted(call)) return@get
                call.respondText(Protocol.json.encodeToString(store.models()), ContentType.Application.Json)
            }
            get("/local/v1/operations") {
                if (!permitted(call, true)) return@get
                call.respondText(Protocol.json.encodeToString(store.operations.values.sortedBy { it.id }), ContentType.Application.Json)
            }
            post("/local/v1/installs") {
                if (!permitted(call, true)) return@post
                try {
                    val candidate = Protocol.json.decodeFromString<Candidate>(call.receiveText())
                    val operation = withContext(Dispatchers.IO) { store.install(candidate) }
                    call.respondText("""{"operationId":"$operation","status":"queued"}""", ContentType.Application.Json, HttpStatusCode.Accepted)
                } catch (e: Exception) { call.respondText(Protocol.error(e.message ?: "Invalid artifact"), ContentType.Application.Json, HttpStatusCode.BadRequest) }
            }
            get("/local/v1/operations/{id}") {
                if (!permitted(call, true)) return@get
                val progress = store.operations[call.parameters["id"]]
                if (progress == null) call.respondText(Protocol.error("Operation not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                else call.respondText(Protocol.json.encodeToString(progress), ContentType.Application.Json)
            }
            post("/local/v1/loads") {
                if (!permitted(call, true)) return@post
                try {
                    val id = receiveJson(call)["model"]?.jsonPrimitive?.content ?: throw BadRequest("model is required")
                    val loaded = withContext(Dispatchers.IO) { engine.load(id) }
                    call.respondText("""{"model":"$loaded","status":"READY"}""", ContentType.Application.Json)
                } catch (e: Exception) { call.respondText(Protocol.error(e.message ?: "Load failed"), ContentType.Application.Json, HttpStatusCode.Conflict) }
            }
            post("/local/v1/models/unload") {
                if (!permitted(call, true)) return@post
                withContext(Dispatchers.IO) { engine.unload() }
                call.respondText("""{"status":"UNLOADED"}""", ContentType.Application.Json)
            }
            post("/local/v1/server/stop") {
                if (!permitted(call, true)) return@post
                call.respondText("""{"status":"DRAINING"}""", ContentType.Application.Json, HttpStatusCode.Accepted)
                scope.launch { delay(150); stopSelf() }
            }
            get("/v1/models") {
                if (!permitted(call)) return@get
                val models = store.models().map { buildJsonObject { put("id", it.id); put("object", "model"); put("owned_by", "local") } }
                call.respondText(buildJsonObject {
                    put("object", "list"); put("data", JsonArray(models))
                    if (call.request.headers["anthropic-version"] != null) { put("has_more", false); put("first_id", models.firstOrNull()?.get("id") ?: JsonNull); put("last_id", models.lastOrNull()?.get("id") ?: JsonNull) }
                }.toString(), ContentType.Application.Json)
            }
            post("/v1/responses") { infer(call, "responses") }
            post("/v1/messages") { infer(call, "messages") }
            post("/v1/chat/completions") { infer(call, "chat") }
        }
    }

    private suspend fun infer(call: ApplicationCall, protocol: String) {
        if (!permitted(call)) return
        val request = try { Protocol.parse(call.receiveText(), protocol) }
        catch (e: Exception) { call.respondText(Protocol.error(e.message ?: "Invalid request"), ContentType.Application.Json, HttpStatusCode.BadRequest); return }
        if (engine.current() != request.model) { call.respondText(Protocol.error("Model is not loaded"), ContentType.Application.Json, HttpStatusCode.Conflict); return }
        val prefix = when (protocol) { "responses" -> "resp_"; "messages" -> "msg_"; else -> "chatcmpl_" }
        val id = "$prefix${UUID.randomUUID().toString().replace("-", "")}"
        val created = System.currentTimeMillis() / 1000
        if (request.toolContext != null) {
            inferTools(call, protocol, request, id, created)
            return
        }
        if (!request.stream) {
            try {
                val text = StringBuilder()
                var inputTokens = -1
                val count = withContext(Dispatchers.IO) { engine.generate(request.model, request.messages, request.maxTokens, { inputTokens = it; true }) { text.append(it); true } }
                if (count < 0) error("Generation cancelled")
                call.respondText(completion(protocol, id, request.model, created, text.toString(), inputTokens, count, count == request.maxTokens), ContentType.Application.Json)
            } catch (e: Exception) { call.respondText(Protocol.error(e.message ?: "Generation failed", "server_error"), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable) }
            return
        }
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            val output = StringBuilder()
            fun send(event: String?, payload: String) {
                if (event != null) write("event: $event\n")
                write("data: $payload\n\n"); flush()
            }
            try {
                when (protocol) {
                    "responses" -> {
                        send("response.created", """{"type":"response.created","response":{"id":"$id","object":"response","status":"in_progress","model":"${request.model}","output":[]}}""")
                        send("response.output_item.added", """{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_$id","type":"message","role":"assistant","status":"in_progress","content":[]}}""")
                        send("response.content_part.added", """{"type":"response.content_part.added","output_index":0,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""")
                    }
                    "messages" -> Unit
                    "chat" -> send(null, """{"id":"$id","object":"chat.completion.chunk","created":$created,"model":"${request.model}","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""")
                }
                var inputTokens = -1
                val count = withContext(Dispatchers.IO) { engine.generate(request.model, request.messages, request.maxTokens, { count ->
                    inputTokens = count
                    if (protocol == "messages") runCatching {
                        send("message_start", """{"type":"message_start","message":{"id":"$id","type":"message","role":"assistant","model":"${request.model}","content":[],"stop_reason":null,"usage":{"input_tokens":$count,"output_tokens":0}}}""")
                        send("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
                    }.isSuccess else true
                }) { piece ->
                    output.append(piece)
                    val quoted = JsonPrimitive(piece).toString()
                    runCatching {
                        when (protocol) {
                            "responses" -> send("response.output_text.delta", """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":$quoted}""")
                            "messages" -> send("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":$quoted}}""")
                            "chat" -> send(null, """{"id":"$id","object":"chat.completion.chunk","created":$created,"model":"${request.model}","choices":[{"index":0,"delta":{"content":$quoted},"finish_reason":null}]}""")
                        }; true
                    }.getOrDefault(false)
                } }
                if (count < 0) error("Generation cancelled")
                val quoted = JsonPrimitive(output.toString()).toString()
                when (protocol) {
                    "responses" -> {
                        send("response.output_text.done", """{"type":"response.output_text.done","output_index":0,"content_index":0,"text":$quoted}""")
                        send("response.content_part.done", """{"type":"response.content_part.done","output_index":0,"content_index":0,"part":{"type":"output_text","text":$quoted,"annotations":[]}}""")
                        send("response.output_item.done", """{"type":"response.output_item.done","output_index":0,"item":{"id":"msg_$id","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":$quoted,"annotations":[]}]}}""")
                        val event = if (count == request.maxTokens) "response.incomplete" else "response.completed"
                        send(event, """{"type":"$event","response":${completion(protocol, id, request.model, created, output.toString(), inputTokens, count, count == request.maxTokens)}}""")
                    }
                    "messages" -> {
                        send("content_block_stop", """{"type":"content_block_stop","index":0}""")
                        send("message_delta", """{"type":"message_delta","delta":{"stop_reason":"${if (count == request.maxTokens) "max_tokens" else "end_turn"}","stop_sequence":null},"usage":{"output_tokens":$count}}""")
                        send("message_stop", """{"type":"message_stop"}""")
                    }
                    "chat" -> {
                        send(null, """{"id":"$id","object":"chat.completion.chunk","created":$created,"model":"${request.model}","choices":[{"index":0,"delta":{},"finish_reason":"${if (count == request.maxTokens) "length" else "stop"}"}]}""")
                        send(null, "[DONE]")
                    }
                }
            } catch (e: Exception) { runCatching { send("error", Protocol.error(e.message ?: "Generation failed", "server_error")) } }
        }
    }

    private suspend fun inferTools(call: ApplicationCall, protocol: String, request: InferenceRequest, id: String, created: Long) {
        val context = requireNotNull(request.toolContext)
        val completed = try {
            val job = currentCoroutineContext()[Job]
            val raw = withContext(Dispatchers.IO) { engine.generateTools(request.model, context, request.maxTokens) { job?.isActive != false } }
            val turn = ToolResponses.parse(raw, context)
            ToolResponses.completion(protocol, id, request.model, created, turn)
        } catch (e: Exception) {
            call.respondText(Protocol.error(e.message ?: "Tool generation failed", "server_error"), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return
        }
        if (!request.stream) {
            call.respondText(completed.toString(), ContentType.Application.Json)
            return
        }
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            ToolResponses.events(protocol, completed).forEach { (event, payload) ->
                if (event != null) write("event: $event\n")
                write("data: $payload\n\n")
            }
            if (protocol == "chat") write("data: [DONE]\n\n")
            flush()
        }
    }

    private fun completion(protocol: String, id: String, model: String, created: Long, text: String, inputTokens: Int, outputTokens: Int, length: Boolean): String {
        val quoted = JsonPrimitive(text).toString()
        return when (protocol) {
            "responses" -> """{"id":"$id","object":"response","created_at":$created,"status":"${if (length) "incomplete" else "completed"}","model":"$model","usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens,"total_tokens":${inputTokens + outputTokens}},"output":[{"id":"msg_$id","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":$quoted,"annotations":[]}]}]}"""
            "messages" -> """{"id":"$id","type":"message","role":"assistant","model":"$model","content":[{"type":"text","text":$quoted}],"stop_reason":"${if (length) "max_tokens" else "end_turn"}","stop_sequence":null,"usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens}}"""
            else -> """{"id":"$id","object":"chat.completion","created":$created,"model":"$model","choices":[{"index":0,"message":{"role":"assistant","content":$quoted},"finish_reason":"${if (length) "length" else "stop"}"}]}"""
        }
    }
}
