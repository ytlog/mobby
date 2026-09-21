package com.mobby.runtime.android

import android.content.Context
import com.mobby.runtime.api.*
import com.mobby.runtime.engine.OutputStorePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Opaque references can only address runtime-owned segments, never arbitrary filesystem paths. */
internal class OutputStore(context: Context, private val expired: suspend (RunId) -> Boolean = { false },
    private val policyProvider: () -> OutputRetentionPolicy = { OutputRetentionPolicy() }) : OutputStorePort {
    private val root = File(context.filesDir, "runtime-output").apply { mkdirs() }
    private val valid = Regex("[A-Za-z0-9-]{1,100}/[0-9]{1,20}")
    override suspend fun write(runId: RunId, name: String, text: String): ResourceRef = withContext(Dispatchers.IO) {
        check(!expired(runId)) { "Output was already expired" }
        val ref = ResourceRef("${runId.value}/$name")
        val target = file(ref)
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        val temporary = File(target.parentFile, target.name + ".tmp")
        // Exclusive creation prevents a stale or injected .tmp entry from redirecting the write.
        FileChannel.open(temporary.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteBuffer.wrap(text.toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        check(temporary.renameTo(target)) { "Output persistence failed" }
        ref
    }
    override suspend fun read(request: ArtifactReadRequest): ArtifactReadResult = withContext(Dispatchers.IO) {
        if (!valid.matches(request.artifactRef.value)) return@withContext ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.PERMISSION_DENIED))
        val id = RunId(request.artifactRef.value.substringBefore('/'))
        if (expired(id)) return@withContext ArtifactReadResult.Expired
        try {
            val file = file(request.artifactRef)
            if (!file.isFile) return@withContext if (expired(id)) ArtifactReadResult.Expired else ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING))
            RandomAccessFile(file, "r").use {
                val length = it.length()
                if (request.offset > length) return@withContext ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.INVALID_CONFIG))
                it.seek(request.offset)
                val bytes = ByteArray(minOf(request.limit.toLong(), length - request.offset).toInt())
                it.readFully(bytes)
                val next = request.offset + bytes.size
                ArtifactReadResult.Chunk(bytes.toList(), next.takeIf { offset -> offset < length }, false)
            }
        } catch (_: SecurityException) { ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.PERMISSION_DENIED)) }
        catch (_: java.io.IOException) { if (expired(id)) ArtifactReadResult.Expired else ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING)) }
    }
    suspend fun compact(journal: RuntimeJournal, policy: OutputRetentionPolicy = policyProvider(), now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val candidates = journal.outputCandidates()
        // A prior crash may have committed expiration without removing all files. Retry those first.
        for (candidate in candidates.filter { it.expired }) purge(candidate.id)
        val retained = candidates.filterNot { it.expired }.map { it to files(it.id).sumOf { file -> file.length() } }
        var bytes = retained.sumOf { it.second }
        for ((candidate, size) in retained) {
            if (candidate.finishedAt <= now - policy.maxAgeMillis || bytes > policy.maxBytes) {
                check(journal.expireOutput(candidate.id)) { "Output eligibility changed" }
                purge(candidate.id)
                bytes -= size
            }
        }
    }
    private fun files(id: RunId): List<File> {
        val directory = file(ResourceRef("${id.value}/0")).parentFile!!
        if (!directory.exists()) return emptyList()
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        return checkNotNull(directory.listFiles()).toList().onEach {
            check(it.name.removeSuffix(".tmp").matches(Regex("[0-9]{1,20}")) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) { "Unexpected runtime output entry" }
        }
    }
    private fun purge(id: RunId) {
        for (file in files(id)) check(file.delete()) { "Output cleanup failed" }
        val directory = file(ResourceRef("${id.value}/0")).parentFile!!
        check(!directory.exists() || directory.delete()) { "Output directory cleanup failed" }
    }
    private fun file(ref: ResourceRef): File {
        require(valid.matches(ref.value))
        val lexical = File(root, ref.value)
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(lexical.parentFile!!.toPath()) || Files.isSymbolicLink(lexical.toPath())) throw SecurityException("Output symlink")
        val path = lexical.canonicalFile
        require(path.path.startsWith(root.canonicalPath + File.separator))
        return path
    }
}

internal data class OutputRetentionPolicy(val maxAgeMillis: Long = 30L * 86_400_000, val maxBytes: Long = 256L * 1024 * 1024) {
    init { require(maxAgeMillis >= 0 && maxBytes >= 0) }
}
