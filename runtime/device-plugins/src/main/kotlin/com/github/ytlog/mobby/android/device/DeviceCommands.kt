package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

fun interface DevicePerformer {
    fun perform(plugin: String, action: String, args: Map<String, String>, execution: DeviceExecution): DeviceResult
}

/** One synchronous action per request. Long actions emit live facts while the CLI awaits the result. */
object DeviceCommands {
    fun token() = UUID.randomUUID().toString()
    fun handle(line: String, token: String, allow: Map<String, Set<String>>, operations: DeviceOperationPort,
        gate: () -> Unit = {}, exportResource: ((String) -> String)? = null, performer: DevicePerformer): String = runBlocking {
        var requestId = "invalid-request"
        var operation: DeviceOperation? = null
        var writes = false
        try {
            if (line.toByteArray().size > 65_536) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT, "Request exceeds 64 KiB")
            val json = DeviceJson.parseToJsonElement(line) as? JsonObject ?: deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
            requestId = (json["requestId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(DeviceOperationRules.ID) } ?: requestId
            if ((json["token"] as? JsonPrimitive)?.contentOrNull != token) deviceFailure(DeviceErrorCode.UNAUTHORIZED)
            if ((json["protocolVersion"] as? JsonPrimitive)?.let { !it.isString && it.intOrNull == 1 } != true) deviceFailure(DeviceErrorCode.INCOMPATIBLE_VERSION)
            val request = DeviceJson.decodeFromJsonElement<DeviceRequest>(JsonObject(json - "token"))
            if (!request.requestId.matches(DeviceOperationRules.ID) || request.args.size > 32) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
            if (request.parentOperationId != null) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT, "No parent operation registered")
            if (request.plugin == "operation" && request.action in setOf("status", "resource")) {
                if (request.args.keys != if (request.action == "status") setOf("requestId") else setOf("requestId", "resourceRef")) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
                val original = (request.args["requestId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
                val found = operations.find(original) ?: deviceFailure(DeviceErrorCode.NOT_FOUND)
                if (request.action == "resource") {
                    val ref = request.args["resourceRef"]?.jsonPrimitive?.content ?: deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
                    if (ref !in found.result?.resourceRefs.orEmpty() || exportResource == null) deviceFailure(DeviceErrorCode.UNAUTHORIZED)
                    gate()
                    return@runBlocking DeviceJson.encodeToString(DeviceResponse.from(found).copy(result = deviceText(exportResource(ref))))
                }
                return@runBlocking DeviceJson.encodeToString(DeviceResponse.from(found))
            }
            if (request.action !in allow[request.plugin].orEmpty()) deviceFailure(DeviceErrorCode.UNAUTHORIZED)
            val definition = DeviceActionDefinition(request.plugin, request.action)
            val args = definition.validate(request.args)
            val admitted = operations.admit(definition.admission(request, args))
            if (!admitted.dispatch) return@runBlocking DeviceJson.encodeToString(DeviceResponse.from(admitted.operation))
            operation = admitted.operation
            gate()
            operation = operations.update(operation!!.copy(revision = operation!!.revision + 1, status = DeviceStatus.RUNNING, phaseCode = "${request.plugin}.${request.action}"))
            if (operation!!.status != DeviceStatus.RUNNING) deviceFailure(DeviceErrorCode.CANCELLED)
            gate()
            writes = definition.writes
            val execution = DeviceExecution(operation!!.operationId) { status, phase, attention ->
                val previous = checkNotNull(operation)
                operation = operations.update(previous.copy(revision = previous.revision + 1, status = status, phaseCode = phase,
                    requiresAttention = attention, availableActions = listOfNotNull(DeviceButton.STOP_RUN, DeviceButton.OPEN_CONVERSATION,
                        DeviceButton.RESPOND.takeIf { attention != null })))
            }
            val result = performer.perform(request.plugin, request.action, args, execution)
            operation = operations.find(request.requestId) ?: deviceFailure(DeviceErrorCode.NOT_FOUND)
            val sendFailed = result.kind == "message_receipt" && result.data["sent"]?.jsonPrimitive?.content == "failed"
            val status = if (sendFailed && result.effectState == EffectState.NONE) DeviceStatus.FAILED else when (result.effectState) {
                EffectState.UNKNOWN, EffectState.SUBMITTED -> DeviceStatus.UNCONFIRMED
                EffectState.PARTIAL -> DeviceStatus.PARTIAL
                else -> DeviceStatus.SUCCEEDED
            }
            operation = operations.update(operation!!.copy(revision = operation!!.revision + 1, status = status, phaseCode = status.name.lowercase(),
                requiresAttention = null, result = result, error = if (sendFailed) DeviceError(DeviceErrorCode.UNAVAILABLE, effectState = result.effectState) else null, availableActions = buildList {
                    add(DeviceButton.OPEN_CONVERSATION)
                    if (result.resourceRefs.isNotEmpty()) add(DeviceButton.OPEN_RESOURCE)
                }))
            DeviceJson.encodeToString(DeviceResponse.from(operation!!))
        } catch (failure: Exception) {
            val effect = if (writes) EffectState.UNKNOWN else EffectState.NONE
            val error = when (failure) {
                is DeviceFailure -> failure.error
                is kotlinx.coroutines.TimeoutCancellationException, is java.util.concurrent.TimeoutException -> DeviceError(DeviceErrorCode.TIMEOUT, effectState = effect)
                is java.util.concurrent.CancellationException -> DeviceError(DeviceErrorCode.CANCELLED, effectState = effect)
                is SecurityException -> DeviceError(DeviceErrorCode.PERMISSION_REVOKED, effectState = effect)
                is kotlinx.serialization.SerializationException -> DeviceError(DeviceErrorCode.INVALID_ARGUMENT, effectState = effect)
                is IllegalArgumentException -> DeviceError(DeviceErrorCode.INVALID_ARGUMENT, effectState = effect)
                is java.io.FileNotFoundException -> DeviceError(DeviceErrorCode.RESOURCE_MISSING, effectState = effect)
                else -> DeviceError(DeviceErrorCode.UNAVAILABLE, effectState = effect)
            }
            val current = operation?.let { admitted ->
                try { operations.find(admitted.requestId) ?: admitted } catch (_: Exception) { admitted }
            }
            if (current == null) DeviceJson.encodeToString(DeviceResponse(requestId = requestId, accepted = false, error = error))
            else {
                val status = when {
                    error.effectState == EffectState.UNKNOWN -> DeviceStatus.UNCONFIRMED
                    error.code == DeviceErrorCode.CANCELLED -> DeviceStatus.CANCELLED
                    else -> DeviceStatus.FAILED
                }
                try {
                    val failed = operations.update(current.copy(revision = current.revision + 1, status = status, phaseCode = status.name.lowercase(), error = error,
                        availableActions = listOf(DeviceButton.OPEN_CONVERSATION), requiresAttention = null))
                    DeviceJson.encodeToString(DeviceResponse.from(failed))
                } catch (_: Exception) {
                    // Already admitted: never suggest that a storage failure made a write safe to resend.
                    DeviceJson.encodeToString(DeviceResponse(requestId = requestId, accepted = true, operationId = current.operationId,
                        status = DeviceStatus.UNCONFIRMED, error = DeviceError(DeviceErrorCode.STORAGE_FULL, effectState = effect)))
                }
            }
        }
    }
}

class DeviceExecution internal constructor(val operationId: String,
    private val update: suspend (DeviceStatus, String, DeviceAttention?) -> Unit) {
    fun waiting(phase: String, promptId: String) = runBlocking {
        update(DeviceStatus.AWAITING_USER, phase, DeviceAttention(phase, promptId, listOf("cancel")))
    }
    fun running(phase: String) = runBlocking { update(DeviceStatus.RUNNING, phase, null) }
}
