package com.mobby.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h320dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceInputUiTest {
    @get:Rule val compose = createComposeRule()
    private fun reach(text: String) {
        val node = compose.onNodeWithText(text)
        try { node.assertIsDisplayed() } catch (_: AssertionError) { node.performScrollTo().assertIsDisplayed() }
    }
    @Test fun `large text short window retains editable transcript and explicit cancel without insertion`() {
        var inserts = 0; var dismisses = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { VoiceInputPanel("editing", "第一行\n第二行\n第三行\n第四行\n第五行", "原草稿已改变，未插入文字；请复制转写内容后返回", true,
                    {}, {}, { inserts++ }, {}, { dismisses++ }) }
            }
        }
        reach("放入输入框")
        reach("取消，保留原草稿")
        compose.onNodeWithText("取消，保留原草稿").performClick()
        assertEquals(0, inserts); assertEquals(1, dismisses)
    }
    @Test fun `recording can be ended in short window with large text`() {
        var finishes = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { VoiceInputPanel("recording", "", null, true, {}, { finishes++ }, {}, {}, {}) }
            }
        }
        reach("结束录音")
        compose.onNodeWithText("结束录音").performClick()
        assertEquals(1, finishes)
    }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h640dp-port")
    fun `editing does not insert until explicit confirmation and blank text is disabled`() {
        var text by androidx.compose.runtime.mutableStateOf("初始转写")
        var inserted: String? = null
        compose.setContent { MaterialTheme {
            VoiceInputPanel("editing", text, null, true, { text = it }, {}, { inserted = text }, {}, {})
        } }
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("放入输入框").assertIsNotEnabled()
        org.junit.Assert.assertNull(inserted)
        compose.onNode(hasSetTextAction()).performTextReplacement("校对后的内容")
        org.junit.Assert.assertNull(inserted)
        reach("放入输入框")
        compose.onNodeWithText("放入输入框").performClick()
        assertEquals("校对后的内容", inserted)
    }

}
