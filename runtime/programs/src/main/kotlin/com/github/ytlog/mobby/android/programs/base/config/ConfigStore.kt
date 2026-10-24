package com.github.ytlog.mobby.android.programs.base.config

import com.github.ytlog.mobby.android.programs.program.ConfigField
import com.github.ytlog.mobby.android.programs.program.ConfigType
import com.github.ytlog.mobby.android.programs.program.ManifestJson
import com.github.ytlog.mobby.android.programs.program.ProgramManifest
import com.github.ytlog.mobby.android.programs.store.ConfigRow
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import kotlinx.serialization.json.*

private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** Values the user fills in for a program's declared config fields. Secrets live in the secret store. */
class ConfigStore(private val dao: ProgramsDao, private val clock: () -> Long) {
    suspend fun fields(programId: String): List<ConfigField> {
        val program = dao.program(programId) ?: return emptyList()
        val version = dao.version(programId, program.currentVersion) ?: return emptyList()
        return ManifestJson.decodeFromString(ProgramManifest.serializer(), version.manifestJson).config
    }

    /** Field values with defaults applied; a secret field is given as its name and whether it is set. */
    suspend fun values(programId: String): JsonObject {
        val stored = dao.config(programId).associate { it.key to Json.parseToJsonElement(it.valueJson) }
        val secrets = dao.secrets(programId).map { it.name }.toSet()
        return buildJsonObject {
            for (field in fields(programId)) {
                if (ConfigType.of(field.type) == ConfigType.SECRET) put(field.key, buildJsonObject { put("secret", field.key); put("set", field.key in secrets) })
                else (stored[field.key] ?: field.default)?.let { put(field.key, it) }
            }
        }
    }

    suspend fun set(programId: String, key: String, value: JsonElement) {
        val field = fields(programId).firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown config field $key")
        validate(field, value)?.let { throw IllegalArgumentException(it) }
        dao.upsertConfig(ConfigRow(programId, key, value.toString(), clock()))
    }

    suspend fun clear(programId: String, key: String) = dao.deleteConfig(programId, key)

    /** Required fields without a value or default; secret fields without a stored secret. */
    suspend fun missingRequired(programId: String): List<String> {
        val stored = dao.config(programId).map { it.key }.toSet()
        val secrets = dao.secrets(programId).map { it.name }.toSet()
        return fields(programId).filter { field ->
            field.required && when (ConfigType.of(field.type)) {
                ConfigType.SECRET -> field.key !in secrets
                else -> field.key !in stored && field.default == null
            }
        }.map { it.key }
    }

    companion object {
        fun validate(field: ConfigField, value: JsonElement): String? {
            val primitive = value as? JsonPrimitive
            return when (ConfigType.of(field.type)) {
                ConfigType.STRING -> if (primitive?.isString != true || primitive.content.length > 1_000) "${field.key} must be text of at most 1000 characters" else null
                ConfigType.NUMBER -> if (primitive == null || primitive.isString || primitive.doubleOrNull?.isFinite() != true) "${field.key} must be a number" else null
                ConfigType.BOOLEAN -> if (primitive == null || primitive.isString || primitive.booleanOrNull == null) "${field.key} must be true or false" else null
                ConfigType.ENUM -> if (primitive?.isString != true || primitive.content !in field.options.orEmpty()) "${field.key} must be one of ${field.options}" else null
                ConfigType.PACKAGE -> if (primitive?.isString != true || !primitive.content.matches(PACKAGE)) "${field.key} must be an app package name" else null
                ConfigType.SECRET -> "${field.key} is a secret; store it as a secret"
                null -> "${field.key} has an unknown type"
            }
        }
    }
}
