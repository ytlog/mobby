package com.github.ytlog.mobby.android.runtime.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.ytlog.mobby.android.runtime.api.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.URI
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyImagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var home: File
    private lateinit var workspace: File
    @Before fun setUp() {
        home = temporary.newFolder("home")
        workspace = File(home, "workspace").apply { mkdir() }
    }
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
    }
    private fun edge(preview: ReplyImagePreview): Int {
        val bytes = (preview as ReplyImagePreview.Ready).bytes
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return maxOf(decoded.width, decoded.height).also { decoded.recycle() }
    }
    private fun rejected(preview: ReplyImagePreview) = (preview as ReplyImagePreview.Rejected).problem

    @Test fun `workspace images resolve from relative, home and absolute paths`() {
        File(workspace, "out").mkdir()
        File(workspace, "out/edited photo.png").writeBytes(png(2000, 1000))
        val images = ReplyImages(home) { _, _ -> fail("local images must not download"); ByteArray(0) }
        for (source in listOf("out/edited photo.png", "out/edited%20photo.png", "./out/edited photo.png", "~/workspace/out/edited photo.png",
            File(workspace, "out/edited photo.png").absolutePath, File(workspace, "out/edited photo.png").toURI().toString())) {
            assertEquals(source, 256, edge(images.preview(workspace, source, false)))
            assertEquals(source, 1024, edge(images.preview(workspace, source, true)))
        }
    }

    @Test fun `generated images larger than attachment limits still preview`() {
        File(workspace, "big.png").writeBytes(png(4800, 1200))
        assertEquals(1024, edge(ReplyImages(home) { _, _ -> ByteArray(0) }.preview(workspace, "big.png", true)))
    }

    @Test fun `deliveries return the original bytes and only read workspace files`() {
        val original = png(2000, 1000)
        val file = File(workspace, "result.png").apply { writeBytes(original) }
        val report = File(workspace, "report.txt").apply { writeText("report") }
        val outside = File(home, "secret.png").apply { writeBytes(original) }
        Files.createSymbolicLink(File(workspace, "outside.png").toPath(), outside.toPath())
        val images = ReplyImages(home) { _, _ -> error("Delivery must not download") }
        val result = images.original(workspace, file.toURI().toString())
        assertEquals("image/png", result.mediaType)
        assertArrayEquals(original, result.bytes)
        assertArrayEquals(report.readBytes(), images.original(workspace, "report.txt").bytes)
        for (source in listOf("../secret.png", "outside.png", "https://example.com/result.png")) {
            assertThrows(ReplyImages.Rejected::class.java) { images.original(workspace, source) }
        }
    }

    @Test fun `paths outside the workspace are refused even through links`() {
        val secret = File(home, "secret.png").apply { writeBytes(png(4, 4)) }
        Files.createSymbolicLink(File(workspace, "link.png").toPath(), secret.toPath())
        val images = ReplyImages(home) { _, _ -> ByteArray(0) }
        for (source in listOf("../secret.png", "~/secret.png", secret.absolutePath, "link.png", "/etc/hosts")) {
            assertEquals(source, ReplyImageProblem.OUTSIDE_WORKSPACE, rejected(images.preview(workspace, source, false)))
        }
    }

    @Test fun `missing, non image and oversized files report their own problem`() {
        File(workspace, "notes.png").writeText("not an image")
        File(workspace, "huge.png").outputStream().use { out -> out.write(png(4, 4)); out.write(ByteArray(ReplyImages.MAX_BYTES)) }
        val images = ReplyImages(home) { _, _ -> ByteArray(0) }
        assertEquals(ReplyImageProblem.MISSING, rejected(images.preview(workspace, "gone.png", false)))
        assertEquals(ReplyImageProblem.NOT_IMAGE, rejected(images.preview(workspace, "notes.png", false)))
        assertEquals(ReplyImageProblem.TOO_LARGE, rejected(images.preview(workspace, "huge.png", false)))
        assertEquals(ReplyImageProblem.MISSING, rejected(images.preview(workspace, ".", false)))
    }

    @Test fun `only https is downloaded and download failures are not shown as images`() {
        val requested = mutableListOf<URI>()
        val bytes = png(300, 200)
        val images = ReplyImages(home) { uri, limit -> requested += uri; assertEquals(ReplyImages.MAX_BYTES, limit); if (uri.host == "fails.example") throw java.io.IOException("offline") else bytes }
        assertEquals(256, edge(images.preview(workspace, "https://img.example/a.png", false)))
        assertEquals(ReplyImageProblem.DOWNLOAD_FAILED, rejected(images.preview(workspace, "https://fails.example/a.png", false)))
        for (source in listOf("http://img.example/a.png", "ftp://img.example/a.png", "content://media/1", "javascript:alert(1)")) {
            assertEquals(source, ReplyImageProblem.UNSUPPORTED_SOURCE, rejected(images.preview(workspace, source, false)))
        }
        assertEquals(listOf(URI("https://img.example/a.png"), URI("https://fails.example/a.png")), requested)
    }

    @Test fun `downloaded bytes that are not an image are refused`() {
        val images = ReplyImages(home) { _, _ -> "<html>".toByteArray() }
        assertEquals(ReplyImageProblem.NOT_IMAGE, rejected(images.preview(workspace, "https://img.example/page", false)))
    }

    @Test fun `inline base64 data images decode without network`() {
        val encoded = java.util.Base64.getEncoder().encodeToString(png(40, 20))
        val images = ReplyImages(home) { _, _ -> fail("data images must not download"); ByteArray(0) }
        assertEquals(40, edge(images.preview(workspace, "data:image/png;base64,$encoded", false)))
        assertEquals(ReplyImageProblem.NOT_IMAGE, rejected(images.preview(workspace, "data:image/png;base64,bm90IGFuIGltYWdl", false)))
        assertEquals(ReplyImageProblem.UNSUPPORTED_SOURCE, rejected(images.preview(workspace, "data:text/html;base64,PGI+", false)))
    }
}
