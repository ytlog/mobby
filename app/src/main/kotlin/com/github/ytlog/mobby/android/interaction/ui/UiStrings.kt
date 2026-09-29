package com.github.ytlog.mobby.android.interaction.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.ytlog.mobby.android.localization.AppLanguage
import com.github.ytlog.mobby.android.localization.StringCatalog

/** Reads are tracked by Compose, including labels produced by ordinary Kotlin helpers. */
object UiStrings : StringCatalog() {
    override val language: AppLanguage get() = LanguagePreferences.current
}

/** Persist first: a failed write must not appear to have changed the app language. */
object LanguagePreferences {
    private const val FILE = "mobby.language"
    private const val KEY = "language"
    var current by mutableStateOf(AppLanguage.CHINESE)
        private set

    fun initialize(context: Context) = restore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    internal fun restore(preferences: SharedPreferences) {
        update(AppLanguage.fromTag(preferences.getString(KEY, null)))
    }

    fun select(context: Context, language: AppLanguage): Boolean =
        select(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), language)

    internal fun select(preferences: SharedPreferences, language: AppLanguage): Boolean {
        if (!preferences.edit().putString(KEY, language.tag).commit()) return false
        update(language)
        return true
    }

    private fun update(language: AppLanguage) {
        AppLanguage.current = language
        current = language
    }
}
