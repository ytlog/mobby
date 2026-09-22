package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.github.ytlog.mobby.android.interaction.domain.Conversation
import com.github.ytlog.mobby.android.interaction.domain.ConversationId

internal data class ComposerState(val conversation: ConversationId? = null, val value: TextFieldValue = TextFieldValue()) {
    fun insertVoice(original: ComposerState, text: String): ComposerState? {
        if (conversation == null || conversation != original.conversation || value.text != original.value.text) return null
        val range = original.value.selection
        return copy(value = TextFieldValue(value.text.replaceRange(range.min, range.max, text), TextRange(range.min + text.length)))
    }
    fun synchronize(remote: Conversation): ComposerState {
        val selection = TextRange(remote.draft.selectionStart, remote.draft.selectionEnd)
        // Runtime/anchor updates must not terminate an in-progress IME composing region.
        if (conversation == remote.id && value.text == remote.draft.text && value.selection == selection) return this
        return ComposerState(remote.id, TextFieldValue(remote.draft.text, selection))
    }
}
