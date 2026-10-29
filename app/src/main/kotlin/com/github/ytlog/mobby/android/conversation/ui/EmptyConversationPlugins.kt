package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

private data class PluginOption(val ref: String, val label: String, val icon: AppGlyph)

private fun pluginOptions(tablet: Boolean) = listOf(
    PluginOption("plugin:device:screen", if (tablet) AppStrings.useTabletPlugin else AppStrings.usePhonePlugin, if (tablet) AppIcons.Tablet else AppIcons.Phone),
    PluginOption(PROGRAM_DEVELOPMENT, AppStrings.programDevelopment, AppIcons.Terminal),
    PluginOption("plugin:device:storage", AppStrings.browseFilesPlugin, AppIcons.Folder),
    PluginOption("plugin:device:media", AppStrings.viewPhotosPlugin, AppIcons.Photo),
)

/** Installed programs for the empty page and how to open them; the drawer uses the same shortcuts. */
class EmptyPagePrograms(val shortcuts: List<ProgramShortcut>, val openPage: (String) -> Unit, val openDetails: (String) -> Unit, val openAll: () -> Unit)

/** The four entries share one row; when programs are installed, up to three of them follow below. */
@Composable internal fun EmptyConversationPlugins(selected: Set<String>, programs: EmptyPagePrograms? = null, onSelect: (String) -> Unit) {
    val tablet = isTabletDisplay()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pluginOptions(tablet).forEach { option -> CompactPlugin(option, option.ref in selected, onSelect, Modifier.weight(1f)) }
        }
        if (programs != null && programs.shortcuts.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                programs.shortcuts.take(3).forEach { shortcut ->
                    ProgramTile(shortcut.name.take(1).uppercase(), shortcut.name, Modifier.weight(1f)) {
                        if (shortcut.opensPage) programs.openPage(shortcut.id) else programs.openDetails(shortcut.id)
                    }
                }
                ProgramTile(null, AppStrings.allProgramsShort, Modifier.weight(1f), onClick = programs.openAll)
                repeat(3 - programs.shortcuts.take(3).size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable private fun CompactPlugin(option: PluginOption, chosen: Boolean, onSelect: (String) -> Unit, modifier: Modifier) {
    val ink = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = { onSelect(option.ref) }, enabled = !chosen, modifier = modifier.testTag(option.ref),
        shape = RoundedCornerShape(14.dp),
        color = if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else raisedColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (chosen) 0.45f else 0.32f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppIcon(if (chosen) AppIcons.Check else option.icon, null, Modifier.size(18.dp), tint = ink)
            Text(option.label, color = ink, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A program as a small app tile: its initial (or a more mark for all programs) above its name. */
@Composable private fun ProgramTile(initial: String?, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(Modifier.size(44.dp), shape = RoundedCornerShape(12.dp),
            color = if (initial != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else raisedColor(),
            border = if (initial == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)) else null) {
            Box(contentAlignment = Alignment.Center) {
                if (initial != null) Text(initial, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                else AppIcon(AppIcons.More, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
