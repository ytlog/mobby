package com.github.ytlog.mobby.android.conversation.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ConversationPreferencesTest {
    @Test fun `theme survives a new application adapter and can return to system`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = ConversationPreferences(context)
        assertEquals(OperationResult.Done, original.setAppearance(Appearance.DARK))
        val restored = ConversationPreferences(context)
        assertEquals(Appearance.DARK, restored.appearance.value)
        restored.setAppearance(Appearance.SYSTEM)
        assertEquals(Appearance.SYSTEM, ConversationPreferences(context).appearance.value)
    }
    @Test fun `unknown stored appearance falls back without clearing other preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = context.getSharedPreferences("interaction-ui", Context.MODE_PRIVATE)
        store.edit().putString("appearance", "future-value").putString("other", "keep").commit()
        assertEquals(Appearance.SYSTEM, ConversationPreferences(context).appearance.value)
        assertEquals("keep", store.getString("other", null))
    }
}
