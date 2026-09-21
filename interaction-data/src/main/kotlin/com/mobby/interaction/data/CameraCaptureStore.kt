package com.mobby.interaction.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.mobby.interaction.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** One system-camera request at a time. Nothing enters a conversation before confirmation. */
internal class CameraCaptureStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("camera-capture", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "captures/output")
    private fun read(): CameraCapture? = prefs.getString("session", null)?.let {
        val data = Json.parseToJsonElement(it).jsonObject
        CameraCapture(data.getValue("id").jsonPrimitive.content, data.getValue("conversation").jsonPrimitive.content,
            data.getValue("workspace").jsonPrimitive.content, data.getValue("captureUri").jsonPrimitive.content,
            data["attachmentUri"]?.jsonPrimitive?.contentOrNull, CapturePhase.valueOf(data.getValue("phase").jsonPrimitive.content), data["error"]?.jsonPrimitive?.contentOrNull).also { capture ->
            require(capture.conversation.isNotBlank() && capture.workspace == "default")
            require(capture.captureUri == uri(File(directory(capture.id), "capture.jpg")))
            require(capture.attachmentUri == null || capture.attachmentUri == uri(File(directory(capture.id), "photo.jpg")))
            if (capture.phase in setOf(CapturePhase.REVIEW, CapturePhase.IMPORTING)) require(capture.attachmentUri != null)
        }
    }
    private fun write(value: CameraCapture?) {
        check(prefs.edit().apply {
            if (value == null) remove("session") else putString("session", buildJsonObject {
                put("id", value.id); put("conversation", value.conversation); put("workspace", value.workspace)
                put("captureUri", value.captureUri); put("attachmentUri", value.attachmentUri); put("phase", value.phase.name); put("error", value.error)
            }.toString())
        }.commit())
    }
    private fun checkRoot() {
        require(!Files.isSymbolicLink(root.parentFile.toPath()) && !Files.isSymbolicLink(root.toPath()))
    }
    private fun directory(id: String): File {
        checkRoot()
        require(id.matches(Regex("[a-f0-9-]{36}")))
        require(!Files.isSymbolicLink(root.toPath()))
        val result = File(root, id)
        require(!Files.isSymbolicLink(result.toPath()))
        return result
    }
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.captures", file).toString()
    suspend fun current(): CameraCapture? = lock.withLock {
        checkRoot()
        var current = read()
        if (current?.phase == CapturePhase.DISCARDING) { discardLocked(current); current = null }
        // A directory without a durable session was never handed to the camera.
        root.listFiles()?.filter { it.name != current?.id && it.name.matches(Regex("[a-f0-9-]{36}")) }?.forEach(::removeDirectory)
        current
    }
    suspend fun begin(conversation: String, workspace: String): CameraCapture = lock.withLock {
        require(read() == null) { "请先处理已有拍照或导入" }
        require(conversation.isNotBlank() && workspace == "default")
        val id = UUID.randomUUID().toString()
        val directory = directory(id); check(directory.mkdirs())
        try {
            val source = File(directory, "capture.jpg"); check(source.createNewFile())
            CameraCapture(id, conversation, workspace, uri(source)).also(::write)
        } catch (e: Exception) { removeDirectory(directory); throw e }
    }
    suspend fun finish(id: String, success: Boolean): CameraCapture? = lock.withLock {
        val current = read() ?: return@withLock null
        if (current.id != id || current.phase != CapturePhase.CAPTURING) return@withLock current
        revoke(current)
        if (!success) { discardLocked(current); return@withLock null }
        val next = try {
            val source = File(directory(id), "capture.jpg")
            require(Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS) && source.length() in 1..MAX_RAW_BYTES)
            val normalized = normalize(source, 2048)
            val target = File(directory(id), "photo.jpg")
            val temporary = File(directory(id), "photo.tmp")
            java.io.FileOutputStream(temporary).use { it.write(normalized); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            current.copy(attachmentUri = uri(target), phase = CapturePhase.REVIEW)
        } catch (_: Exception) { current.copy(phase = CapturePhase.ERROR, error = "照片处理失败：请取消后重拍（原图最多 32 MiB）") }
        write(next); next
    }
    suspend fun preview(id: String): ByteArray = lock.withLock {
        val current = requireNotNull(read()); require(current.id == id && current.phase == CapturePhase.REVIEW)
        normalize(File(directory(id), "photo.jpg"), 1024)
    }
    suspend fun discard(id: String) = lock.withLock {
        val current = read() ?: return@withLock
        require(current.id == id && current.phase != CapturePhase.IMPORTING)
        discardLocked(current)
    }
    /** Called only after the repository persisted the pending attachment. */
    suspend fun importing(location: String) = lock.withLock {
        read()?.takeIf { it.attachmentUri == location && it.phase == CapturePhase.REVIEW }?.let { write(it.copy(phase = CapturePhase.IMPORTING)) }
    }
    suspend fun retain(locations: Set<String>) = lock.withLock {
        val current = read() ?: return@withLock
        if (current.phase == CapturePhase.DISCARDING) { discardLocked(current); return@withLock }
        if (current.attachmentUri in locations && current.phase == CapturePhase.REVIEW) write(current.copy(phase = CapturePhase.IMPORTING))
        else if (current.phase == CapturePhase.IMPORTING && current.attachmentUri !in locations) {
            discardLocked(current)
        }
    }
    private fun discardLocked(current: CameraCapture) {
        write(current.copy(phase = CapturePhase.DISCARDING))
        revoke(current); removeDirectory(directory(current.id)); write(null)
    }
    private fun revoke(current: CameraCapture) = context.revokeUriPermission(Uri.parse(current.captureUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    private fun removeDirectory(directory: File) {
        if (!directory.exists() && !Files.isSymbolicLink(directory.toPath())) return
        Files.walk(directory.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
    private fun normalize(file: File, edge: Int): ByteArray {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..MAX_RAW_BYTES)
        java.io.RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 4); input.seek(input.length() - 2); require(input.readUnsignedShort() == 0xffd9)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge) sample *= 2
        val source = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }))
        var bitmap: Bitmap? = null
        try {
            val exif = ExifInterface(file)
            val scale = minOf(1f, edge.toFloat() / maxOf(source.width, source.height))
            bitmap = Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat()); postScale(scale, scale)
            }, true)
            for (quality in listOf(90, 80, 65, 50)) {
                val output = java.io.ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= 2 * 1024 * 1024) return output.toByteArray()
            }
            error("Captured image exceeds import limit")
        } finally { if (bitmap !== source) bitmap?.recycle(); source.recycle() }
    }
    companion object { private val lock = Mutex(); private const val MAX_RAW_BYTES = 32L * 1024 * 1024 }
}
