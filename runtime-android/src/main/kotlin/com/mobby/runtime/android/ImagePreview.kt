package com.mobby.runtime.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/** Downsample before allocating pixels; previews do not modify original CLI input. */
internal object ImagePreview {
    fun render(bytes: ByteArray, edge: Int): ByteArray {
        require(edge == 256 || edge == 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
        var transformed: Bitmap? = null
        try {
            val exif = bytes.inputStream().use { ExifInterface(it) }
            val scale = minOf(1f, edge.toFloat() / maxOf(decoded.width, decoded.height))
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
                postScale(scale, scale)
            }
            transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            return ByteArrayOutputStream().use { output ->
                check(transformed.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            if (transformed !== decoded) transformed?.recycle()
            decoded.recycle()
        }
    }
}
