package com.mobby.interaction.data

import android.content.Context
import com.mobby.interaction.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Only non-sensitive appearance preferences; gateway configuration remains in encrypted storage. */
internal class InteractionPreferences(context: Context) : PreferencePort {
    private val preferences = context.applicationContext.getSharedPreferences("interaction-ui", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(runCatching { Appearance.valueOf(preferences.getString("appearance", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM))
    override val appearance = current.asStateFlow()
    override suspend fun setAppearance(value: Appearance): OperationResult = withContext(Dispatchers.IO) {
        if (preferences.edit().putString("appearance", value.name).commit()) {
            current.value = value
            OperationResult.Done
        } else OperationResult.Failed("外观设置保存失败，请重试")
    }
}
