package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.localization.AppStrings

import android.graphics.BitmapFactory
import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Base64

/** Immutable imported content. External URIs and caller-chosen paths never reach execution. */
internal class ResourceStore(private val root: File, private val budgetBytes: () -> Long = { DEFAULT_BUDGET_BYTES }) {
    @Serializable private data class Document(val name: String, val text: String, val workspace: String = "default")
    @Serializable private data class ImageDocument(val name: String, val mediaType: String, val base64: String, val workspace: String = "default")
    @Serializable private data class BinaryDocument(val name: String, val mediaType: String, val base64: String, val workspace: String)
    class Image(val name: String, val mediaType: String, val bytes: ByteArray)
    class Prepared(val prompt: String, val images: List<Image>)
    private val json = Json
    private fun directory(): File {
        require(!Files.isSymbolicLink(root.toPath()))
        require(root.isDirectory || root.mkdirs())
        return root
    }
    fun save(request: ImportResourceRequest): ResourceSummary = synchronized(importLock) {
        require(WorkspaceStore.validRef(request.workspaceRef.value))
        require(request.name.isNotBlank() && request.name.length <= 200 && request.name.none { it.isISOControl() || it == '/' || it == '\\' })
        require(request.bytes.isNotEmpty() && request.bytes.size <= MAX_IMAGE_BYTES)
        val imageType = imageType(request.bytes)
        val encoded: ByteArray
        val prefix: String
        if (imageType != null) {
            validateImage(request.bytes, imageType)
            prefix = "image"
            encoded = json.encodeToString(ImageDocument(request.name, imageType, Base64.getEncoder().encodeToString(request.bytes), request.workspaceRef.value)).toByteArray()
        } else {
            require(request.bytes.size <= MAX_BYTES)
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(request.bytes)).toString()
            require(!request.name.endsWith(".pdf", ignoreCase = true) && !text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("%PDF-"))
            require(text.none { it.isISOControl() && it !in "\n\r\t" })
            prefix = "text"
            encoded = json.encodeToString(Document(request.name, text, request.workspaceRef.value)).toByteArray()
        }
        persist(encoded, prefix, request.workspaceRef)
    }
    private fun persist(encoded: ByteArray, prefix: String, workspace: WorkspaceRef): ResourceSummary {
        val digest = hash(encoded)
        val target = File(directory(), digest)
        if (!Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
            val limit = budgetBytes().also { require(it >= 0) }
            if (encoded.size.toLong() > limit) throw QuotaExceeded()
            val entries = storageEntries()
            val used = entries.fold(0L) { total, file -> Math.addExact(total, Files.size(file.toPath())) }
            var needed = (used - (limit - encoded.size)).coerceAtLeast(0)
            val victims = mutableListOf<File>()
            if (needed > 0) {
                for (file in entries.filter { it.name.matches(DIGEST) }
                    .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
                    victims += file
                    needed -= Files.size(file.toPath())
                    if (needed <= 0) break
                }
                if (needed > 0) throw QuotaExceeded()
            }
            val temporary = File.createTempFile("import-", ".tmp", root)
            try {
                FileOutputStream(temporary).use { it.write(encoded); it.fd.sync() }
                victims.forEach { check(it.delete()) { "Could not evict attachment cache entry" } }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { temporary.delete() }
        }
        return summary(ResourceRef("$prefix:$digest"), workspace)
    }
    private fun storageEntries(): List<File> = Files.newDirectoryStream(directory().toPath()).use { paths ->
        paths.map { path ->
            check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Unexpected attachment storage entry" }
            path.toFile()
        }.toList()
    }
    private fun touch(ref: ResourceRef) {
        val file = File(directory(), ref.value.substringAfter(':'))
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw FileNotFoundException("Attachment was evicted")
        val key = root.absolutePath
        val latest = lastAccessByDirectory.getOrPut(key) { storageEntries().maxOfOrNull { it.lastModified() } ?: 0L }
        val next = maxOf(System.currentTimeMillis(), latest + 1)
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(next))
        lastAccessByDirectory[key] = next
    }

    /** Registers executor-owned material before its temporary inbox is removed. */
    fun saveDevice(file: File, workspace: WorkspaceRef, mediaType: String): ResourceSummary = synchronized(importLock) {
        require(file.isFile && file.length() in 1..(32L * 1024 * 1024))
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 32 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size in 1..32 * 1024 * 1024)
        if (imageType(bytes) != null || mediaType.startsWith("text/")) return@synchronized save(ImportResourceRequest(workspace, file.name, bytes))
        require(WorkspaceStore.validRef(workspace.value))
        val encoded = json.encodeToString(BinaryDocument(file.name, mediaType, Base64.getEncoder().encodeToString(bytes), workspace.value)).toByteArray()
        persist(encoded, "binary", workspace)
    }
    fun contentBytes(ref: ResourceRef, workspace: WorkspaceRef): ByteArray = synchronized(importLock) { when {
        ref.value.startsWith("binary:") -> {
            val stored = json.decodeFromString<BinaryDocument>(encoded(ref, workspace, "binary", 48 * 1024 * 1024).toString(Charsets.UTF_8))
            require(stored.workspace == workspace.value)
            Base64.getDecoder().decode(stored.base64).also { touch(ref) }
        }
        ref.value.startsWith("image:") -> image(ref, workspace).bytes
        else -> read(ref, workspace).second.toByteArray()
    } }
    private fun encoded(ref: ResourceRef, workspace: WorkspaceRef, prefix: String, limit: Int): ByteArray {
        require(WorkspaceStore.validRef(workspace.value))
        val digest = ref.value.removePrefix("$prefix:")
        require(ref.value == "$prefix:$digest" && digest.matches(Regex("[a-f0-9]{64}")))
        val file = File(directory(), digest)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) throw FileNotFoundException("Attachment was evicted")
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= limit)
        val encoded = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); require(out.size() <= limit) }
            out.toByteArray()
        }
        require(hash(encoded) == digest)
        return encoded
    }
    fun read(ref: ResourceRef, workspace: WorkspaceRef): Pair<ResourceSummary, String> = synchronized(importLock) {
        val document = json.decodeFromString<Document>(encoded(ref, workspace, "text", MAX_BYTES * 6 + 2048).toString(Charsets.UTF_8))
        require(document.workspace == workspace.value)
        touch(ref)
        ResourceSummary(ref, document.name, document.text.toByteArray().size, "text/plain") to document.text
    }
    fun image(ref: ResourceRef, workspace: WorkspaceRef): Image = synchronized(importLock) {
        val document = json.decodeFromString<ImageDocument>(encoded(ref, workspace, "image", MAX_IMAGE_BYTES * 2 + 2048).toString(Charsets.UTF_8))
        require(document.workspace == workspace.value)
        val bytes = Base64.getDecoder().decode(document.base64)
        require(bytes.size <= MAX_IMAGE_BYTES && imageType(bytes) == document.mediaType)
        touch(ref)
        Image(document.name, document.mediaType, bytes)
    }
    fun preview(ref: ResourceRef, workspace: WorkspaceRef, expanded: Boolean): ByteArray {
        if (!ref.value.startsWith("image:")) return contentBytes(ref, workspace)
        val image = image(ref, workspace)
        return ImagePreview.render(image.bytes, if (expanded) 1024 else 256)
    }
    fun summary(ref: ResourceRef, workspace: WorkspaceRef): ResourceSummary = synchronized(importLock) { if (ref.value.startsWith("image:")) {
        val image = image(ref, workspace)
        ResourceSummary(ref, image.name, image.bytes.size, image.mediaType)
    } else if (ref.value.startsWith("binary:")) {
        val stored = json.decodeFromString<BinaryDocument>(encoded(ref, workspace, "binary", 48 * 1024 * 1024).toString(Charsets.UTF_8))
        require(stored.workspace == workspace.value)
        touch(ref)
        ResourceSummary(ref, stored.name, Base64.getDecoder().decode(stored.base64).size, stored.mediaType)
    } else read(ref, workspace).first
    }

    fun prepare(parts: List<InputPart>, workspace: WorkspaceRef): Prepared = synchronized(importLock) {
        if (parts.filterIsInstance<InputPart.Resource>().size > MAX_FILES) throw InputTooLarge()
        val images = mutableListOf<Image>()
        val prompt = parts.joinToString("\n\n") { part -> when (part) {
            is InputPart.Text -> part.text
            is InputPart.Resource -> if (part.ref.value.startsWith("image:")) {
                val image = image(part.ref, workspace); images.add(image)
                AppStrings.selectedImagePrompt(images.size) + json.encodeToString(image.name)
            } else {
                val (summary, text) = read(part.ref, workspace)
                AppStrings.selectedTextAttachmentPrompt + json.encodeToString(Document(summary.name, text))
            }
        } }
        require(prompt.isNotBlank() && '\u0000' !in prompt)
        if (prompt.toByteArray().size > MAX_INPUT_BYTES) throw InputTooLarge()
        Prepared(prompt, images)
    }
    /** Legacy text-only callers cannot accidentally turn images into placeholders. */
    fun prompt(parts: List<InputPart>, workspace: WorkspaceRef): String = prepare(parts, workspace).let {
        require(it.images.isEmpty()); it.prompt
    }
    private fun imageType(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map { it.toByte() } -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        else -> null
    }
    private fun validateImage(bytes: ByteArray, mediaType: String) {
        if (mediaType == "image/png") {
            var offset = 8
            var ended = false
            while (offset < bytes.size) {
                require(bytes.size - offset >= 12)
                val size = ByteBuffer.wrap(bytes, offset, 4).int
                require(size >= 0 && size <= bytes.size - offset - 12)
                val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
                require(type != "acTL") // Animated images cannot be faithfully represented as one still.
                val crc = java.util.zip.CRC32().apply { update(bytes, offset + 4, size + 4) }.value
                require(crc == (ByteBuffer.wrap(bytes, offset + size + 8, 4).int.toLong() and 0xffffffffL))
                offset += size + 12
                if (type == "IEND") { require(size == 0 && offset == bytes.size); ended = true }
            }
            require(ended)
        }

        if (mediaType == "image/jpeg") require(bytes.size >= 4 && bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == mediaType && bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000)
        // Decode once at import/read so truncated or invalid pixel data is not marked ready.
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        bitmap.recycle()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    class QuotaExceeded : java.io.IOException("Attachment storage budget exceeded")
    class InputTooLarge : IllegalArgumentException()
    companion object {
        private val importLock = Any()
        private val lastAccessByDirectory = mutableMapOf<String, Long>()
        private val DIGEST = Regex("[a-f0-9]{64}")
        const val DEFAULT_BUDGET_BYTES = 512L * 1024 * 1024
        const val MAX_BYTES = 32 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_FILES = 4
        const val MAX_INPUT_BYTES = 65536
    }
}
