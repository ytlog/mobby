package com.mobby.interaction.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mobby.interaction.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InteractionPreferencesTest {
    @Test fun `theme survives a new application adapter and can return to system`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = InteractionPreferences(context)
        assertEquals(OperationResult.Done, original.setAppearance(Appearance.DARK))
        val restored = InteractionPreferences(context)
        assertEquals(Appearance.DARK, restored.appearance.value)
        restored.setAppearance(Appearance.SYSTEM)
        assertEquals(Appearance.SYSTEM, InteractionPreferences(context).appearance.value)
    }
    @Test fun `unknown stored appearance falls back without clearing other preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = context.getSharedPreferences("interaction-ui", Context.MODE_PRIVATE)
        store.edit().putString("appearance", "future-value").putString("other", "keep").commit()
        assertEquals(Appearance.SYSTEM, InteractionPreferences(context).appearance.value)
        assertEquals("keep", store.getString("other", null))
    }
}
