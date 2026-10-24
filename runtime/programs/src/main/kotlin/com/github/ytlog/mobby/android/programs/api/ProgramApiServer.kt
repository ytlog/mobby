package com.github.ytlog.mobby.android.programs.api

import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.CallPipeline
import com.github.ytlog.mobby.android.programs.call.CallRequest
import com.github.ytlog.mobby.android.programs.capability.Availability
import com.github.ytlog.mobby.android.programs.capability.CapabilityRegistry
import com.github.ytlog.mobby.android.programs.capability.ProgramErrorCode
import com.github.ytlog.mobby.android.programs.permission.PermissionGate
import com.github.ytlog.mobby.android.programs.permission.PermissionManager
import com.github.ytlog.mobby.android.programs.permission.Scope
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.readBytes
import kotlinx.serialization.json.*

/** Maps a run credential to its caller; credentials exist only in memory while their run lives. */
fun interface CallerResolver { fun resolve(credential: String): CallContext? }

/** Extra endpoints contributed by later components (events, files, UI messages) behind the same authentication. */
fun interface ApiExtension { fun install(routing: Routing, auth: suspend (ApplicationCall) -> CallContext?) }

/**
 * Loopback HTTP for background processes. Every request needs a run credential; requests from
 * browsers (Origin header) or with a non-loopback Host are refused, and no CORS is offered.
 */
