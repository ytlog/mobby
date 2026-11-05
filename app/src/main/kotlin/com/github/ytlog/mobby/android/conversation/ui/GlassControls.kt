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
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.InnerShadow

/** Room a glass control needs below it so its outer shadow fades out before the screen edge cuts it off. */
internal val GlassShadowClearance = 20.dp

internal val LocalConversationBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Sample only the transcript layer; never record controls into their own backdrop. */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    tint: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val dark = darkChrome()
    val neutralLight = !dark && neutralLightTint(tint)
    if (!LocalGlassEnabled.current) {
        val fill = if (neutralLight) conversationCardColor() else if (tint == Color.Unspecified) buttonColor() else tint
        Box(modifier.background(fill, shape).clip(shape), propagateMinConstraints = true) {
            CompositionLocalProvider(LocalContentColor provides onButtonColor(), content = content)
        }
        return
    }
    val backdrop = LocalConversationBackdrop.current
    val base = if (neutralLight) MobbyColors.Light.glassBase
        else if (tint == Color.Unspecified) buttonColor() else tint
    val accented = tint != Color.Unspecified && !neutralLight
    val opticalGlass = Build.VERSION.SDK_INT >= 31 && backdrop != null
    val surface: DrawScope.() -> Unit = { drawGlassLighting(base, dark, accented) }
    val glass = if (opticalGlass) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur((if (neutralLight) 3.dp else 12.dp).toPx())
            lens(6.dp.toPx(), 20.dp.toPx())
        },
        highlight = { Highlight(width = if (neutralLight) 1.4.dp else 0.4.dp, blurRadius = if (neutralLight) 0.25.dp else 0.5.dp,
            style = HighlightStyle.Default(intensity = if (dark) 0.36f else if (neutralLight) 1f else 0.40f, // Tighter on light: near-white saturates, so even a faint highlight would line the straight edges.
            falloff = if (neutralLight) 14f else 6f)) },
        shadow = { Shadow(radius = if (neutralLight) 16.dp else 12.dp,
            offset = DpOffset(0.dp, if (neutralLight) 5.dp else 4.dp),
            color = (if (dark) Color.Black else MobbyColors.Light.glassShadow).copy(alpha = if (dark) 0.10f else if (neutralLight) 0.22f else 0.07f)) },
        innerShadow = { if (neutralLight) InnerShadow(
            // Centered: this layer paints over the highlight, so any offset would dim one of the two lit corners.
            radius = 4.dp,
            offset = DpOffset(0.dp, 0.dp),
            color = MobbyColors.Light.glassInnerShadow.copy(alpha = 0.26f)) else null },
        onDrawSurface = surface,
    ) else Modifier.background(if (neutralLight) base.copy(alpha = 0.14f) else base, shape).clip(shape).drawBehind(surface)
    // The library already draws a directional highlight. A second border turns it into chrome.
    val fallbackRim = if (opticalGlass) Modifier else Modifier.border(
        if (neutralLight) 0.85.dp else 0.5.dp,
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.18f else if (neutralLight) 0.90f else 0.40f),
            Color.Transparent,
            Color.White.copy(alpha = if (dark) 0.025f else if (neutralLight) 0.35f else 0.06f),
        )),
        shape,
    )
    Box(modifier.then(glass).then(fallbackRim).clip(shape), propagateMinConstraints = true) {
        CompositionLocalProvider(LocalContentColor provides onButtonColor(), content = content)
    }
}

/** A control with no tint, or a near-white one, is ordinary glass; anything more colorful is an accent. */
internal fun neutralLightTint(tint: Color): Boolean = tint == Color.Unspecified ||
    (minOf(tint.red, tint.green, tint.blue) > 0.7f && maxOf(tint.red, tint.green, tint.blue) - minOf(tint.red, tint.green, tint.blue) < 0.08f)

/** Lighting over the sampled image; kept separate so transmission can be pixel-tested without AGSL. */
internal fun DrawScope.drawGlassLighting(base: Color, dark: Boolean, accented: Boolean) {
    drawRect(base.copy(alpha = if (accented) 0.94f else if (dark) 0.32f else 0.14f))
    if (!accented) {
        drawRect(Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.012f else 0.1f),
            0.35f to Color.White.copy(alpha = if (dark) 0.004f else 0.008f),
            1f to Color.Black.copy(alpha = if (dark) 0.008f else 0.05f),
            endY = size.height,
        ))
        drawRect(Brush.linearGradient(
            listOf(Color.White.copy(alpha = if (dark) 0.008f else 0.06f), Color.Transparent),
            start = Offset.Zero,
            end = Offset(size.width * 0.65f, size.height),
        ))
    }
}
