package com.mobby.runtime.android

import com.mobby.runtime.api.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ImageResourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val workspace = WorkspaceRef("default")
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR4nGP4z8AARAxg8j8AG/ID/fPnS7EAAAAASUVORK5CYII=")
    @Test fun `image attachments retain bytes and reject a different workspace`() {
        val store = ResourceStore(temporary.newFolder())
        val other = WorkspaceRef("local-12345678-1234-1234-1234-123456789abc")
        val image = store.save(ImportResourceRequest(other, "photo.png", png))
        assertArrayEquals(png, store.image(image.ref, other).bytes)
        assertThrows(Exception::class.java) { store.image(image.ref, workspace) }
    }
    @Test fun `selected PNG imports as an image instead of invalid UTF8`() {
        val root = temporary.newFolder()
        val result = ResourceStore(root).save(ImportResourceRequest(workspace, "photo.png", png))
        assertEquals("image/png", result.mediaType)
        assertEquals(png.size, result.sizeBytes)
        assertEquals(result, ResourceStore(root).save(ImportResourceRequest(workspace, "photo.png", png)))
    }
    @Test fun `image reference reopens intact and mixed input retains image order`() {
        val root = temporary.newFolder(); val store = ResourceStore(root)
        val photo = store.save(ImportResourceRequest(workspace, "photo.png", png))
        val text = store.save(ImportResourceRequest(workspace, "notes.txt", "exact text".toByteArray()))
        val restarted = ResourceStore(root)
        assertEquals(photo, restarted.summary(photo.ref, workspace))
        assertArrayEquals(png, restarted.image(photo.ref, workspace).bytes)
        val parts = listOf(InputPart.Text("describe"), InputPart.Resource(photo.ref), InputPart.Resource(text.ref))
        val prepared = restarted.prepare(parts, workspace)
        assertEquals("photo.png", prepared.images.single().name)
        assertTrue(prepared.prompt.contains("exact text"))
        assertThrows(Exception::class.java) { restarted.prompt(parts, workspace) }
        assertThrows(Exception::class.java) { restarted.prepare(List(5) { InputPart.Resource(photo.ref) }, workspace) }
        val imageFile = java.io.File(root, photo.ref.value.substringAfter(':'))
        imageFile.writeText("modified")
        assertThrows(Exception::class.java) { restarted.image(photo.ref, workspace) }
    }
    @Test fun `corrupt oversized and excessive dimension images are rejected without files`() {
        val root = temporary.newFolder(); val store = ResourceStore(root)
        for (bytes in listOf(png.copyOf(24), png.copyOf().apply { this[45] = (this[45].toInt() xor 1).toByte() }, png.copyOf(ResourceStore.MAX_IMAGE_BYTES + 1))) {
            assertThrows(Exception::class.java) { store.save(ImportResourceRequest(workspace, "bad.png", bytes)) }
        }
        val bitmap = android.graphics.Bitmap.createBitmap(4097, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        assertThrows(Exception::class.java) { store.save(ImportResourceRequest(workspace, "wide.png", output.toByteArray())) }
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun `JPEG bytes are preserved without transcoding`() {
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output); bitmap.recycle()
        val bytes = output.toByteArray(); val store = ResourceStore(temporary.newFolder())
        val saved = store.save(ImportResourceRequest(workspace, "photo.jpg", bytes))
        assertEquals("image/jpeg", saved.mediaType)
        assertArrayEquals(bytes, store.image(saved.ref, workspace).bytes)
    }

}