class ProgramApiServer(
    private val pipeline: CallPipeline,
    private val registry: CapabilityRegistry,
    private val gate: PermissionGate,
    private val permissions: PermissionManager,
    private val resolver: CallerResolver,
    private val extensions: List<ApiExtension> = emptyList(),
) {
    private var engine: ApplicationEngine? = null
    @Volatile var port: Int = 0
        private set

    fun start(): Int {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { routes() }.start(wait = false)
        engine = server
        port = kotlinx.coroutines.runBlocking { server.resolvedConnectors().first().port }
        return port
    }

    fun stop() { engine?.stop(500, 2_000); engine = null }

    private fun Application.routes() {
        routing {
            post("/v1/operations") {
                val caller = authenticate(call) ?: return@post
                val body = readJson(call) ?: return@post
                val request = parseCall(body) ?: return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.INVALID_ARGUMENT, "invalid_request")
                call.json(pipeline.call(caller, request).toJson())
            }
            get("/v1/operations/{requestId}") {
                val caller = authenticate(call) ?: return@get
                val found = pipeline.find(caller, call.parameters["requestId"].orEmpty())
                    ?: return@get call.error(HttpStatusCode.NotFound, ProgramErrorCode.RESOURCE_MISSING, "unknown_request")
                call.json(found.toJson())
            }
            get("/v1/capabilities") {
                val caller = authenticate(call) ?: return@get
                call.json(buildJsonObject {
                    put("capabilities", JsonArray(registry.descriptors.map { descriptor ->
                        val grant = gate.snapshot.grant(caller.programId, descriptor.id)
                        buildJsonObject {
                            put("id", descriptor.id); put("kind", descriptor.kind.name.lowercase())
                            put("inputSchema", descriptor.inputSchema); put("outputSchema", descriptor.outputSchema)
                            put("scope", descriptor.scope.name.lowercase())
                            put("sideEffects", JsonArray(descriptor.sideEffects.map { JsonPrimitive(it.name.lowercase()) }))
                            put("surfaces", JsonArray(descriptor.surfaces.map { JsonPrimitive(it.name.lowercase()) }))
                            put("available", registry[descriptor.id]?.availability() is Availability.Available)
                            put("permission", grant?.let { gate.state(it).wire } ?: "not_requested")
                        }
                    }))
                })
            }
            get("/v1/permissions") {
                val caller = authenticate(call) ?: return@get
                call.json(buildJsonObject {
                    put("permissions", JsonArray(gate.snapshot.grants(caller.programId).sortedBy { it.capability }.map { grant ->
                        buildJsonObject {
                            put("capability", grant.capability); put("state", gate.state(grant).wire)
                            put("required", grant.required); put("scope", grant.granted.toJson())
                            grant.expiresAt?.let { put("expiresAt", it) }
                        }
                    }))
                })
            }
            post("/v1/permissions/requests") {
                val caller = authenticate(call) ?: return@post
                val body = readJson(call) ?: return@post
                val capability = (body["capability"] as? JsonPrimitive)?.contentOrNull
                val handler = capability?.let { registry[it] }
                    ?: return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.UNSUPPORTED, "unknown_capability")
                val (scope, problems) = Scope.parse(handler.descriptor.scope, body["scope"] as? JsonObject)
                if (scope == null || body.keys - setOf("capability", "scope") != emptySet<String>())
                    return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.INVALID_ARGUMENT, problems.firstOrNull() ?: "unknown_field")
                val id = try { permissions.request(caller.programId, capability, scope) }
                    catch (error: IllegalArgumentException) { return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.SCOPE_DENIED, error.message ?: "not_declared") }
                call.json(buildJsonObject { put("requestId", id); put("state", "pending") })
            }
            extensions.forEach { it.install(this) { call -> authenticate(call) } }
        }
    }

    /** Returns the caller, or answers the request with a refusal and returns null. */
    private suspend fun authenticate(call: ApplicationCall): CallContext? {
        val headers = call.request.headers
        if (headers.contains(HttpHeaders.Origin)) { call.error(HttpStatusCode.Forbidden, ProgramErrorCode.PERMISSION_DENIED, "browser_origin"); return null }
        val host = headers[HttpHeaders.Host].orEmpty()
        if (host != "127.0.0.1:$port" && host != "localhost:$port") { call.error(HttpStatusCode.Forbidden, ProgramErrorCode.PERMISSION_DENIED, "host_not_loopback"); return null }
        val credential = headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        val caller = credential?.let(resolver::resolve)
        if (caller == null) { call.error(HttpStatusCode.Unauthorized, ProgramErrorCode.PERMISSION_DENIED, "invalid_credential"); return null }
        return caller
    }

    private suspend fun readJson(call: ApplicationCall): JsonObject? {
        val length = call.request.contentLength()
        if (length != null && length > MAX_BODY) { call.error(HttpStatusCode.PayloadTooLarge, ProgramErrorCode.INVALID_ARGUMENT, "body_too_large"); return null }
        val bytes = call.receiveChannel().readRemaining(MAX_BODY + 1).readBytes()
        if (bytes.size > MAX_BODY) { call.error(HttpStatusCode.PayloadTooLarge, ProgramErrorCode.INVALID_ARGUMENT, "body_too_large"); return null }
        return runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
            ?: run { call.error(HttpStatusCode.BadRequest, ProgramErrorCode.INVALID_ARGUMENT, "body_must_be_a_json_object"); null }
    }

    private fun parseCall(body: JsonObject): CallRequest? {
        if (body.keys - setOf("schema", "requestId", "capability", "args", "deadlineMs") != emptySet<String>()) return null
        if ((body["schema"] as? JsonPrimitive)?.contentOrNull != API_SCHEMA) return null
        val requestId = (body["requestId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val capability = (body["capability"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val args = body["args"]?.let { it as? JsonObject ?: return null } ?: JsonObject(emptyMap())
        val deadline = body["deadlineMs"]?.let { (it as? JsonPrimitive)?.longOrNull?.takeIf { d -> d > 0 } ?: return null }
        return CallRequest(requestId, capability, args, deadline)
    }

    companion object {
        const val API_SCHEMA = "mobby.program-api/1"
        const val MAX_BODY = 1L * 1024 * 1024
    }
}

internal suspend fun ApplicationCall.json(body: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body.toString(), ContentType.Application.Json, status)

internal suspend fun ApplicationCall.error(status: HttpStatusCode, code: ProgramErrorCode, reason: String) =
    json(buildJsonObject { put("error", buildJsonObject { put("code", code.name); put("reason", reason) }) }, status)
