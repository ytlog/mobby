package com.github.ytlog.mobby.android.localmodel

import android.content.Context
import com.github.ytlog.mobby.android.localization.AppLanguage

internal enum class ModelDownloadSource(val id: String) {
    MODELSCOPE("modelscope"),
    HUGGING_FACE("huggingface"),
    HF_MIRROR("hf-mirror");

    companion object {
        fun fromId(id: String?): ModelDownloadSource? = values().firstOrNull { it.id == id }
        fun defaultFor(language: AppLanguage): ModelDownloadSource =
            if (language == AppLanguage.CHINESE) MODELSCOPE else HUGGING_FACE
    }
}

/** Only the page reads this preference; the service receives a source with each HTTP request. */
internal class ModelDownloadSourcePreference(context: Context) {
    private val prefs = context.getSharedPreferences("local_model_options", Context.MODE_PRIVATE)

    fun selected(language: AppLanguage): ModelDownloadSource =
        ModelDownloadSource.fromId(prefs.getString("download_source", null)) ?: ModelDownloadSource.defaultFor(language)

    fun select(source: ModelDownloadSource): Boolean =
        prefs.edit().putString("download_source", source.id).commit()
}
