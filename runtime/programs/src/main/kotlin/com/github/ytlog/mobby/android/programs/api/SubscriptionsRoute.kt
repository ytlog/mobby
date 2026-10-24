package com.github.ytlog.mobby.android.programs.api

import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.capability.CapabilityKind
import com.github.ytlog.mobby.android.programs.capability.CapabilityRegistry
import com.github.ytlog.mobby.android.programs.capability.EventCapability
import com.github.ytlog.mobby.android.programs.capability.ProgramErrorCode
import com.github.ytlog.mobby.android.programs.permission.PermissionGate
import com.github.ytlog.mobby.android.programs.program.ProgramPackageReader
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import com.github.ytlog.mobby.android.programs.store.SubscriptionRow
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * `/v1/subscriptions`: a program reads its own subscriptions and may add or remove runtime ones,
 * never beyond the event capabilities it was granted. [changed] rebuilds routing.
 */
class SubscriptionsRoute(
    private val dao: ProgramsDao,
    private val registry: CapabilityRegistry,
    private val gate: PermissionGate,
    private val changed: suspend (programId: String) -> Unit,
) : ApiExtension {
    override fun install(routing: Routing, auth: suspend (ApplicationCall) -> CallContext?) {
        routing.get("/v1/subscriptions") {
            val caller = auth(call) ?: return@get
            call.json(buildJsonObject {
                put("subscriptions", JsonArray(dao.subscriptions(caller.programId).map { row ->
                    buildJsonObject {
                        put("id", row.subscriptionId.substringAfter('/')); put("event", row.eventType)
                        put("filter", Json.parseToJsonElement(row.filterJson)); put("handler", row.handler)
                        put("source", row.source); put("paused", row.paused); put("triggerCount", row.triggerCount)
                    }
                }))
            })
        }
        routing.post("/v1/subscriptions") {
            val caller = auth(call) ?: return@post
            if (caller.dryRun != null) return@post call.error(HttpStatusCode.Forbidden, ProgramErrorCode.PERMISSION_DENIED, "not_allowed_in_dry_run")
            val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                ?: return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.INVALID_ARGUMENT, "body_must_be_a_json_object")
            if (body.keys - setOf("id", "event", "filter", "handler") != emptySet<String>())
                return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.INVALID_ARGUMENT, "unknown_field")
            val id = (body["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(ProgramPackageReader.LOCAL_ID) }
            val handler = (body["handler"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(ProgramPackageReader.LOCAL_ID) }
            val event = (body["event"] as? JsonPrimitive)?.contentOrNull
            val filter = body["filter"] as? JsonObject ?: JsonObject(emptyMap())
            if (id == null || handler == null || event == null) return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.INVALID_ARGUMENT, "id_event_and_handler_required")
            val capability = registry[event]
            if (capability == null || capability.descriptor.kind != CapabilityKind.EVENT || capability !is EventCapability)
                return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.UNSUPPORTED, "not_an_event_capability")
            val grant = gate.snapshot.grant(caller.programId, event)
                ?: return@post call.error(HttpStatusCode.Forbidden, ProgramErrorCode.PERMISSION_DENIED, "not_requested")
            capability.validateFilter(filter, grant.granted).firstOrNull()?.let {
                return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.SCOPE_DENIED, it)
            }
            val fullId = "${caller.programId}/$id"
            val existing = dao.subscriptions(caller.programId).firstOrNull { it.subscriptionId == fullId }
            if (existing?.source == "manifest") return@post call.error(HttpStatusCode.Conflict, ProgramErrorCode.REQUEST_CONFLICT, "declared_in_manifest")
            if (dao.subscriptions(caller.programId).count { it.source == "runtime" } >= MAX_RUNTIME && existing == null)
                return@post call.error(HttpStatusCode.Forbidden, ProgramErrorCode.QUOTA_EXCEEDED, "too_many_subscriptions")
            dao.upsertSubscription(SubscriptionRow(fullId, caller.programId, caller.version, event, filter.toString(), handler, "runtime",
                existing?.paused ?: false, existing?.lastTriggeredAt, existing?.triggerCount ?: 0))
            changed(caller.programId)
            call.json(buildJsonObject { put("id", id) })
        }
        routing.delete("/v1/subscriptions/{id}") {
            val caller = auth(call) ?: return@delete
            val fullId = "${caller.programId}/${call.parameters["id"]}"
            val row = dao.subscriptions(caller.programId).firstOrNull { it.subscriptionId == fullId }
                ?: return@delete call.error(HttpStatusCode.NotFound, ProgramErrorCode.RESOURCE_MISSING, "unknown_subscription")
            if (row.source != "runtime") return@delete call.error(HttpStatusCode.Forbidden, ProgramErrorCode.PERMISSION_DENIED, "declared_in_manifest")
            dao.deleteSubscription(fullId)
            changed(caller.programId)
            call.json(buildJsonObject { put("deleted", true) })
        }
    }

    private companion object { const val MAX_RUNTIME = 32 }
}
