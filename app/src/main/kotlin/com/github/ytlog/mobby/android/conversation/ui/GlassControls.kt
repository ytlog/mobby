package com.github.ytlog.mobby.android.conversation.ui

import android.os.Build
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.RuntimeShaderCache
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
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
    val opticalGlass = Build.VERSION.SDK_INT >= 31 && backdrop != null
    val edgeHighlight = remember(dark) { EdgeBandHighlight(Color.White.copy(alpha = if (dark) 0.30f else 0.34f)) }
    val surface: DrawScope.() -> Unit = { drawGlassLighting(base, dark, accented) }
    val glass = if (opticalGlass) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(1.5.dp.toPx())
            lens(6.dp.toPx(), 20.dp.toPx())
        },
        highlight = { Highlight(width = 1.2.dp, blurRadius = 1.2.dp,
            style = edgeHighlight) },
        shadow = { Shadow(radius = 12.dp, offset = DpOffset(0.dp, 4.dp), color = Color.Black.copy(alpha = if (dark) 0.10f else 0.07f)) },
        onDrawSurface = surface,
    ) else Modifier.background(base, shape).clip(shape).drawBehind(surface)
    // The library already draws a directional highlight. A second border turns it into chrome.
    val fallbackRim = if (opticalGlass) Modifier else Modifier.border(
        0.5.dp,
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.18f else 0.40f),
            Color.Transparent,
            Color.White.copy(alpha = if (dark) 0.025f else 0.06f),
        )),
        shape,
    )
    Box(modifier.then(glass).then(fallbackRim).clip(shape)) {
        CompositionLocalProvider(LocalContentColor provides onButtonColor(), content = content)
    }
}

/** Two soft bands along the edge halves, rather than a normal-based glow at opposite corners. */
private class EdgeBandHighlight(override val color: Color) : HighlightStyle {
    override val blendMode = androidx.compose.ui.graphics.BlendMode.Plus
    private var shaderSize = Size.Unspecified
    private var cachedShader: Shader? = null

    override fun DrawScope.createShader(shape: Shape, runtimeShaderCache: RuntimeShaderCache): Shader {
        cachedShader?.let { if (shaderSize == size) return it }
        fun band(center: Float, top: Boolean): Shader {
            val alongEdge = LinearGradient(0f, 0f, size.width, 0f,
                intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT),
                floatArrayOf(center - 0.20f, center, center + 0.20f), android.graphics.Shader.TileMode.CLAMP)
            val towardInterior = if (top) LinearGradient(0f, 0f, 0f, size.height * 0.35f,
                android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
            else LinearGradient(0f, size.height * 0.65f, 0f, size.height,
                android.graphics.Color.TRANSPARENT, android.graphics.Color.WHITE, android.graphics.Shader.TileMode.CLAMP)
            return ComposeShader(alongEdge, towardInterior, PorterDuff.Mode.DST_IN)
        }
        return ComposeShader(band(0.28f, top = true), band(0.72f, top = false), PorterDuff.Mode.ADD).also {
            shaderSize = size
            cachedShader = it
        }
    }
}

/** Lighting over the sampled image; kept separate so transmission can be pixel-tested without AGSL. */
internal fun DrawScope.drawGlassLighting(base: Color, dark: Boolean, accented: Boolean) {
    drawRect(base.copy(alpha = if (accented) 0.94f else if (dark) 0.08f else 0.10f))
    if (!accented) {
        drawRect(Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.012f else 0.18f),
            0.35f to Color.White.copy(alpha = if (dark) 0.004f else 0.008f),
            1f to Color.Black.copy(alpha = if (dark) 0.008f else 0.025f),
            endY = size.height,
        ))
        drawRect(Brush.linearGradient(
            listOf(Color.White.copy(alpha = if (dark) 0.008f else 0.06f), Color.Transparent),
            start = Offset.Zero,
            end = Offset(size.width * 0.65f, size.height),
        ))
    }
}
