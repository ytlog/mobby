package com.mobby.runtime.android

import com.mobby.runtime.api.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Immutable, content-addressed imported text. No URI or caller-chosen filesystem path. */
internal class ResourceStore(private val root: File) {
    @Serializable private data class Document(val name: String, val text: String)
    private val json = Json
    private fun directory(): File {
        require(!Files.isSymbolicLink(root.toPath()))
        require(root.isDirectory || root.mkdirs())
        return root
    }
    fun save(request: ImportResourceRequest): ResourceSummary {
        require(request.workspaceRef.value == "default")
        require(request.name.isNotBlank() && request.name.length <= 200 && request.name.none { it.isISOControl() || it == '/' || it == '\\' })
        require(request.bytes.isNotEmpty() && request.bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(request.bytes)).toString()
        require(!request.name.endsWith(".pdf", ignoreCase = true) && !text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("%PDF-"))
        require(text.none { it.isISOControl() && it !in "\n\r\t" })
        val encoded = json.encodeToString(Document(request.name, text)).toByteArray(Charsets.UTF_8)
        val digest = hash(encoded)
        val target = File(directory(), digest)
        if (!target.exists()) {
            val temporary = File.createTempFile("import-", ".tmp", root)
            try {
                FileOutputStream(temporary).use { it.write(encoded); it.fd.sync() }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { temporary.delete() }
        }
        return read(ResourceRef("text:$digest"), request.workspaceRef).first
    }
    fun read(ref: ResourceRef, workspace: WorkspaceRef): Pair<ResourceSummary, String> {
        require(workspace.value == "default")
        val digest = ref.value.removePrefix("text:")
        require(ref.value == "text:$digest" && digest.matches(Regex("[a-f0-9]{64}")))
        val file = File(directory(), digest)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= MAX_BYTES * 6L + 2048)
        val encoded = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); require(out.size() <= MAX_BYTES * 6 + 2048) }
            out.toByteArray()
        }
        require(hash(encoded) == digest)
        val document = json.decodeFromString<Document>(encoded.toString(Charsets.UTF_8))
        return ResourceSummary(ref, document.name, document.text.toByteArray(Charsets.UTF_8).size, "text/plain") to document.text
    }
    fun prompt(parts: List<InputPart>, workspace: WorkspaceRef): String {
        val refs = parts.filterIsInstance<InputPart.Resource>()
        if (refs.size > MAX_FILES) throw InputTooLarge()
        val prompt = parts.joinToString("\n\n") { part -> when (part) {
            is InputPart.Text -> part.text
            is InputPart.Resource -> {
                val (summary, text) = read(part.ref, workspace)
                "用户所选文本附件（JSON 数据，保留原文）：\n" + json.encodeToString(Document(summary.name, text))
            }
        } }
        require(prompt.isNotBlank() && '\u0000' !in prompt)
        if (prompt.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) throw InputTooLarge()
        return prompt
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    class InputTooLarge : IllegalArgumentException()
    companion object { const val MAX_BYTES = 32 * 1024; const val MAX_FILES = 4; const val MAX_INPUT_BYTES = 65536 }
}
