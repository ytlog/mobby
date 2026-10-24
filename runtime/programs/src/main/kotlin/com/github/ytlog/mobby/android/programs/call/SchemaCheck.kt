package com.github.ytlog.mobby.android.programs.call

import kotlinx.serialization.json.*

/**
 * Validates the JSON Schema subset the registry uses: type, properties, required,
 * additionalProperties, items, maxLength, minimum, maximum, maxItems and enum.
 * Unsupported keywords are rejected at registration rather than silently ignored.
 */
object SchemaCheck {
    private val SUPPORTED = setOf("type", "properties", "required", "additionalProperties", "items", "maxLength",
        "minimum", "maximum", "maxItems", "enum", "description")

    fun supported(schema: JsonObject): Boolean = schema.keys.all { it in SUPPORTED } &&
        (schema["properties"] as? JsonObject)?.values?.all { it is JsonObject && supported(it) } != false &&
        (schema["items"] as? JsonObject)?.let(::supported) != false

    fun problems(schema: JsonObject, value: JsonElement, path: String = "$"): List<String> {
        val result = mutableListOf<String>()
        val types = when (val type = schema["type"]) {
            null -> null
            is JsonArray -> type.map { it.jsonPrimitive.content }.toSet()
            else -> setOf(type.jsonPrimitive.content)
        }
        if (types != null && types.none { matches(it, value) }) return listOf("$path must be ${types.joinToString(" or ")}")
        schema["enum"]?.jsonArray?.let { if (value !in it) result += "$path must be one of $it" }
        when (value) {
            is JsonObject -> {
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it !in value }?.forEach { result += "$path.$it is required" }
                if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false)
                    (value.keys - properties.keys).forEach { result += "$path.$it is not allowed" }
                value.forEach { (key, child) -> (properties[key] as? JsonObject)?.let { result += problems(it, child, "$path.$key") } }
            }
            is JsonArray -> {
                schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { if (value.size > it) result += "$path has more than $it items" }
                (schema["items"] as? JsonObject)?.let { items -> value.forEachIndexed { i, child -> result += problems(items, child, "$path[$i]") } }
            }
            is JsonPrimitive -> {
                if (value.isString) schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { if (value.content.length > it) result += "$path is longer than $it" }
                value.doubleOrNull?.takeIf { !value.isString }?.let { number ->
                    schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (number < it) result += "$path is below $it" }
                    schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if (number > it) result += "$path is above $it" }
                }
            }
            JsonNull -> Unit
        }
        return result
    }

    private fun matches(type: String, value: JsonElement) = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "null" -> value is JsonNull
        else -> false
    }
}
