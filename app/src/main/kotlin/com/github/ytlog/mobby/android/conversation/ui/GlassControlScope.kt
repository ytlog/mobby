package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

internal val LocalGlassControls = staticCompositionLocalOf { false }

/** Flat panels sample their actual solid background; popups supply their own window-local layer. */
@Composable internal fun GlassControlScope(background: Color, backdrop: Backdrop? = null, glassButtons: Boolean = true, content: @Composable () -> Unit) {
    val flatBackground = rememberCanvasBackdrop { drawRect(background) }
    CompositionLocalProvider(LocalGlassControls provides glassButtons,
        LocalConversationBackdrop provides (backdrop ?: flatBackground), content = content)
}

@Composable internal fun GlassControlSurface(modifier: Modifier = Modifier, shape: Shape,
    tint: Color = Color.Unspecified, content: @Composable () -> Unit) {
    if (LocalGlassControls.current) GlassSurface(modifier, shape, tint, content = content)
    else Box(modifier, propagateMinConstraints = true) { content() }
}

/** Retain Material's semantics, focus, disabled handling and interaction source inside the glass. */
@Composable internal fun ScopedButtonSurface(modifier: Modifier, shape: Shape, colors: ButtonColors, enabled: Boolean,
    content: @Composable (Modifier, ButtonColors) -> Unit) {
    if (!LocalGlassControls.current) content(modifier, colors)
    else GlassSurface(modifier, shape, tint = colors.containerColor.takeIf { enabled && it.alpha > 0f } ?: Color.Unspecified) {
        content(Modifier, ButtonColors(Color.Transparent, colors.contentColor, Color.Transparent, colors.disabledContentColor))
    }
}
