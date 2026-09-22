package com.github.ytlog.mobby.android.runtime.android

import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** Per-run private materialization, removed after exit/cancel and during post-recovery startup. */
internal class AgentInputFiles private constructor(private val directory: File, val imagePaths: List<String>, val schemaPath: String?) : Closeable {
    override fun close() = cleanup(directory)
    companion object {
        fun claudeMessage(prompt: String, images: List<ResourceStore.Image>): JsonObject = buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    images.forEach { image -> add(buildJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64"); put("media_type", image.mediaType)
                            put("data", Base64.getEncoder().encodeToString(image.bytes))
                        }
                    }) }
                }
            }
        }

        fun create(root: File, images: List<ResourceStore.Image>, schema: String? = null): AgentInputFiles {
            require(!Files.isSymbolicLink(root.toPath()))
            require(root.isDirectory || root.mkdirs())
            val directory = Files.createTempDirectory(root.toPath(), "run-").toFile()
            try {
                val paths = images.mapIndexed { index, image ->
                    File(directory, "image-$index.${if (image.mediaType == "image/png") "png" else "jpg"}").apply { writeBytes(image.bytes) }.absolutePath
                }
                val schemaPath = schema?.let { File(directory, "output-schema.json").apply { writeText(it) }.absolutePath }
                return AgentInputFiles(directory, paths, schemaPath)
            } catch (error: Throwable) { cleanup(directory); throw error }
        }
        fun cleanup(root: File) {
            if (!root.exists() && !Files.isSymbolicLink(root.toPath())) return
            // Files.walk does not follow symbolic links, including links left inside an old run.
            Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
