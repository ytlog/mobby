package com.github.ytlog.mobby.android.runtime.api.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val DeviceJson = Json { encodeDefaults = true }

@Serializable enum class DeviceStatus(val terminal: Boolean = false) {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("awaiting_system") AWAITING_SYSTEM,
    @SerialName("awaiting_user") AWAITING_USER,
    @SerialName("cancelling") CANCELLING,
    @SerialName("succeeded") SUCCEEDED(true),
    @SerialName("partial") PARTIAL(true),
    @SerialName("failed") FAILED(true),
    @SerialName("cancelled") CANCELLED(true),
    @SerialName("unconfirmed") UNCONFIRMED(true),
    @SerialName("interrupted") INTERRUPTED(true),
}
@Serializable enum class EffectState {
    @SerialName("none") NONE, @SerialName("submitted") SUBMITTED,
    @SerialName("confirmed") CONFIRMED, @SerialName("partial") PARTIAL, @SerialName("unknown") UNKNOWN,
}
@Serializable enum class DeviceButton {
    @SerialName("stop_run") STOP_RUN, @SerialName("open_conversation") OPEN_CONVERSATION,
    @SerialName("open_resource") OPEN_RESOURCE, @SerialName("respond") RESPOND,
    @SerialName("retry") RETRY, @SerialName("inspect_effect") INSPECT_EFFECT,
}
@Serializable enum class DeviceErrorCode {
    INVALID_ARGUMENT, UNAUTHORIZED, UNSUPPORTED_CAPABILITY, INCOMPATIBLE_VERSION, REQUEST_CONFLICT,
    STALE_INTERACTION, NOT_FOUND, PERMISSION_REVOKED, RESOURCE_MISSING, UNAVAILABLE, TIMEOUT,
    CANCELLED, INTERRUPTED, RESULT_UNCONFIRMED, STORAGE_FULL, PROTOCOL_ERROR,
}
@Serializable data class DeviceError(
    val code: DeviceErrorCode, val message: String = "", val retryable: Boolean = false,
    val effectState: EffectState = EffectState.NONE,
)
class DeviceFailure(val error: DeviceError) : IllegalStateException(error.message.ifBlank { error.code.name })
fun deviceFailure(code: DeviceErrorCode, message: String = "", effect: EffectState = EffectState.NONE): Nothing =
    throw DeviceFailure(DeviceError(code, message, effectState = effect))

@Serializable data class DeviceSubject(val label: String = "", val detail: String = "")
@Serializable data class DeviceProgress(val completed: Long, val total: Long? = null, val unit: String) {
    init { require(completed >= 0 && (total == null || total >= completed)); require(unit.length <= 80) }
}
@Serializable data class DeviceAttention(
    val reasonCode: String, val interactionId: String, val allowedResponses: List<String>, val deadlineEpochMillis: Long? = null,
)
/** The kind is open for forward-compatible display; only the registered executor produces its data. */
@Serializable data class DeviceResult(
    val kind: String, val effectState: EffectState, val data: JsonObject, val resourceRefs: List<String> = emptyList(),
)
@Serializable data class DeviceOperation(
    val operationId: String, val requestId: String, val revision: Long,
    val plugin: String, val action: String, val displayType: String,
    val status: DeviceStatus, val phaseCode: String,
    val subject: DeviceSubject, val input: JsonObject,
    val progress: DeviceProgress? = null, val result: DeviceResult? = null, val error: DeviceError? = null,
    val availableActions: List<DeviceButton> = emptyList(), val requiresAttention: DeviceAttention? = null,
    val parentOperationId: String? = null,
)
/** Journal metadata is not part of the device response or the card's editable input. */
@Serializable data class DeviceRecord(val operation: DeviceOperation, val fingerprint: String, val order: Long)
@Serializable data class DeviceRequest(
    val protocolVersion: Int, val requestId: String, val plugin: String, val action: String,
    val args: JsonObject, val parentOperationId: String? = null,
)
@Serializable data class DeviceResponse(
    val protocolVersion: Int = 1, val requestId: String, val accepted: Boolean,
    val operationId: String? = null, val status: DeviceStatus? = null,
    val result: DeviceResult? = null, val error: DeviceError? = null,
) {
    companion object {
        fun from(operation: DeviceOperation) = DeviceResponse(requestId = operation.requestId, accepted = true,
            operationId = operation.operationId, status = operation.status, result = operation.result, error = operation.error)
    }
}

