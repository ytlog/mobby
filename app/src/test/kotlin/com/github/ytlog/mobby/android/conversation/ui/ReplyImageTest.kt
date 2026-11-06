package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
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

    @Test fun `tapping a reply image opens a closable preview`() {
        compose.setContent { MaterialTheme { ReplyContent("![结果](a.png)", images = { _, _ -> DataResult.Loaded(AttachmentPreview(bytes)) }) { _, _ -> } } }
        waitFor(hasContentDescription("结果预览"))
        compose.onNodeWithContentDescription("结果预览").performClick()
        compose.onNodeWithText("关闭预览").assertIsDisplayed().performClick()
        compose.onNodeWithText("关闭预览").assertDoesNotExist()
    }

    @Test fun `network images wait for the user before downloading`() {
        val requests = mutableListOf<String>()
        compose.setContent { MaterialTheme { ReplyContent("![图表](https://img.example/chart.png)", images = { source, _ ->
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
