package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.ButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

internal val LocalGlassControls = staticCompositionLocalOf { false }

/** Glass belongs to the home conversation page and the drawer; every other surface stays plain. */
internal val LocalGlassEnabled = staticCompositionLocalOf { false }

/** The drawer: its controls are glass and sample the drawer's own solid background. */
@Composable internal fun GlassControlScope(background: Color, content: @Composable () -> Unit) {
    val flatBackground = rememberCanvasBackdrop { drawRect(background) }
    CompositionLocalProvider(LocalGlassEnabled provides true, LocalGlassControls provides true,
        LocalConversationBackdrop provides flatBackground, content = content)
}

/** Retain Material's semantics, focus, disabled handling and interaction source inside the glass. */
@Composable internal fun ScopedButtonSurface(modifier: Modifier, shape: Shape, colors: ButtonColors, enabled: Boolean,
    content: @Composable (Modifier, ButtonColors) -> Unit) {
    if (!LocalGlassControls.current) content(modifier, colors)
    else GlassSurface(modifier, shape, tint = colors.containerColor.takeIf { enabled && it.alpha > 0f } ?: Color.Unspecified) {
        content(Modifier, ButtonColors(Color.Transparent, colors.contentColor, Color.Transparent, colors.disabledContentColor))
    }
}
