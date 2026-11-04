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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

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
    val glass = if (Build.VERSION.SDK_INT >= 31 && backdrop != null) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(18.dp.toPx())
            lens(8.dp.toPx(), 12.dp.toPx())
        },
        onDrawSurface = { drawRect(base.copy(alpha = if (tint != Color.Unspecified) 0.94f else if (dark) 0.72f else 0.64f)) },
    ) else Modifier.background(base, shape)
    Box(modifier.then(glass).border(
        1.dp,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.24f else 0.75f), Color.White.copy(alpha = 0.06f))),
        shape,
    ).clip(shape)) {
        CompositionLocalProvider(LocalContentColor provides onButtonColor(), content = content)
    }
}
