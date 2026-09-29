package com.github.ytlog.mobby.android.device.appfunctions

import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.*
import kotlinx.serialization.json.*
import java.util.Base64

/** Converts only the types whose semantics can be preserved by JSON. Unsupported types fail explicitly. */
internal object AppFunctionJson {
    fun unsupportedType(metadata: AppFunctionMetadata): String? =
        metadata.parameters.firstNotNullOfOrNull { parameter ->
            if (parameter.dataType is AppFunctionUnitTypeMetadata) "unit parameter"
            else unsupported(parameter.dataType, metadata.components, 0)
        } ?: if (metadata.response.valueType is AppFunctionUnitTypeMetadata) null
            else unsupported(metadata.response.valueType, metadata.components, 0)

    private fun unsupported(rawType: AppFunctionDataTypeMetadata, components: AppFunctionComponentsMetadata, depth: Int): String? {
        if (depth > 8) return "nested type exceeds depth 8"
        val type = if (rawType is AppFunctionReferenceTypeMetadata)
            components.dataTypes[rawType.referenceDataType] ?: return "unknown reference"
        else rawType
        return when (type) {
            is AppFunctionBooleanTypeMetadata, is AppFunctionIntTypeMetadata, is AppFunctionLongTypeMetadata,
            is AppFunctionFloatTypeMetadata, is AppFunctionDoubleTypeMetadata, is AppFunctionStringTypeMetadata,
            is AppFunctionBytesTypeMetadata -> null
            is AppFunctionUnitTypeMetadata -> "unit field"
            is AppFunctionObjectTypeMetadata -> type.properties.values.firstNotNullOfOrNull { unsupported(it, components, depth + 1) }
            is AppFunctionArrayTypeMetadata -> {
                val item = resolve(type.itemType, components, depth + 1)
                if (item is AppFunctionBytesTypeMetadata || item is AppFunctionArrayTypeMetadata || item is AppFunctionUnitTypeMetadata)
                    item.javaClass.simpleName else unsupported(item, components, depth + 1)
            }
            else -> type.javaClass.simpleName
        }
    }
    fun parameters(metadata: AppFunctionMetadata, input: JsonObject): AppFunctionData {
        require(input.toString().toByteArray().size <= 32_768) { "Arguments exceed 32 KiB" }
        val expected = metadata.parameters.associateBy { it.name }
        require(input.keys.all { it in expected }) { "Unknown function parameter" }
        val builder = AppFunctionData.Builder(metadata.parameters, metadata.components)
        for (parameter in metadata.parameters) {
            val value = input[parameter.name]
            if (value == null) {
                require(!parameter.isRequired) { "Missing parameter: ${parameter.name}" }
                continue
            }
            require(value != JsonNull) { "Null parameter is not supported: ${parameter.name}" }
            put(builder, parameter.name, parameter.dataType, value, metadata.components, 0)
        }
        return builder.build()
    }

    fun result(metadata: AppFunctionMetadata, response: ExecuteAppFunctionResponse.Success): JsonElement {
        val type = metadata.response.valueType
        if (type is AppFunctionUnitTypeMetadata) return JsonNull
        val data = response.returnValue
        val key = ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE
        if (!data.containsKey(key) && type.isNullable) return JsonNull
        require(data.containsKey(key)) { "App function omitted its return value" }
        return read(data, key, type, metadata.components, 0)
    }

    private fun resolve(type: AppFunctionDataTypeMetadata, components: AppFunctionComponentsMetadata, depth: Int): AppFunctionDataTypeMetadata {
        require(depth <= 8) { "Function value is too deeply nested" }
        return if (type is AppFunctionReferenceTypeMetadata) {
            resolve(components.dataTypes[type.referenceDataType] ?: error("Unknown function data type"), components, depth + 1)
        } else type
    }

    private fun put(builder: AppFunctionData.Builder, key: String, rawType: AppFunctionDataTypeMetadata,
                    value: JsonElement, components: AppFunctionComponentsMetadata, depth: Int) {
        val type = resolve(rawType, components, depth)
        when (type) {
            is AppFunctionBooleanTypeMetadata -> builder.setBoolean(key, nonString(value).boolean)
            is AppFunctionIntTypeMetadata -> builder.setInt(key, nonString(value).int)
            is AppFunctionLongTypeMetadata -> builder.setLong(key, nonString(value).long)
            is AppFunctionFloatTypeMetadata -> builder.setFloat(key, nonString(value).float)
            is AppFunctionDoubleTypeMetadata -> builder.setDouble(key, nonString(value).double)
            is AppFunctionStringTypeMetadata -> builder.setString(key, value.jsonPrimitive.content.also { require(value.jsonPrimitive.isString) })
            is AppFunctionBytesTypeMetadata -> {
                require(value.jsonPrimitive.isString) { "Expected Base64 string for $key" }
                builder.setByteArray(key, Base64.getDecoder().decode(value.jsonPrimitive.content))
            }
            is AppFunctionObjectTypeMetadata -> {
                val obj = value as? JsonObject ?: error("Expected object for $key")
                val nested = AppFunctionData.Builder(type, components)
                require(obj.keys.all { it in type.properties }) { "Unknown field in $key" }
                require(type.required.all { it in obj }) { "Missing field in $key" }
                for ((name, child) in obj) {
                    require(child != JsonNull) { "Null field is not supported: $name" }
                    put(nested, name, type.properties.getValue(name), child, components, depth + 1)
                }
                builder.setAppFunctionData(key, nested.build())
            }
            is AppFunctionArrayTypeMetadata -> putArray(builder, key, resolve(type.itemType, components, depth + 1), value as? JsonArray
                ?: error("Expected array for $key"), components, depth + 1)
            else -> error("Unsupported App Function parameter type: ${type.javaClass.simpleName}")
        }
    }

