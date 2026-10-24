package com.github.ytlog.mobby.android.programs.base.storage

import com.github.ytlog.mobby.android.programs.capability.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** `storage.files`: text files under the program's own files directory, with a quota. */
class FilesCapability(private val limits: StorageLimits = StorageLimits.DEFAULT) : CapabilityHandler {
    override val descriptor = storageDescriptor("storage.files", CapabilityKind.COMMAND,
        schema(listOf("op"), "op" to enumType("read", "write", "list", "delete"), "path" to stringType(1024),
            "dir" to stringType(1024), "text" to stringType(limits.fileReadBytes)),
        setOf(SideEffect.READ, SideEffect.WRITE, SideEffect.DELETE))

    override fun availability(): Availability = Availability.Available

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome {
        val root = call.space.files.apply { mkdirs() }
        return when (call.args.text("op")) {
            "read" -> {
                val file = containedPath(root, call.args.text("path") ?: invalid("path_required"))
                if (!file.isFile) throw CapabilityException(ProgramErrorCode.RESOURCE_MISSING, "file_not_found")
                if (file.length() > limits.fileReadBytes) invalid("file_too_large_to_read_inline")
                val text = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString()
                } catch (_: CharacterCodingException) { invalid("file_is_not_utf8_text") }
                CapabilityOutcome(Effect.NONE, JsonPrimitive(text))
            }
            "write" -> {
                val path = call.args.text("path") ?: invalid("path_required")
                val text = call.args.text("text") ?: invalid("text_required")
                val file = containedPath(root, path)
                if (file.isDirectory) invalid("path_is_a_directory")
                val bytes = text.toByteArray()
                if (directoryBytes(root) - (if (file.isFile) file.length() else 0) + bytes.size > limits.filesBytes)
                    throw CapabilityException(ProgramErrorCode.QUOTA_EXCEEDED, "files_quota")
                file.parentFile!!.mkdirs()
                val staging = File(file.parentFile, ".${file.name}.writing")
                staging.writeBytes(bytes)
                if (!staging.renameTo(file)) { staging.delete(); throw CapabilityException(ProgramErrorCode.FAILED, "write_failed") }
                CapabilityOutcome(Effect.CONFIRMED, JsonNull)
            }
            "list" -> {
                val dir = containedPath(root, call.args.text("dir").orEmpty())
                if (!dir.isDirectory) throw CapabilityException(ProgramErrorCode.RESOURCE_MISSING, "directory_not_found")
                CapabilityOutcome(Effect.NONE, JsonArray(dir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.sortedBy { it.name }.map { entry ->
                    buildJsonObject {
                        put("name", entry.name); put("kind", if (entry.isDirectory) "directory" else "file")
                        if (entry.isFile) put("sizeBytes", entry.length())
                    }
                }))
            }
            "delete" -> {
                val path = call.args.text("path") ?: invalid("path_required")
                val file = containedPath(root, path)
                if (file == root) invalid("cannot_delete_root")
                CapabilityOutcome(if (file.exists() && file.deleteRecursively()) Effect.CONFIRMED else Effect.NONE, JsonNull)
            }
            else -> invalid("unknown_op")
        }
    }
}
