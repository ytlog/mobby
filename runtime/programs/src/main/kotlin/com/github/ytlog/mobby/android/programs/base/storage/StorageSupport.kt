package com.github.ytlog.mobby.android.programs.base.storage

import com.github.ytlog.mobby.android.programs.capability.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class StorageLimits(
    val kvBytes: Long = 5L * 1024 * 1024,
    val kvValueBytes: Int = 64 * 1024,
    val filesBytes: Long = 100L * 1024 * 1024,
    val fileReadBytes: Int = 1024 * 1024,
    val sqlBytes: Long = 100L * 1024 * 1024,
    val sqlRows: Int = 1_000,
    val sqlResultBytes: Int = 1024 * 1024,
) { companion object { val DEFAULT = StorageLimits() } }

internal fun storageDescriptor(id: String, kind: CapabilityKind, schema: JsonObject, effects: Set<SideEffect>) = CapabilityDescriptor(
    id = id, version = 1, kind = kind, inputSchema = schema, outputSchema = buildJsonObject { put("type", "object") },
    scope = ScopeKind.NONE, sideEffects = effects, prerequisites = listOf(Prerequisite.PROGRAM_SPACE),
    surfaces = setOf(Surface.BACKGROUND, Surface.UI), timeoutMillis = 10_000, cancellable = false,
    limits = CapabilityLimits(maxArgumentBytes = 2 * 1024 * 1024, callsPerMinute = 600),
)

internal fun schema(required: List<String>, vararg properties: Pair<String, JsonObject>) = buildJsonObject {
    put("type", "object"); put("additionalProperties", false)
    put("properties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
    put("required", JsonArray(required.map(::JsonPrimitive)))
}

internal fun stringType(max: Int) = buildJsonObject { put("type", "string"); put("maxLength", max) }
internal fun enumType(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun invalid(reason: String): Nothing = throw CapabilityException(ProgramErrorCode.INVALID_ARGUMENT, reason)

/** Resolves a relative path inside [root]; no absolute paths, `..`, or links on the way. */
internal fun containedPath(root: File, relative: String): File {
    if (relative.isEmpty()) return root
    if (relative.startsWith("/") || relative.contains('\u0000') || relative.split('/').any { it == ".." || it == "." })
        invalid("path_outside_program_files")
    val target = File(root, relative)
    var current: File? = target
    while (current != null && current != root) {
        if (Files.isSymbolicLink(current.toPath())) invalid("links_not_allowed")
        current = current.parentFile
    }
    val base = root.canonicalPath
    if (target.canonicalPath != base && !target.canonicalPath.startsWith(base + File.separator)) invalid("path_outside_program_files")
    return target
}

/** Bytes of regular files under [root]; links are not followed, so nothing outside is counted. */
internal fun directoryBytes(root: File): Long {
    if (!root.exists()) return 0
    return Files.walk(root.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
    }
}
