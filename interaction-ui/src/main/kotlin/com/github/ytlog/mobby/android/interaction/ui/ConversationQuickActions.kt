package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

private data class QuickAction(val ref: String, val label: String, val icon: AppGlyph)

private val quickActions get() = listOf(
    QuickAction("plugin:device:screen", AppStrings.usePhoneQuickAction, AppIcons.Phone),
    QuickAction("plugin:device:media", AppStrings.viewPhotosQuickAction, AppIcons.Photo),
    QuickAction("plugin:device:storage", AppStrings.browseFilesQuickAction, AppIcons.Folder),
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun ConversationQuickActions(selected: Set<String>, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        quickActions.forEach { action ->
            val chosen = action.ref in selected
            val ink = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Surface(
                modifier = Modifier.clickable(enabled = !chosen) { onSelect(action.ref) },
                shape = RoundedCornerShape(16.dp),
                color = if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else raisedColor(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (chosen) 0.45f else 0.32f)),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppIcon(if (chosen) AppIcons.Check else action.icon, null, Modifier.size(18.dp), tint = ink)
                    Text(action.label, color = ink, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
