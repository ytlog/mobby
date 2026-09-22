package com.github.ytlog.mobby.android.speech

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the speech model into app files only when [ensure] is called and the model is not already ready.
 * Callers must not invoke this at process start.
 */
internal class SpeechModelStore(
    private val root: File,
    private val expectedSha256: String = SpeechModel.SHA256,
    private val fetch: (File, (Long, Long) -> Unit) -> Unit = { destination, onProgress ->
        download(SpeechModel.URL, destination, onProgress)
    },
) {
    fun modelDirectory(): File = File(root, SpeechModel.DIRECTORY)

    fun ready(): Boolean = modelReady(modelDirectory())

    fun ensure(onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        if (ready()) return
        val partial = File(root, SpeechModel.PARTIAL)
        try {
            if (!root.isDirectory && !root.mkdirs()) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
            var reported = Int.MIN_VALUE
            fetch(partial) { read, total ->
                val bucket = if (total > 0L) ((read * 100) / total).toInt() else (read / (256 * 1024)).toInt()
                if (bucket != reported || (total > 0L && read >= total)) {
                    reported = bucket
                    onProgress(read, total)
                }
            }
            if (!partial.isFile) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
            if (!sha256(partial).equals(expectedSha256, ignoreCase = true)) {
                throw SpeechModelException(SpeechModelException.Kind.CHECKSUM)
            }
            unzipSafely(partial, root)
            if (!ready()) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
        } catch (error: Exception) {
            partial.delete()
            if (!ready()) modelDirectory().deleteRecursively()
            throw error
        } finally {
            partial.delete()
        }
    }
}

private fun download(url: String, destination: File, onProgress: (Long, Long) -> Unit) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "mobby")
    }
    try {
        connection.connect()
        if (connection.responseCode !in 200..299) throw SpeechModelException(SpeechModelException.Kind.NETWORK)
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    read += count
                    onProgress(read, total)
                }
            }
        }
    } catch (error: SpeechModelException) {
        throw error
    } catch (_: java.io.IOException) {
        throw SpeechModelException(SpeechModelException.Kind.NETWORK)
    } finally {
        connection.disconnect()
    }
}
