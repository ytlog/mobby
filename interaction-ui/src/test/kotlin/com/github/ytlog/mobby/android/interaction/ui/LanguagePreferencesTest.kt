package com.github.ytlog.mobby.android.interaction.ui

import android.content.Context
import android.content.SharedPreferences
import com.github.ytlog.mobby.android.localization.AppLanguage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguagePreferencesTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("language-test", Context.MODE_PRIVATE)
    @After fun reset() { prefs.edit().clear().commit(); LanguagePreferences.restore(prefs) }

    @Test fun `language is restored after a fresh initialization`() {
        LanguagePreferences.restore(prefs)
        assertEquals("设置", UiStrings.settings)
        assertTrue(LanguagePreferences.select(prefs, AppLanguage.ENGLISH))
        LanguagePreferences.restore(RuntimeEnvironment.getApplication().getSharedPreferences("empty", Context.MODE_PRIVATE))
        assertEquals("设置", UiStrings.settings)
        LanguagePreferences.restore(prefs)
        assertEquals("Settings", UiStrings.settings)
        assertEquals(AppLanguage.ENGLISH, AppLanguage.current)
    }

    @Test fun `failed persistence leaves visible language unchanged`() {
        LanguagePreferences.restore(prefs)
        val broken = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by prefs.edit() {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                override fun commit() = false
            }
        }
        assertFalse(LanguagePreferences.select(broken, AppLanguage.ENGLISH))
        assertEquals("设置", UiStrings.settings)
        assertEquals(AppLanguage.CHINESE, AppLanguage.current)
    }
}
