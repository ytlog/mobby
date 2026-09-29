package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.serialization.json.*

internal fun fields(vararg values: Pair<String, Any?>): JsonObject = JsonObject(values.associate { it.first to jsonValue(it.second) })
private fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map(::jsonValue))
    else -> error("Unsupported device result field")
}
internal fun deviceResult(kind: String, data: JsonObject, effect: EffectState = EffectState.NONE, refs: List<String> = emptyList()) =
    DeviceResult(kind, effect, data, refs)
internal fun deviceText(text: String, effect: EffectState = EffectState.NONE) = deviceResult("text", fields("text" to text), effect)
internal fun deviceList(kind: String, rows: List<JsonObject>, scope: String) = deviceResult(kind, fields("items" to rows, "nextCursor" to null, "scope" to scope))
internal fun recordChange(action: String, id: String, values: JsonObject = fields()) = deviceResult("record_change",
    fields("action" to action, "recordId" to id, "after" to values), EffectState.CONFIRMED)

internal fun interface DeviceResourceRegistrar {
    fun register(file: java.io.File, mediaType: String): String
}
