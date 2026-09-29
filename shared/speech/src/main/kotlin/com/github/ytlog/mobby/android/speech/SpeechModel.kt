package com.github.ytlog.mobby.android.speech

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.sqrt

/** Apache-2.0 streaming Chinese/English model; only its quantized inference files are installed. */
internal object SpeechModel {
    const val DIRECTORY = "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16"
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$DIRECTORY.tar.bz2"
    const val SHA256 = "2b7c63322b32e5e0f2526043a1103366119ca58dd615cd7105a37c01db9553d7"
    const val ARCHIVE_BYTES = 458_187_351L
    const val PARTIAL = "$DIRECTORY.tar.bz2.partial"
    const val MODELSCOPE_REPO = "liaowenbin/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile"
    const val MODELSCOPE_BASE = "https://modelscope.cn/api/v1/models/$MODELSCOPE_REPO/repo?Revision=master&FilePath="
    val MODELSCOPE_FILES = linkedMapOf(
        "encoder-epoch-99-avg-1.int8.onnx" to SpeechFile(32_631_819, "ed2932a455d69fb6555ee6a1badcc4b765bef60e426796b62fca18b36328b941"),
        "decoder-epoch-99-avg-1.onnx" to SpeechFile(13_877_276, "89be509a83175261695bdef5fd1c7b9ab1129a663d1284e7ba9f8507b21e0906"),
        "joiner-epoch-99-avg-1.int8.onnx" to SpeechFile(3_228_485, "bdda356d6f9b8c2d7cee9ee0e26075fa537490f7fd06520be408d287073667b9"),
        "tokens.txt" to SpeechFile(56_317, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"),
    )
    val OBSOLETE = setOf("vosk-model-small-cn-0.22", "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23")
    val REQUIRED = setOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")
}

internal data class SpeechFile(val bytes: Long, val sha256: String)

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
