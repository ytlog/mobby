package com.github.ytlog.mobby.android.localmodel

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Module-local theme with the same settings palette as the host. */
@Composable internal fun LocalModelTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        background = Color(0xFF121212), surface = Color(0xFF1E1E1E), primary = Color(0xFF80BAFF),
        onPrimary = Color(0xFF102033), onBackground = Color(0xFFEDEDED), onSurface = Color(0xFFEDEDED),
        onSurfaceVariant = Color(0xFF9A9A9A), outline = Color(0xFF2E2E30), error = Color(0xFFE88B8B),
    ) else lightColorScheme(
        background = Color(0xFFF5F5F7), surface = Color.White, primary = Color(0xFF2F80FF),
        onPrimary = Color.White, onBackground = Color(0xFF1A1A1A), onSurface = Color(0xFF1A1A1A),
        onSurfaceVariant = Color(0xFF8A8A8A), outline = Color(0xFFE6E6E8),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