data class DeviceAdmission(
    val requestId: String, val fingerprint: String, val plugin: String, val action: String,
    val displayType: String, val subject: DeviceSubject, val input: JsonObject,
)
data class AdmittedDevice(val operation: DeviceOperation, val dispatch: Boolean)
/** App-internal facts only. Implementations persist before returning; no UI or Android dependency. */
interface DeviceOperationPort {
    suspend fun admit(request: DeviceAdmission): AdmittedDevice
    suspend fun update(operation: DeviceOperation): DeviceOperation
    suspend fun find(requestId: String): DeviceOperation?
}

object DeviceOperationRules {
    fun validate(operation: DeviceOperation) {
        require(operation.operationId.matches(ID) && operation.requestId.matches(ID) && operation.revision >= 1)
        require(operation.plugin.matches(ID) && operation.action.matches(ID) && operation.phaseCode.matches(ID))
        require(operation.input.size <= 32 && operation.subject.label.length <= 1000 && operation.subject.detail.length <= 1000)
        require(operation.result?.resourceRefs.orEmpty().size <= 100)
        require(operation.error?.message.orEmpty().length <= 1000)
        if (operation.status in setOf(DeviceStatus.SUCCEEDED, DeviceStatus.PARTIAL)) require(operation.result != null)
        if (operation.status == DeviceStatus.SUCCEEDED) require(operation.result?.effectState in setOf(EffectState.NONE, EffectState.CONFIRMED))
        if (operation.status == DeviceStatus.PARTIAL) require(operation.result?.effectState == EffectState.PARTIAL)
        if (operation.status == DeviceStatus.FAILED) require(operation.error != null)
        if (operation.status in setOf(DeviceStatus.AWAITING_USER, DeviceStatus.AWAITING_SYSTEM)) require(operation.requiresAttention != null)
        if (operation.status.terminal) require(DeviceButton.STOP_RUN !in operation.availableActions && DeviceButton.RESPOND !in operation.availableActions)
        require(DeviceJson.encodeToString(DeviceOperation.serializer(), operation).toByteArray().size <= 120 * 1024)
    }
    fun advances(previous: DeviceOperation, next: DeviceOperation): Boolean {
        if (next.operationId != previous.operationId || next.revision <= previous.revision) return false
        require(next.requestId == previous.requestId && next.plugin == previous.plugin && next.action == previous.action &&
            next.displayType == previous.displayType && next.subject == previous.subject && next.input == previous.input &&
            next.parentOperationId == previous.parentOperationId)
        if (previous.status.terminal) return false
        if (previous.status == DeviceStatus.CANCELLING && next.status !in setOf(DeviceStatus.CANCELLED, DeviceStatus.UNCONFIRMED, DeviceStatus.INTERRUPTED)) return false
        validate(next)
        return true
    }
    fun stopped(operation: DeviceOperation, interrupted: Boolean): DeviceOperation {
        if (operation.status.terminal) return operation
        val effect = operation.result?.effectState ?: if (operation.status == DeviceStatus.QUEUED) EffectState.NONE else EffectState.UNKNOWN
        val status = if (interrupted) DeviceStatus.INTERRUPTED else if (effect == EffectState.UNKNOWN) DeviceStatus.UNCONFIRMED else DeviceStatus.CANCELLED
        return operation.copy(revision = operation.revision + 1, status = status, phaseCode = status.name.lowercase(),
            error = DeviceError(if (interrupted) DeviceErrorCode.INTERRUPTED else DeviceErrorCode.CANCELLED, effectState = effect),
            requiresAttention = null, availableActions = listOf(DeviceButton.OPEN_CONVERSATION))
    }
    val ID = Regex("[A-Za-z0-9._-]{1,128}")
}

@Serializable data class DeviceInteractionResponse(
    val commandId: String, val runId: String, val operationId: String, val interactionId: String,
    val expectedRevision: Long, val response: String,
)
