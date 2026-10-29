package com.github.ytlog.mobby.android.programs.api

import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.capability.ProgramErrorCode
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * `POST /v1/ui/messages`: a background run pushes a message to its program's open pages; nothing is queued.
 * A dry run shares its program's id, so its messages go to [simulate] instead of the installed program's pages.
 */
class UiMessagesRoute(
    private val post: (programId: String, message: JsonElement) -> Int,
    private val simulate: suspend (caller: CallContext, message: JsonElement) -> Unit,
) : ApiExtension {
    override fun install(routing: Routing, auth: suspend (ApplicationCall) -> CallContext?) {
        routing.post("/v1/ui/messages") {
            val caller = auth(call) ?: return@post
            val text = call.receiveText()
            if (text.length > MAX) return@post call.error(HttpStatusCode.PayloadTooLarge, ProgramErrorCode.INVALID_ARGUMENT, "message_too_large")
            val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (body == null || body.keys != setOf("message")) return@post call.error(HttpStatusCode.BadRequest, ProgramErrorCode.INVALID_ARGUMENT, "body_needs_message")
            val message = body.getValue("message")
            if (caller.dryRun != null) {
                simulate(caller, message)
                return@post call.json(buildJsonObject { put("delivered", 0); put("simulated", true) })
            }
            call.json(buildJsonObject { put("delivered", post(caller.programId, message)) })
        }
    }

    private companion object { const val MAX = 256 * 1024 }
}
