package com.mobby.runtime.android

import android.content.Context
import com.mobby.runtime.api.*
import com.mobby.runtime.engine.OutputStorePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Opaque references can only address runtime-owned segments, never arbitrary filesystem paths. */
internal class OutputStore(context: Context) : OutputStorePort {
    private val root = File(context.filesDir, "runtime-output").apply { mkdirs() }
    private val valid = Regex("[A-Za-z0-9-]{1,100}/[0-9]{1,20}")
    override suspend fun write(runId: RunId, name: String, text: String): ResourceRef = withContext(Dispatchers.IO) {
        val ref = ResourceRef("${runId.value}/$name")
        val target = file(ref)
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        val temporary = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temporary).use { it.write(text.toByteArray()); it.fd.sync() }
        check(temporary.renameTo(target)) { "Output persistence failed" }
        ref
    }
    override suspend fun read(request: ArtifactReadRequest): ArtifactReadResult = withContext(Dispatchers.IO) {
        if (!valid.matches(request.artifactRef.value)) return@withContext ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.PERMISSION_DENIED))
        val file = file(request.artifactRef)
        if (!file.isFile) return@withContext ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING))
        try {
            RandomAccessFile(file, "r").use {
                val length = it.length()
                if (request.offset > length) return@withContext ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.INVALID_CONFIG))
                it.seek(request.offset)
                val bytes = ByteArray(minOf(request.limit.toLong(), length - request.offset).toInt())
                it.readFully(bytes)
                val next = request.offset + bytes.size
                ArtifactReadResult.Chunk(bytes.toList(), next.takeIf { offset -> offset < length }, false)
            }
        } catch (_: Exception) { ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
    }
    private fun file(ref: ResourceRef): File {
        require(valid.matches(ref.value))
        val path = File(root, ref.value).canonicalFile
        require(path.path.startsWith(root.canonicalPath + File.separator))
        return path
    }
}
