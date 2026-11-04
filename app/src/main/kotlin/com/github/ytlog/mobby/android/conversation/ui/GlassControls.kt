package com.github.ytlog.mobby.android.conversation.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

internal val LocalConversationBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Sample only the transcript layer; never record controls into their own backdrop. */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    tint: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalConversationBackdrop.current
    val dark = darkChrome()
    val base = if (tint == Color.Unspecified) buttonColor() else tint
    val accented = tint != Color.Unspecified
    // A clear lens needs a visible rim even over the conversation's plain canvas.
    // Keep this lighting separate from the sampled content and the crisp foreground.
    val surface: DrawScope.() -> Unit = {
        drawRect(base.copy(alpha = if (accented) 0.94f else if (dark) 0.24f else 0.18f))
        if (!accented) {
            drawRect(Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.14f else 0.48f),
                0.35f to Color.White.copy(alpha = 0.02f),
                1f to Color.Black.copy(alpha = if (dark) 0.16f else 0.065f),
                endY = size.height,
            ))
            drawRect(Brush.linearGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.08f else 0.22f), Color.Transparent),
                start = Offset.Zero,
                end = Offset(size.width * 0.65f, size.height),
            ))
        }
    }
    val glass = if (Build.VERSION.SDK_INT >= 31 && backdrop != null) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(4.dp.toPx())
            lens(12.dp.toPx(), 24.dp.toPx(), depthEffect = true)
        },
        highlight = { Highlight(width = 1.dp, blurRadius = 0.5.dp,
            style = HighlightStyle.Default(intensity = 0.9f, falloff = 1.5f)) },
        shadow = { Shadow(radius = 12.dp, offset = DpOffset(0.dp, 4.dp), color = Color.Black.copy(alpha = if (dark) 0.28f else 0.12f)) },
        innerShadow = { InnerShadow(radius = 3.dp, offset = DpOffset(0.dp, 1.dp), color = Color.Black.copy(alpha = if (dark) 0.24f else 0.10f)) },
        onDrawSurface = surface,
    ) else Modifier.background(base, shape).clip(shape).drawBehind(surface)
    Box(modifier.then(glass).border(
        0.75.dp,
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.65f else 0.95f),
            Color.White.copy(alpha = 0.18f),
            Color.Black.copy(alpha = if (dark) 0.25f else 0.16f),
        )),
        shape,
    ).clip(shape)) {
        CompositionLocalProvider(LocalContentColor provides onButtonColor(), content = content)
    }
}
