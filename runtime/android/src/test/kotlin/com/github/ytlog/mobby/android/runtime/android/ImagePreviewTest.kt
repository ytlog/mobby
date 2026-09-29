package com.github.ytlog.mobby.android.runtime.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.github.ytlog.mobby.android.runtime.api.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePreviewTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `bounded preview honors JPEG orientation while keeping original resource bytes`() {
        val jpeg = temporary.newFile("oriented.jpg")
        val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        ExifInterface(jpeg).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val bytes = jpeg.readBytes(); val store = PersistentResourceStore(temporary.newFolder())
        val workspace = WorkspaceRef("default")
        val saved = store.save(ImportResourceRequest(workspace, "portrait.jpg", bytes))
        for ((expanded, edge) in listOf(false to 256, true to 1024)) {
            val preview = store.preview(saved.ref, workspace, expanded)
            val decoded = BitmapFactory.decodeByteArray(preview, 0, preview.size)
            assertEquals(edge, decoded.height); assertEquals(edge / 2, decoded.width)
            decoded.recycle()
            assertArrayEquals(bytes, store.image(saved.ref, workspace).bytes)
        }
        assertThrows(Exception::class.java) { store.preview(saved.ref, WorkspaceRef("other"), false) }
    }
    @Test fun `preview preserves PNG alpha and does not upscale small images`() {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        val output = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val preview = ImagePreview.render(output.toByteArray(), 256)
        val decoded = BitmapFactory.decodeByteArray(preview, 0, preview.size)
        assertEquals(8, decoded.width); assertEquals(4, decoded.height)
        assertEquals(0, android.graphics.Color.alpha(decoded.getPixel(0, 0))); decoded.recycle()
        assertThrows(Exception::class.java) { ImagePreview.render(output.toByteArray(), 100000) }
    }
    @Test fun `all EXIF rotations and reflections put the expected corner at top left`() {
        val colors = listOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE, android.graphics.Color.YELLOW)
        val expected = listOf(colors[0], colors[1], colors[3], colors[2], colors[0], colors[2], colors[3], colors[1])
        for (orientation in 1..8) {
            val file = temporary.newFile("orientation-$orientation.png")
            val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
            for (y in 0..1) for (x in 0..3) bitmap.setPixel(x, y, colors[y * 2 + x / 2])
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
            val preview = ImagePreview.render(file.readBytes(), 256)
            val result = BitmapFactory.decodeByteArray(preview, 0, preview.size)
            assertEquals("orientation=$orientation", expected[orientation - 1], result.getPixel(0, 0))
            assertEquals(if (orientation >= 5) 2 else 4, result.width)
            result.recycle()
        }
    }

}
