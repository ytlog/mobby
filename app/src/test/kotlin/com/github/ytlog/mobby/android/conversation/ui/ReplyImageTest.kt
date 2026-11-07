package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.domain.*
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
class ReplyImageTest {
    @get:Rule val compose = createComposeRule()
    private val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR4nGP4z8AARAxg8j8AG/ID/fPnS7EAAAAASUVORK5CYII=")
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    @Test fun `workspace image in a reply is shown as an image between its text`() {
        val requests = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { MaterialTheme { ReplyContent("改好了：![修好的照片](out/photo.png) 请查看", images = { source, expanded ->
            requests += source to expanded; DataResult.Loaded(AttachmentPreview(bytes))
        }) { _, _ -> } } }
        waitFor(hasContentDescription("修好的照片预览"))
        compose.onNodeWithText("改好了：").assertExists()
        compose.onNodeWithText("请查看").assertExists()
        compose.onNodeWithText("[图片：", substring = true).assertDoesNotExist()
        assertEquals(listOf("out/photo.png" to true), requests)
    }

    @Test fun `reply shortens visible absolute paths without changing the image destination`() {
        val path = "/data/user/0/com.github.ytlog.mobby.android/files/libtermux/home/workspace/out/photo.png"
        val requests = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            ReplyContent("已保存到 `$path`\n\n![$path](file://$path)", images = { source, _ ->
                requests += source
                DataResult.Loaded(AttachmentPreview(bytes))
            }) { _, _ -> }
        } }
        waitFor(hasText("已保存到 ~/workspace/out/photo.png"))
        waitFor(hasContentDescription("~/workspace/out/photo.png预览"))
        compose.onNodeWithContentDescription("~/workspace/out/photo.png预览").performClick()
        compose.onNodeWithText("~/workspace/out/photo.png").assertDoesNotExist()
        compose.onNodeWithContentDescription(UiStrings.closePreview).assertIsDisplayed()
        assertEquals(listOf("file://$path"), requests)
    }

    @Test fun `tapping a reply image opens a zoomable preview without reloading`() {
        var loads = 0
        compose.setContent { MaterialTheme { ReplyContent("![结果](a.png)", images = { _, _ ->
            loads++
            DataResult.Loaded(AttachmentPreview(bytes))
        }) { _, _ -> } } }
        waitFor(hasContentDescription("结果预览"))
        compose.onNodeWithContentDescription("结果预览").performClick()
        waitFor(hasText("100%"))
        compose.onNodeWithContentDescription(UiStrings.zoomInImage).assertIsDisplayed().performClick()
        compose.onNodeWithText("150%").assertIsDisplayed()
        compose.onNodeWithContentDescription(UiStrings.zoomOutImage).assertIsDisplayed().performClick()
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNode(hasContentDescription("结果预览") and hasAnyAncestor(isDialog())).performTouchInput {
            val near = Offset(width * 0.1f, 0f)
            val far = Offset(width * 0.3f, 0f)
            pinch(center - near, center + near, center - far, center + far)
        }
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithContentDescription(UiStrings.closePreview).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(UiStrings.closePreview).assertDoesNotExist()
        assertEquals(1, loads)
    }

    @Test fun `linked and formatted images display with their surrounding text`() {
        val requests = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            ReplyContent("**修改前 [![原图](before.png)](https://example.com) 修改后 ![结果](after.png)**", images = { source, _ ->
                requests += source
                DataResult.Loaded(AttachmentPreview(bytes))
            }) { _, _ -> }
        } }
        waitFor(hasContentDescription("原图预览"))
        waitFor(hasContentDescription("结果预览"))
        compose.onNodeWithText("修改前").assertExists()
        compose.onNodeWithText("修改后").assertExists()
        assertEquals(setOf("before.png", "after.png"), requests.toSet())
    }

    @Test fun `result list gathers only explicit local file references`() {
        val files = ReplyMarkdown.resultFiles(listOf(
            "[![Photo](./out/a%20b.png)](out/a%20b.png) [report](docs/report.pdf)",
            "[archive](file:///workspace/out/code.zip) [remote](https://example.com/a.png) [jump](#heading)\n\n`![example](fake.png)`",
        ))
        assertEquals(listOf("a b.png", "report.pdf", "code.zip"), files.map { it.name })
        assertEquals(listOf(true, false, false), files.map { it.image })
    }

    @Test fun `continue editing hands the original file reference to the draft action`() {
        val path = "out/crop.png"
        var added: String? = null
        val access = ResultFileAccess(read = { error("Edit should use the draft import path") }, edit = { source ->
            added = source
            DataResult.Loaded(Attachment("test-ref", "crop.png", bytes.size, "image/png"))
        })
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalResultFileAccess provides access) {
                ReplyContent("![结果]($path)", images = { _, _ -> DataResult.Loaded(AttachmentPreview(bytes)) }) { _, _ -> }
            }
        } }
        waitFor(hasContentDescription("结果预览"))
        compose.onNodeWithContentDescription("结果预览").performClick()
        compose.onNodeWithText(UiStrings.saveResultImage).assertExists()
        compose.onNodeWithText(UiStrings.shareResultFile).assertExists()
        compose.onNodeWithText(UiStrings.editResultImage).performClick()
        compose.onNodeWithContentDescription(UiStrings.closePreview).assertDoesNotExist()
        assertEquals(path, added)
    }

    @Test fun `network images wait for the user before downloading`() {
        val requests = mutableListOf<String>()
        compose.setContent { MaterialTheme { ReplyContent("[![图表](https://img.example/chart.png)](https://example.com)", images = { source, _ ->
            requests += source; DataResult.Loaded(AttachmentPreview(bytes))
        }) { _, _ -> } } }
        waitFor(hasText("img.example", substring = true))
        compose.waitForIdle()
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("加载网络图片").performClick()
        waitFor(hasContentDescription("图表预览"))
        assertEquals(listOf("https://img.example/chart.png"), requests)
    }

    @Test fun `unreadable reply image states why and can retry`() {
        var attempts = 0
        compose.setContent { MaterialTheme { ReplyContent("![结果](a.png)", images = { _, _ ->
            attempts++
            if (attempts == 1) DataResult.Failed("图片不在当前工作区内") else DataResult.Loaded(AttachmentPreview(bytes))
        }) { _, _ -> } } }
        waitFor(hasText("图片不在当前工作区内"))
        compose.onNodeWithText("重试预览").performClick()
        waitFor(hasContentDescription("结果预览"))
    }

    @Test fun `replies without an image source keep the text form`() {
        compose.setContent { MaterialTheme { ReplyContent("![结果](a.png)") { _, _ -> } } }
        waitFor(hasText("[图片：结果]"))
    }
}
