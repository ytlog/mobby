package com.github.ytlog.mobby.android.programs.api

import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.capability.ProgramErrorCode
import com.github.ytlog.mobby.android.programs.run.RunMessage
import com.github.ytlog.mobby.android.programs.run.RunSupervisor
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `GET /v1/events`: server-sent events for one background run; connecting marks the run as running. */
class EventStreamRoute(private val runs: RunSupervisor) : ApiExtension {
    override fun install(routing: Routing, auth: suspend (ApplicationCall) -> CallContext?) {
        routing.get("/v1/events") {
            val caller = auth(call) ?: return@get
            val runId = caller.runId ?: return@get call.error(HttpStatusCode.BadRequest, ProgramErrorCode.UNSUPPORTED, "events_need_a_background_run")
            val messages = runs.attach(runId) ?: return@get call.error(HttpStatusCode.Conflict, ProgramErrorCode.CONDITION_UNMET, "stream_already_attached")
            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondTextWriter(ContentType.Text.EventStream) {
                messages.collect { message ->
                    val (name, data) = when (message) {
                        is RunMessage.Event -> "program.event" to message.event.toJson()
                        is RunMessage.Stopping -> "run.stopping" to buildJsonObject { put("reason", message.reason) }
                    }
                    write("event: $name\ndata: $data\n\n")
                    flush()
                }
            }
        }
    }
}
