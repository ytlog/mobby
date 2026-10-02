package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

private data class PluginOption(val ref: String, val label: String, val icon: AppGlyph)

private fun pluginOptions(tablet: Boolean) = listOf(
    PluginOption("plugin:device:screen", if (tablet) AppStrings.useTabletPlugin else AppStrings.usePhonePlugin, if (tablet) AppIcons.Tablet else AppIcons.Phone),
    PluginOption("plugin:device:media", AppStrings.viewPhotosPlugin, AppIcons.Photo),
    PluginOption("plugin:device:storage", AppStrings.browseFilesPlugin, AppIcons.Folder),
    PluginOption("plugin:device:camera", AppStrings.useCameraPlugin, AppIcons.Camera),
)

@Composable internal fun EmptyConversationPlugins(selected: Set<String>, onSelect: (String) -> Unit) {
    val tablet = isTabletDisplay()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pluginOptions(tablet).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { option ->
                    val chosen = option.ref in selected
                    val ink = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 62.dp).testTag(option.ref)
                            .clickable(enabled = !chosen, role = Role.Button) { onSelect(option.ref) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else raisedColor(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (chosen) 0.45f else 0.32f)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            AppIcon(if (chosen) AppIcons.Check else option.icon, null, Modifier.size(18.dp), tint = ink)
                            Text(option.label, color = ink, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
