package com.mobby.interaction.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mobby.interaction.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraCaptureStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() {
        context.getSharedPreferences("camera-capture", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "captures").deleteRecursively()
        // Robolectric changes filesDir between tests; AndroidX caches roots per authority.
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }
    private fun take(capture: CameraCapture, width: Int = 3200, height: Int = 1600) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(Uri.parse(capture.captureUri))!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
    }
    @Test fun `cancellation clears only capture files and restored session keeps original conversation`() = runBlocking<Unit> {
        val store = CameraCaptureStore(context)
        val capture = store.begin("original-conversation", "default")
        assertEquals(capture, CameraCaptureStore(context).current())
        assertTrue(capture.captureUri.startsWith("content://${context.packageName}.captures/"))
        assertNull(store.finish(capture.id, false)); assertNull(store.current())
        assertTrue(File(context.filesDir, "captures/output").listFiles()!!.isEmpty())
    }
    @Test fun `camera result is normalized and remains reviewable until explicit import`() = runBlocking<Unit> {
        val store = CameraCaptureStore(context); val capture = store.begin("original", "default")
        take(capture)
        val review = store.finish(capture.id, true)!!
        assertEquals(CapturePhase.REVIEW, review.phase); assertEquals("original", review.conversation)
        val bytes = context.contentResolver.openInputStream(Uri.parse(review.attachmentUri))!!.use { it.readBytes() }
        assertTrue(bytes.size <= 2 * 1024 * 1024)
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertTrue(image.width <= 2048); assertEquals(image.width / 2, image.height); image.recycle()
        assertTrue(store.preview(review.id).isNotEmpty())
        store.retain(emptySet()); assertEquals(review, store.current())
        store.importing(review.attachmentUri!!)
        store.retain(setOf(review.attachmentUri!!)); assertNotNull(CameraCaptureStore(context).current())
        store.retain(emptySet()); assertNull(store.current())
    }
    @Test fun `pending import promotes restored review and late old camera result cannot alter a new capture`() = runBlocking<Unit> {
        val store = CameraCaptureStore(context); val old = store.begin("old", "default")
        store.finish(old.id, false)
        val current = store.begin("new", "default"); take(current, 8, 4)
        assertEquals(current, store.finish(old.id, true))
        val review = store.finish(current.id, true)!!
        CameraCaptureStore(context).retain(setOf(review.attachmentUri!!))
        assertEquals(CapturePhase.IMPORTING, store.current()!!.phase)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.discard(current.id) } }
    }
    @Test fun `empty or corrupt camera output is an error that can be discarded`() = runBlocking<Unit> {
        val store = CameraCaptureStore(context); val capture = store.begin("conversation", "default")
        val result = store.finish(capture.id, true)!!
        assertEquals(CapturePhase.ERROR, result.phase); assertNotNull(result.error); assertNull(result.attachmentUri)
        store.discard(capture.id); assertNull(store.current())
    }
    @Test fun `capture provider cannot expose conversation files outside its directory`() {
        val privateFile = File(context.filesDir, "private-note.txt").apply { writeText("private") }
        try { assertThrows(IllegalArgumentException::class.java) { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.captures", privateFile) } }
        finally { privateFile.delete() }
    }
    @Test fun `restart completes a persisted cancellation and removes only orphan capture directories`() = runBlocking<Unit> {
        val store = CameraCaptureStore(context); val capture = store.begin("conversation", "default")
        val prefs = context.getSharedPreferences("camera-capture", Context.MODE_PRIVATE)
        val record = org.json.JSONObject(prefs.getString("session", null)!!).put("phase", "DISCARDING")
        prefs.edit().putString("session", record.toString()).commit()
        val orphan = File(context.filesDir, "captures/output/00000000-0000-0000-0000-000000000000").apply { mkdirs() }
        File(orphan, "capture.jpg").writeText("unfinished")
        CameraCaptureStore(context).retain(emptySet())
        assertNull(CameraCaptureStore(context).current())
        assertFalse(orphan.exists())
        assertFalse(File(context.filesDir, "captures/output/${capture.id}").exists())
    }

    @Test fun `mismatched recovery metadata never turns a real capture into disposable orphan data`() = runBlocking<Unit> {
        val store = CameraCaptureStore(context); val capture = store.begin("conversation", "default")
        take(capture, 8, 4)
        val prefs = context.getSharedPreferences("camera-capture", Context.MODE_PRIVATE)
        val record = org.json.JSONObject(prefs.getString("session", null)!!).put("id", "00000000-0000-0000-0000-000000000000")
        prefs.edit().putString("session", record.toString()).commit()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { CameraCaptureStore(context).current() } }
        assertTrue(File(context.filesDir, "captures/output/${capture.id}/capture.jpg").length() > 0)
    }

}
