package com.github.ytlog.mobby.android.speech

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Resumable download and verified, atomic installation of the one local speech model. */
internal class SpeechModelStore(
    private val root: File,
    private val expectedSha256: String = SpeechModel.SHA256,
    private val preferDomestic: Boolean = false,
    private val mirrorFiles: Map<String, SpeechFile> = SpeechModel.MODELSCOPE_FILES,
    private val mirrorBase: String = SpeechModel.MODELSCOPE_BASE,
    private val mirrorDownload: (String, File, Long, (Long, Long) -> Unit) -> Unit = ::downloadFile,
    private val fetch: (File, (Long, Long) -> Unit) -> Unit = ::download,
) {
    fun modelDirectory(): File = File(root, SpeechModel.DIRECTORY)
    fun ready(): Boolean = SpeechModel.REQUIRED.all { File(modelDirectory(), it).isFile }
    private fun removeObsoleteModels() {
        SpeechModel.OBSOLETE.forEach { File(root, it).deleteRecursively() }
        File(root, "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2.partial").delete()
    }

    fun ensure(onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        if (ready()) {
            removeObsoleteModels()
            return
        }
        if (!root.isDirectory && !root.mkdirs()) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
        val sources = if (preferDomestic) listOf(::installFromModelScope, ::installFromArchive)
            else listOf(::installFromArchive, ::installFromModelScope)
        var failure: SpeechModelException? = null
        for (source in sources) {
            try {
                source(onProgress)
                removeObsoleteModels()
                return
            } catch (error: SpeechModelException) {
                if (failure == null || failure.kind == SpeechModelException.Kind.NETWORK) failure = error
            }
        }
        throw failure ?: SpeechModelException(SpeechModelException.Kind.NETWORK)
    }

    private fun installFromModelScope(onProgress: (Long, Long) -> Unit) {
        val staging = File(root, "${SpeechModel.DIRECTORY}.modelscope.installing")
        if (!staging.isDirectory && !staging.mkdirs()) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
        val total = mirrorFiles.values.sumOf { it.bytes }
        var completed = 0L
        for ((name, spec) in mirrorFiles) {
            if (name !in SpeechModel.REQUIRED) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
            val file = File(staging, name)
            if (file.length() != spec.bytes || !sha256(file).equals(spec.sha256, ignoreCase = true)) {
                file.delete()
                val partial = File(staging, "$name.partial")
                mirrorDownload("$mirrorBase$name", partial, spec.bytes) { current, _ ->
                    onProgress(completed + current, total)
                }
                if (partial.length() != spec.bytes || !sha256(partial).equals(spec.sha256, ignoreCase = true)) {
                    partial.delete()
                    throw SpeechModelException(SpeechModelException.Kind.CHECKSUM)
                }
                if (!partial.renameTo(file)) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
            }
            completed += spec.bytes
            onProgress(completed, total)
        }
        if (mirrorFiles.keys != SpeechModel.REQUIRED) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
        staging.listFiles()?.filter { it.name !in SpeechModel.REQUIRED }?.forEach { it.deleteRecursively() }
        modelDirectory().deleteRecursively()
        if (!staging.renameTo(modelDirectory())) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
        File(root, SpeechModel.PARTIAL).delete()
    }

    private fun installFromArchive(onProgress: (Long, Long) -> Unit) {
        val archive = File(root, SpeechModel.PARTIAL)
        fetch(archive, onProgress)
        if (!archive.isFile || !sha256(archive).equals(expectedSha256, ignoreCase = true)) {
            archive.delete()
            throw SpeechModelException(SpeechModelException.Kind.CHECKSUM)
        }
        val staging = File(root, "${SpeechModel.DIRECTORY}.archive.installing")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            archive.delete()
            throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
        }
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive).buffered())).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val relative = entry.name.removePrefix("${SpeechModel.DIRECTORY}/")
                    if (!entry.isFile || relative !in SpeechModel.REQUIRED || entry.name != "${SpeechModel.DIRECTORY}/$relative") continue
                    FileOutputStream(File(staging, relative)).use { tar.copyTo(it) }
                }
            }
            if (!SpeechModel.REQUIRED.all { File(staging, it).isFile }) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
            modelDirectory().deleteRecursively()
            if (!staging.renameTo(modelDirectory())) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
            archive.delete()
            File(root, "${SpeechModel.DIRECTORY}.modelscope.installing").deleteRecursively()
        } catch (error: Exception) {
            archive.delete()
            if (error is SpeechModelException) throw error
            throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
        } finally { staging.deleteRecursively() }
    }
}

private fun download(destination: File, onProgress: (Long, Long) -> Unit) {
    downloadFile(SpeechModel.URL, destination, SpeechModel.ARCHIVE_BYTES, onProgress)
}

private fun downloadFile(url: String, destination: File, expectedBytes: Long, onProgress: (Long, Long) -> Unit) {
    if (destination.length() > expectedBytes) destination.delete()
    if (destination.length() == expectedBytes) {
        onProgress(expectedBytes, expectedBytes)
        return
    }
    val offset = destination.length()
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "mobby")
        if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
    }
    try {
        connection.connect()
        val append = offset > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (connection.responseCode !in 200..299) throw SpeechModelException(SpeechModelException.Kind.NETWORK)
        connection.inputStream.use { input ->
            FileOutputStream(destination, append).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = if (append) offset else 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (read + count > expectedBytes) throw SpeechModelException(SpeechModelException.Kind.CHECKSUM)
                    output.write(buffer, 0, count)
                    read += count
                    onProgress(read, expectedBytes)
                }
            }
        }
    } catch (error: SpeechModelException) {
        throw error
    } catch (_: java.io.IOException) {
        throw SpeechModelException(SpeechModelException.Kind.NETWORK)
    } finally { connection.disconnect() }
    if (destination.length() != expectedBytes) throw SpeechModelException(SpeechModelException.Kind.NETWORK)
}
