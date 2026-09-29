package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.github.ytlog.mobby.android.conversation.domain.*
import org.junit.Assert.*
import org.junit.Test

class ComposerStateTest {
    private val conversation = Conversation(ConversationId("one"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"), draft = Draft(text = "中文", selectionStart = 2, selectionEnd = 2))
    @Test fun `runtime updates preserve IME composition when draft is unchanged`() {
        val composing = ComposerState(conversation.id, TextFieldValue("中文", TextRange(2), TextRange(0, 2)))
        assertEquals(TextRange(0, 2), composing.synchronize(conversation).value.composition)
    }
    @Test fun `voice replaces original reversed selection and positions caret after transcript`() {
        val original = ComposerState(conversation.id, TextFieldValue("开始替换结束", TextRange(4, 2)))
        val inserted = original.insertVoice(original, "语音")!!
        assertEquals("开始语音结束", inserted.value.text)
        assertEquals(TextRange(4), inserted.value.selection)
    }
    @Test fun `late voice result never overwrites newer draft or another conversation`() {
        val original = ComposerState(conversation.id, TextFieldValue("原文"))
        assertNull(original.copy(value = TextFieldValue("新文")).insertVoice(original, "语音"))
        assertNull(original.copy(conversation = ConversationId("other")).insertVoice(original, "语音"))
    }
    @Test fun `accepted clear and conversation switch replace old composition`() {
        val composing = ComposerState(conversation.id, TextFieldValue("中文", TextRange(2), TextRange(0, 2)))
        assertEquals("", composing.synchronize(conversation.copy(draft = Draft())).value.text)
        assertNull(composing.synchronize(conversation.copy(id = ConversationId("other"))).value.composition)
    }
}
