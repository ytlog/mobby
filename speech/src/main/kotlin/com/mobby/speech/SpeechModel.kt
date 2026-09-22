package com.mobby.speech

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/** Chinese small model. The archive is downloaded on first use and is not part of the APK. */
internal object SpeechModel {
    const val DIRECTORY = "vosk-model-small-cn-0.22"
    const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    const val SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
    const val PARTIAL = "$DIRECTORY.zip.partial"
}

internal class SpeechModelException(val kind: Kind) : Exception() {
    enum class Kind { NETWORK, CHECKSUM, INCOMPLETE }
}

internal fun modelReady(directory: File): Boolean =
    File(directory, "am/final.mdl").isFile && File(directory, "conf/model.conf").isFile

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun unzipSafely(zip: File, destination: File) {
    val root = destination.canonicalFile
    val rootPath = root.path + File.separator
    ZipInputStream(FileInputStream(zip).buffered()).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            val target = File(root, entry.name).canonicalFile
            if (target != root && !target.path.startsWith(rootPath)) {
                throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
            }
            if (entry.isDirectory) {
                if (!target.isDirectory && !target.mkdirs()) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
            } else {
                val parent = target.parentFile ?: throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
                if (!parent.isDirectory && !parent.mkdirs()) throw SpeechModelException(SpeechModelException.Kind.INCOMPLETE)
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            input.closeEntry()
        }
    }
}

/** RMS of 16-bit little-endian PCM, scaled so typical speech sits below full level. */
internal fun pcmLevel(bytes: ByteArray, length: Int): Float {
    val samples = length / 2
    if (samples <= 0) return 0f
    var sum = 0.0
    var index = 0
    while (index + 1 < length) {
        val low = bytes[index].toInt() and 0xff
        val high = bytes[index + 1].toInt()
        val signed = (high shl 8 or low).toShort().toInt()
        sum += signed.toDouble() * signed
        index += 2
    }
    val rms = sqrt(sum / samples) / 32768.0
    return (rms / 0.2).toFloat().coerceIn(0f, 1f)
}

internal fun transcriptOf(json: String?): String {
    if (json.isNullOrBlank()) return ""
    val text = jsonStringField(json, "text").ifBlank { jsonStringField(json, "partial") }
    return normalizeTranscript(text)
}

internal fun normalizeTranscript(text: String): String =
    text.trim().replace(Regex("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})"), "")

private fun jsonStringField(json: String, name: String): String {
    val match = Regex(""""${Regex.escape(name)}"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json) ?: return ""
    return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
}
