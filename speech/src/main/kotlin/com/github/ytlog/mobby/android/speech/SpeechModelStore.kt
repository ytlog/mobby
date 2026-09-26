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
    private val fetch: (File, (Long, Long) -> Unit) -> Unit = ::download,
) {
    fun modelDirectory(): File = File(root, SpeechModel.DIRECTORY)
    fun ready(): Boolean = SpeechModel.REQUIRED.all { File(modelDirectory(), it).isFile }
    fun removeLegacyCache() { File(root, "vosk-model-small-cn-0.22").deleteRecursively() }

    fun ensure(onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        removeLegacyCache()
        if (ready()) return
        if (!root.isDirectory && !root.mkdirs()) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
        val archive = File(root, SpeechModel.PARTIAL)
        fetch(archive, onProgress)
        if (!archive.isFile || !sha256(archive).equals(expectedSha256, ignoreCase = true)) {
            archive.delete()
            throw SpeechModelException(SpeechModelException.Kind.CHECKSUM)
        }
        val staging = File(root, "${SpeechModel.DIRECTORY}.installing")
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
        } catch (error: Exception) {
            archive.delete()
            if (error is SpeechModelException) throw error
            throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
        } finally { staging.deleteRecursively() }
    }
}

private fun download(destination: File, onProgress: (Long, Long) -> Unit) {
    if (destination.length() > SpeechModel.ARCHIVE_BYTES) destination.delete()
    if (destination.length() == SpeechModel.ARCHIVE_BYTES) {
        onProgress(SpeechModel.ARCHIVE_BYTES, SpeechModel.ARCHIVE_BYTES)
        return
    }
    val offset = destination.length()
    val connection = (URL(SpeechModel.URL).openConnection() as HttpURLConnection).apply {
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
                    output.write(buffer, 0, count)
                    read += count
                    onProgress(read, SpeechModel.ARCHIVE_BYTES)
                }
            }
        }
    } catch (error: SpeechModelException) {
        throw error
    } catch (_: java.io.IOException) {
        throw SpeechModelException(SpeechModelException.Kind.NETWORK)
    } finally { connection.disconnect() }
}
