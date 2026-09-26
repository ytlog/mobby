package com.github.ytlog.mobby.android.speech

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.sqrt

/** Apache-2.0 streaming Chinese model; no other ASR model is installed. */
internal object SpeechModel {
    const val DIRECTORY = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$DIRECTORY.tar.bz2"
    const val SHA256 = "2cbd71b640d9c37d3784f29367333a4577b0398b62e9deeed418170b081cba8b"
    const val ARCHIVE_BYTES = 74_004_050L
    const val PARTIAL = "$DIRECTORY.tar.bz2.partial"
    val REQUIRED = setOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")
}

internal class SpeechModelException(val kind: Kind) : Exception() {
    enum class Kind { NETWORK, CHECKSUM, INCOMPLETE }
}

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
    return (sqrt(sum / samples) / 32768.0 / 0.2).toFloat().coerceIn(0f, 1f)
}

internal fun normalizeTranscript(text: String): String =
    text.trim().replace(Regex("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})"), "")
