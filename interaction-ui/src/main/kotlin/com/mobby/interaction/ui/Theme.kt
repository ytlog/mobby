package com.mobby.interaction.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal val MobbyDarkScheme: ColorScheme = darkColorScheme(
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF2A2A2C),
    primary = Color(0xFF80BAFF),
    onPrimary = Color(0xFF102033),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF9A9A9A),
    secondary = Color(0xFF80BAFF),
    error = Color(0xFFE88B8B),
    onError = Color(0xFF3B1010),
    outline = Color(0xFF2E2E30),
    outlineVariant = Color(0xFF2E2E30),
)

internal val MobbyLightScheme: ColorScheme = lightColorScheme(
    background = Color(0xFFF5F5F7),
    surface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFFF0F1F3),
    primary = Color(0xFF2F80FF),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF8A8A8A),
    secondary = Color(0xFF2F80FF),
    outline = Color(0xFFE6E6E8),
    outlineVariant = Color(0xFFE6E6E8),
)

@Composable internal fun darkChrome(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

@Composable internal fun cardColor(): Color = if (darkChrome()) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)

@Composable internal fun raisedColor(): Color = cardColor()

@Composable internal fun EmptyPlaceholder(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (detail != null) Text(detail, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable internal fun RaisedDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val raised = raisedColor()
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = raised), shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(20.dp))) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)
    }
}
