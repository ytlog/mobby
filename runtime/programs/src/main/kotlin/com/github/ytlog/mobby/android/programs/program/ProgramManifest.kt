package com.github.ytlog.mobby.android.programs.program

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val PROGRAM_SCHEMA = "mobby.program/1"

/** `mobby.program/1`. Unknown fields are rejected, never ignored. */
@Serializable data class ProgramManifest(
    val schema: String,
    val id: String,
    val version: String,
    val name: String,
    val background: String? = null,
    val ui: UiEntry? = null,
    val permissions: List<PermissionRequest> = emptyList(),
    val config: List<ConfigField> = emptyList(),
    val subscriptions: List<SubscriptionDeclaration> = emptyList(),
)

@Serializable data class UiEntry(val entry: String)

@Serializable data class PermissionRequest(val capability: String, val required: Boolean, val scope: JsonObject? = null)

@Serializable data class ConfigField(
    val key: String,
    val type: String,
    val required: Boolean,
    val label: String,
    val default: JsonElement? = null,
    val options: List<String>? = null,
    val domains: List<String>? = null,
)

@Serializable data class SubscriptionDeclaration(val id: String, val event: String, val filter: JsonObject = JsonObject(emptyMap()), val handler: String)

internal val ManifestJson = Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = false; encodeDefaults = false }

enum class ConfigType(val wire: String) {
    STRING("string"), NUMBER("number"), BOOLEAN("boolean"), ENUM("enum"), PACKAGE("package"), SECRET("secret");
    companion object { fun of(wire: String) = values().firstOrNull { it.wire == wire } }
}
