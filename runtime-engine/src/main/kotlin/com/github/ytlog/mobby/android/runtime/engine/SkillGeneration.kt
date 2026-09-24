package com.github.ytlog.mobby.android.runtime.engine

import kotlinx.serialization.json.*

/** Native CLI structured response. Markdown is constructed locally, never guessed from prose. */
object SkillGeneration {
    val schema = """{"type":"object","properties":{"kind":{"type":"string","enum":["clarification","proposal"]},"message":{"type":"string"},"name":{"type":"string"},"description":{"type":"string"},"body":{"type":"string"}},"required":["kind","message","name","description","body"],"additionalProperties":false}"""
    val instruction get() = com.github.ytlog.mobby.android.localization.AgentPrompts.skillGenerationInstruction
    data class Result(val message: String, val markdown: String?)
    fun parse(text: String): Result? = runCatching {
        require(text.toByteArray().size <= 512 * 1024)
        val obj = Json.parseToJsonElement(text).jsonObject
        require(obj.keys == setOf("kind", "message", "name", "description", "body"))
        fun field(key: String): String = obj.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        val kind = field("kind"); val message = field("message")
        val name = field("name"); val description = field("description"); val body = field("body")
        require(message.isNotBlank() && message.toByteArray().size <= 32 * 1024)
        when (kind) {
            "clarification" -> { require(name.isEmpty() && description.isEmpty() && body.isEmpty()); Result(message, null) }
            "proposal" -> {
                require(name == name.trim())
                val preview = SkillDocument.manual(name, description, body)
                require(preview.issues.isEmpty())
                Result(message, preview.markdown)
            }
            else -> error("Unknown skill output kind")
        }
    }.getOrNull()
}
