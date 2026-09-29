package com.github.ytlog.mobby.android.interaction.ui

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraCaptureUiTest {
    @get:Rule val compose = createComposeRule()
    private val photo = CameraCapture("capture", "original", "default", "content://capture/raw", "content://capture/photo", CapturePhase.REVIEW)
    private val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR4nGP4z8AARAxg8j8AG/ID/fPnS7EAAAAASUVORK5CYII=")
    @Test fun `confirmation waits for a visible preview and cancellation never confirms`() {
        val loaded = CompletableDeferred<DataResult<AttachmentPreview>>()
        var confirmed = 0; var discarded = 0
        compose.setContent { MaterialTheme { CameraReviewDialog(photo, false, { loaded.await() }, { discarded++ }, { confirmed++ }, {}) } }
        compose.onNodeWithText("加入草稿").assertIsNotEnabled()
        loaded.complete(DataResult.Loaded(AttachmentPreview(bytes)))
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("拍摄照片预览").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("加入草稿").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, discarded); assertEquals(0, confirmed)
    }
    @Test fun `failed preview can retry before confirmation`() {
        var attempts = 0; var confirmed = 0
        compose.setContent { MaterialTheme { CameraReviewDialog(photo, false, {
            if (++attempts == 1) DataResult.Failed("unavailable") else DataResult.Loaded(AttachmentPreview(bytes))
        }, {}, { confirmed++ }, {}) } }
        compose.onNodeWithText("加入草稿").assertIsNotEnabled()
        compose.onNodeWithText("重试预览").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("拍摄照片预览").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("加入草稿").assertIsDisplayed().performClick()
        assertEquals(1, confirmed)
    }
    @Test fun `camera launch grants only the specific output URI and requests full capture`() {
        val uri = Uri.parse("content://test.captures/camera/output.jpg")
        val intent = CapturePictureContract().createIntent(RuntimeEnvironment.getApplication(), uri)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        assertEquals(uri, intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT))
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
    @Test fun `confirmation failure is visible inside the modal and busy actions stay disabled`() {
        compose.setContent { MaterialTheme { CameraReviewDialog(photo, true, { DataResult.Loaded(AttachmentPreview(bytes)) }, {}, {}, {}, problem = "原会话已归档，请恢复后重试") } }
        compose.onNodeWithText("原会话已归档，请恢复后重试").assertIsDisplayed()
        compose.onNodeWithText("加入草稿").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }

    @Test @Config(sdk = [34], qualifiers = "w640dp-h320dp-land")
    fun `short camera preview keeps confirmation reachable and conversion notice readable`() {
        compose.setContent { MaterialTheme { CameraReviewDialog(photo, false, { DataResult.Loaded(AttachmentPreview(bytes)) }, {}, {}, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("拍摄照片预览").fetchSemanticsNodes().isNotEmpty() }
        val notice = compose.onNodeWithText("照片已转为最长边不超过 2048 像素的 JPEG。确认后加入发起拍照的会话，尚未发送。")
        try { notice.assertIsDisplayed() } catch (_: AssertionError) { notice.performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("加入草稿").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test @Config(sdk = [34], qualifiers = "w640dp-h320dp-land")
    fun `large font camera review keeps instructions and actions visible`() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                CameraReviewDialog(photo, false, { DataResult.Loaded(AttachmentPreview(bytes)) }, {}, {}, {})
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("拍摄照片预览").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("照片已转为最长边不超过 2048 像素的 JPEG。确认后加入发起拍照的会话，尚未发送。")
            .assertIsDisplayed()
        compose.onNodeWithText("加入草稿").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("取消").assertIsDisplayed().assertIsEnabled()
    }

}