    private fun nonString(value: JsonElement): JsonPrimitive = value.jsonPrimitive.also {
        require(!it.isString) { "Expected a JSON number or boolean" }
    }

    private fun putArray(builder: AppFunctionData.Builder, key: String, type: AppFunctionDataTypeMetadata,
                         values: JsonArray, components: AppFunctionComponentsMetadata, depth: Int) {
        require(values.size <= 100 && values.none { it == JsonNull }) { "Unsupported array in $key" }
        when (type) {
            is AppFunctionBooleanTypeMetadata -> builder.setBooleanArray(key, values.map { nonString(it).boolean }.toBooleanArray())
            is AppFunctionIntTypeMetadata -> builder.setIntArray(key, values.map { nonString(it).int }.toIntArray())
            is AppFunctionLongTypeMetadata -> builder.setLongArray(key, values.map { nonString(it).long }.toLongArray())
            is AppFunctionFloatTypeMetadata -> builder.setFloatArray(key, values.map { nonString(it).float }.toFloatArray())
            is AppFunctionDoubleTypeMetadata -> builder.setDoubleArray(key, values.map { nonString(it).double }.toDoubleArray())
            is AppFunctionStringTypeMetadata -> builder.setStringList(key, values.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content })
            is AppFunctionObjectTypeMetadata -> builder.setAppFunctionDataList(key, values.map { item ->
                val nested = AppFunctionData.Builder(type, components)
                val obj = item as? JsonObject ?: error("Expected object in $key")
                require(obj.keys.all { it in type.properties } && type.required.all { it in obj }) { "Invalid object in $key" }
                for ((name, child) in obj) put(nested, name, type.properties.getValue(name), child, components, depth + 1)
                nested.build()
            })
            else -> error("Unsupported App Function array type: ${type.javaClass.simpleName}")
        }
    }

    private fun read(data: AppFunctionData, key: String, rawType: AppFunctionDataTypeMetadata,
                     components: AppFunctionComponentsMetadata, depth: Int): JsonElement {
        val type = resolve(rawType, components, depth)
        if (!data.containsKey(key)) return nullableResult(type)
        return when (type) {
            is AppFunctionBooleanTypeMetadata -> data.getBooleanOrNull(key)?.let(::JsonPrimitive) ?: nullableResult(type)
            is AppFunctionIntTypeMetadata -> data.getIntOrNull(key)?.let(::JsonPrimitive) ?: nullableResult(type)
            is AppFunctionLongTypeMetadata -> data.getLongOrNull(key)?.let(::JsonPrimitive) ?: nullableResult(type)
            is AppFunctionFloatTypeMetadata -> data.getFloatOrNull(key)?.let(::JsonPrimitive) ?: nullableResult(type)
            is AppFunctionDoubleTypeMetadata -> data.getDoubleOrNull(key)?.let(::JsonPrimitive) ?: nullableResult(type)
            is AppFunctionStringTypeMetadata -> data.getStringOrNull(key)?.let(::JsonPrimitive) ?: nullableResult(type)
            is AppFunctionBytesTypeMetadata -> data.getByteArrayOrNull(key)?.let { JsonPrimitive(Base64.getEncoder().encodeToString(it)) } ?: nullableResult(type)
            is AppFunctionObjectTypeMetadata -> {
                val child = data.getAppFunctionData(key)
                if (child == null) nullableResult(type) else
                    JsonObject(type.properties.filterKeys { child.containsKey(it) }.mapValues { (name, fieldType) -> read(child, name, fieldType, components, depth + 1) })
            }
            is AppFunctionArrayTypeMetadata -> readArray(data, key, resolve(type.itemType, components, depth + 1), components, depth + 1)
            else -> error("Unsupported App Function result type: ${type.javaClass.simpleName}")
        }
    }

    private fun nullableResult(type: AppFunctionDataTypeMetadata): JsonElement {
        require(type.isNullable) { "Target returned null for a required value" }
        return JsonNull
    }

    private fun readArray(data: AppFunctionData, key: String, type: AppFunctionDataTypeMetadata,
                          components: AppFunctionComponentsMetadata, depth: Int): JsonArray = JsonArray(when (type) {
        is AppFunctionBooleanTypeMetadata -> requireNotNull(data.getBooleanArray(key)).map(::JsonPrimitive)
        is AppFunctionIntTypeMetadata -> requireNotNull(data.getIntArray(key)).map(::JsonPrimitive)
        is AppFunctionLongTypeMetadata -> requireNotNull(data.getLongArray(key)).map(::JsonPrimitive)
        is AppFunctionFloatTypeMetadata -> requireNotNull(data.getFloatArray(key)).map(::JsonPrimitive)
        is AppFunctionDoubleTypeMetadata -> requireNotNull(data.getDoubleArray(key)).map(::JsonPrimitive)
        is AppFunctionStringTypeMetadata -> requireNotNull(data.getStringList(key)).map(::JsonPrimitive)
        is AppFunctionObjectTypeMetadata -> requireNotNull(data.getAppFunctionDataList(key)).map { child ->
            JsonObject(type.properties.filterKeys { child.containsKey(it) }.mapValues { (name, fieldType) -> read(child, name, fieldType, components, depth + 1) })
        }
        else -> error("Unsupported App Function result array type: ${type.javaClass.simpleName}")
    })
}
