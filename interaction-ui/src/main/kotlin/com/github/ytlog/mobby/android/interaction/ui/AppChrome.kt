package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val ToolbarControl = 44.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ActionIcon(label: String, onClick: () -> Unit, icon: AppGlyph, enabled: Boolean = true, filled: Boolean = false, tint: Color = Color.Unspecified) {
    val ink = if (tint == Color.Unspecified) onButtonColor() else tint
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(ToolbarControl)) {
        if (filled) Box(Modifier.size(32.dp).background(if (darkChrome()) buttonColor() else MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(icon, label, Modifier.size(16.dp), tint = if (darkChrome()) ink else MaterialTheme.colorScheme.surface)
        } else AppIcon(icon, label, Modifier.size(22.dp), tint = if (enabled) ink else ink.copy(alpha = 0.38f))
    }
    }
}

@Composable internal fun StreamingCursor(description: String? = null) {
    val reduced = rememberReducedMotion()
    val alpha = if (reduced) 1f else {
        val pulse = rememberInfiniteTransition(label = "cursor")
        val value by pulse.animateFloat(0.2f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "cursor-alpha")
        value
    }
    val mark = Modifier.padding(top = 4.dp).size(8.dp, 16.dp).alpha(alpha).background(MaterialTheme.colorScheme.primary, CircleShape)
    Box(if (description == null) mark else mark.semantics { contentDescription = description })
}

@Composable internal fun AttachmentTile(label: String, icon: AppGlyph, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.height(100.dp).semantics { contentDescription = label }, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(icon, null, Modifier.size(26.dp))
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable internal fun CapabilityRow(title: String, detail: String, icon: AppGlyph, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        colors = textButtonColors(onButtonColor()),
    ) {
        AppIcon(icon, null, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppIcon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
    cursorColor = MaterialTheme.colorScheme.primary,
    errorCursorColor = MaterialTheme.colorScheme.error,
)

@Composable internal fun SettingsField(
    value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean = true,
    singleLine: Boolean = true, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value, onValueChange, Modifier.fillMaxWidth(), enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge, label = { Text(label) },
        visualTransformation = visualTransformation, singleLine = singleLine, maxLines = maxLines,
        shape = RoundedCornerShape(0.dp), colors = settingsFieldColors(),
    )
}

@Composable internal fun SettingsCaption(text: String, error: Boolean = false) {
    Text(text, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun SettingsAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = onButtonColor(),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
    ) { Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge) }
}

@Composable internal fun SettingsItem(title: String, onClick: () -> Unit, detail: String? = null) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = textButtonColors(onButtonColor())) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppIcon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Text(title, Modifier.padding(start = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor()) { Column(content = content) }
    }
}

@Composable internal fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outline)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun CatalogTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, label ->
            val requester = remember { BringIntoViewRequester() }
            LaunchedEffect(selected) { if (selected == index) requester.bringIntoView() }
            Box(Modifier.bringIntoViewRequester(requester)) {
                CatalogChip(label, selected == index) { onSelect(index) }
            }
        }
    }
}

@Composable internal fun CatalogChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) buttonColor() else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, onButtonColor().copy(alpha = 0.28f)) else null,
        contentColor = if (selected) onButtonColor() else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable internal fun CatalogRow(
    title: String,
    subtitle: String,
    icon: AppGlyph,
    iconForeground: Color,
    iconBackground: Color,
    action: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = raisedColor()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).background(iconBackground, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    AppIcon(icon, null, Modifier.size(24.dp), tint = iconForeground)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = onAction,
                enabled = actionEnabled,
                shape = RoundedCornerShape(18.dp),
                color = if (darkChrome()) buttonColor() else Color.Transparent,
                border = BorderStroke(1.dp, onButtonColor().copy(alpha = if (actionEnabled) 0.28f else 0.12f)),
            ) {
                Text(
                    action,
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = onButtonColor().copy(alpha = if (actionEnabled) 1f else 0.38f),
                )
            }
        }
    }
}

@Composable internal fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f), style = MaterialTheme.typography.bodyLarge)
        if (selected) AppIcon(AppIcons.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable internal fun MenuSection(title: String, content: @Composable () -> Unit) {
    Text(title, Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = menuMuted())
    content()
}

@Composable internal fun MenuOption(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val color = menuInk().copy(alpha = if (enabled) 1f else 0.38f)
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        if (selected) AppIcon(AppIcons.Check, null, Modifier.size(20.dp), tint = menuTick())
    }
}

@Composable internal fun MenuCaption(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = menuMuted())
}

@Composable internal fun MenuAction(text: String, enabled: Boolean = true, danger: Boolean = false, icon: AppGlyph? = null, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else menuInk().copy(alpha = if (enabled) 1f else 0.38f)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, null, Modifier.size(20.dp), tint = color)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}
