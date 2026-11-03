package com.github.ytlog.mobby.android.runtime.engine

import kotlinx.serialization.json.*

/** Per-execution token set also retains rotated tokens until that process exits. */
class NativeCredentialRedactor(document: String? = null) {
    private val secrets = mutableSetOf<String>()
    private val names = setOf("access_token", "refresh_token", "id_token", "access", "refresh", "accessToken", "refreshToken")
    init { document?.let(::include) }
    @Synchronized fun include(document: String) {
        fun visit(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (name, item) ->
                    if (name in names && item is JsonPrimitive && item.isString && item.content.length >= 8) {
                        secrets += item.content
                        secrets += item.toString().removeSurrounding("\"")
                    } else visit(item)
                }
                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(Json.parseToJsonElement(document))
    }
    @Synchronized fun redact(text: String): String = secrets.fold(text) { result, secret -> result.replace(secret, "[redacted]") }
}
