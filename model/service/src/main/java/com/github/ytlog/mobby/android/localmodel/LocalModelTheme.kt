package com.github.ytlog.mobby.android.localmodel

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Keep the independent module's colors aligned with the host settings palette. */
@Composable internal fun LocalModelTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        background = Color(0xFF121212), surface = Color(0xFF121212), surfaceVariant = Color(0xFF2A2A2C), primary = Color(0xFF80BAFF),
        onPrimary = Color(0xFF102033), onBackground = Color(0xFFEDEDED), onSurface = Color(0xFFEDEDED),
        onSurfaceVariant = Color(0xFF9A9A9A), outline = Color(0xFF2E2E30), outlineVariant = Color(0xFF2E2E30), error = Color(0xFFE88B8B),
    ) else lightColorScheme(
        background = Color(0xFFF5F5F7), surface = Color(0xFFF5F5F7), surfaceVariant = Color(0xFFF0F1F3), primary = Color(0xFF2F80FF),
        onPrimary = Color.White, onBackground = Color(0xFF1A1A1A), onSurface = Color(0xFF1A1A1A),
        onSurfaceVariant = Color(0xFF8A8A8A), outline = Color(0xFFE6E6E8), outlineVariant = Color(0xFFE6E6E8),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
