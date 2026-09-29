package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentPreviewTest {
    @get:Rule val compose = createComposeRule()
    private val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR4nGP4z8AARAxg8j8AG/ID/fPnS7EAAAAASUVORK5CYII=")
    private val photo = Attachment("image:test", "photo.png", bytes.size, "image/png")
    private fun waitImage(description: String) = compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
    @Test fun `draft thumbnail opens and closes preview without removing the attachment`() {
        var removed = 0
        val requests = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { AttachmentItem(photo, { expanded -> requests.add(expanded); DataResult.Loaded(AttachmentPreview(bytes)) }, { removed++ }) } }
        waitImage("photo.png缩略图")
        compose.onNodeWithText("查看图片").performClick()
        waitImage("photo.png预览")
        compose.onNodeWithText("关闭预览").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("photo.png预览").assertDoesNotExist()
        assertEquals(0, removed)
        assertEquals(listOf(false, true), requests)
        compose.onNodeWithText("移除").performClick()
        assertEquals(1, removed)
    }
    @Test fun `preview failure can retry and history never exposes removal`() {
        var attempts = 0
        compose.setContent { MaterialTheme { AttachmentItem(photo, {
            attempts++
            if (attempts == 1) DataResult.Failed("暂时不可读") else DataResult.Loaded(AttachmentPreview(bytes))
        }) } }
        compose.onNodeWithText("重试预览").performClick()
        waitImage("photo.png缩略图")
        compose.onNodeWithText("预览失败").assertDoesNotExist()
        compose.onNodeWithText("移除").assertDoesNotExist()
        assertEquals(2, attempts)
    }
    @Test fun `text attachments never request image bytes`() {
        compose.setContent { MaterialTheme { AttachmentItem(Attachment("text:test", "notes.txt", 10), { error("Text is not an image") }) } }
        compose.onNodeWithText("notes.txt · 10 B · 已就绪").assertExists()
        compose.onNodeWithText("查看图片").assertDoesNotExist()
    }
    @Test fun `late preview response cannot replace a different attachment`() {
        val attachment = mutableStateOf(photo)
        val delayed = CompletableDeferred<DataResult<AttachmentPreview>>()
        val started = CompletableDeferred<Unit>()
        compose.setContent { MaterialTheme { AttachmentItem(attachment.value, {
            if (attachment.value.ref == photo.ref) { started.complete(Unit); withContext(NonCancellable) { delayed.await() } }
            else DataResult.Failed("new resource unavailable")
        }) } }
        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle { attachment.value = photo.copy(ref = "image:new", name = "new.png") }
        compose.waitForIdle()
        delayed.complete(DataResult.Loaded(AttachmentPreview(bytes)))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("new.png缩略图").assertDoesNotExist()
        compose.onNodeWithText("预览失败").assertExists()
    }
    @Test @Config(sdk = [34], qualifiers = "w640dp-h320dp-land")
    fun `preview close remains reachable in a short landscape window`() {
        compose.setContent { MaterialTheme { AttachmentItem(photo, { DataResult.Loaded(AttachmentPreview(bytes)) }) } }
        waitImage("photo.png缩略图")
        compose.onNodeWithText("查看图片").performClick()
        waitImage("photo.png预览")
        compose.onNodeWithText("关闭预览").assertIsDisplayed().performClick()
        compose.onNodeWithText("查看图片").assertIsDisplayed()
    }

    @Test fun `accessible attachment actions identify and operate on their own file`() {
        val removed = mutableListOf<String>()
        val other = photo.copy(ref = "image:other", name = "diagram.png")
        compose.setContent { MaterialTheme {
            androidx.compose.foundation.layout.Column {
                listOf(photo, other).forEach { item ->
                    AttachmentItem(item, { DataResult.Loaded(AttachmentPreview(bytes)) }, { removed.add(item.ref) })
                }
            }
        } }
        compose.onNodeWithContentDescription("查看图片 diagram.png").performClick()
        waitImage("diagram.png预览")
        compose.onNodeWithText("关闭预览").performClick()
        assertTrue(removed.isEmpty())
        compose.onNodeWithContentDescription("移除附件 photo.png").performClick()
        assertEquals(listOf(photo.ref), removed)
        compose.onNodeWithContentDescription("移除附件 diagram.png").assertHasClickAction()
    }

}
