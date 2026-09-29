package com.github.ytlog.mobby.android.device

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

internal data class ScreenScreenshot(val jpeg: ByteArray? = null, val status: String)

/** Keep screen evidence within the image resource store's size and dimension limits. */
internal object ScreenScreenshotEncoder {
    fun encode(source: Bitmap): ByteArray {
        val scale = minOf(1f, 1800f / maxOf(source.width, source.height))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        var bitmap = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            repeat(4) { attempt ->
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output))
                val bytes = output.toByteArray()
                if (bytes.size <= 2 * 1024 * 1024) return bytes
                if (attempt == 3) error("Screenshot exceeds the image resource limit")
                val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * .75f).toInt().coerceAtLeast(1),
                    (bitmap.height * .75f).toInt().coerceAtLeast(1), true)
                if (bitmap !== source) bitmap.recycle()
                bitmap = smaller
            }
            error("Screenshot could not be encoded")
        } finally { if (bitmap !== source) bitmap.recycle() }
    }
}
