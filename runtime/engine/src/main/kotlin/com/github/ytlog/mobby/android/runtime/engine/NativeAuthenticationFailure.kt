package com.github.ytlog.mobby.android.runtime.engine

import kotlinx.serialization.json.*

/** Only explicit authentication failures from protocol error fields expire a login. */
object NativeAuthenticationFailure {
    /** An intermediate retry must not expire an account that later succeeds. */
    fun terminalState(line: String): Boolean? {
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        return when (value["type"]?.jsonPrimitive?.contentOrNull) {
            "turn.completed" -> false
            "step_finish" -> if ((value["part"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull in setOf("stop", "length")) false else null
            "turn.failed", "mobby.pi.error", "error", "result" -> matches(line)
            "message_end" -> if ((value["message"] as? JsonObject)?.get("stopReason") != null) matches(line) else null
            else -> null
        }
    }
    fun matches(line: String): Boolean {
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return false
        val params = value["params"] as? JsonObject
        val message = value["message"] as? JsonObject
        val error = value["error"] ?: params?.get("error") ?: (params?.get("turn") as? JsonObject)?.get("error")
            ?: message?.takeIf { it["stopReason"]?.jsonPrimitive?.contentOrNull == "error" }?.get("errorMessage")
            ?: value.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "result" && it["is_error"]?.jsonPrimitive?.booleanOrNull == true }?.get("errors")
            ?: return false
        if (error is JsonNull) return false
        val text = error.toString()
        if (Regex("\"(?:status|statusCode|status_code|code)\"\\s*:\\s*(?:401|\"401\")").containsMatchIn(text)) return true
        return Regex("(?i)(?:HTTP(?:/[^ ]+)?\\s+(?:status(?: code)?[: ]+)?401\\b|\\b401\\s+Unauthorized|invalid_grant|invalid_token|refresh_token_reused|refresh_token_expired|authentication_error|OAuth token (?:has )?expired)").containsMatchIn(text)
    }
}
