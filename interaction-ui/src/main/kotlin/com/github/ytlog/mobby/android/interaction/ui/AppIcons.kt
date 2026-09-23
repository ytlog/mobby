package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Stroke shared by every icon on screen. A 22dp icon keeps the previous 1.7 viewport stroke;
 * smaller and larger icons use the same physical width instead of scaling with their box.
 */
internal val IconStroke = (1.7f * 22f / 24f).dp

internal class AppGlyph(val path: Path, val filled: Boolean = false)

/** Stroke icons matching the conversation HTML prototype, used in place of Material icon fonts. */
internal object AppIcons {
    val Menu = glyph("M4 6h16M4 12h16M4 18h16")
    val ChevronDown = glyph("m6 9 6 6 6-6")
    val ChevronUp = glyph("m6 15 6-6 6 6")
    val ChevronRight = glyph("m9 5 7 7-7 7")
    val New = glyph("M12 5v14M5 12h14")
    val More = AppGlyph(Path().apply {
        listOf(6f, 12f, 18f).forEach { y -> addOval(Rect(10f, y - 2f, 14f, y + 2f)) }
    }, filled = true)
    val Plus = glyph("M12 5v14M5 12h14")
    val Mic = glyph("M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0zM5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8")
    val Keyboard = glyph("M4 6h16v12H4z M7 9h2 M11 9h2 M15 9h2 M7 12h2 M11 12h2 M15 12h2 M7 15h10")
    val Stop = glyph("M8 8h8v8H8z")
    val Send = glyph("m5 12 7-7 7 7M12 5v15")
    val Close = glyph("m6 6 12 12M18 6 6 18")
    val Copy = glyph("M9 9h11v11H9zM5 15H3V3h12v2")
    val Expand = glyph("M9 3H3v6M15 3h6v6M3 15v6h6M21 15v6h-6")
    val Wrap = glyph("M3 5h18M3 10h14a4 4 0 0 1 0 8h-5m3-3-3 3 3 3M3 16h4")
    val File = glyph("M6 2h8l4 4v16H6zM14 2v5h5")
    val Folder = glyph("M3 6h7l2 3h9v11H3z")
    val Search = glyph("M21 21l-4.3-4.3M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0")
    val Settings = glyph("M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 0 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 0 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 0 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9c.3.6.8 1 1.5 1.2H21a2 2 0 0 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
    val Back = glyph("m14 5-7 7 7 7")
    val Share = glyph("M12 16V3m-5 5 5-5 5 5M5 12v9h14v-9")
    val Pin = glyph("M8 3h8l-1 7 4 4H5l4-4zM12 14v8")
    val Check = glyph("M5 12.5 9.5 17 19 7")
    val Error = glyph("M12 9v4M12 16.5h.01M10.3 4.8 2.6 18.2A2 2 0 0 0 4.3 21h15.4a2 2 0 0 0 1.7-2.8L13.7 4.8a2 2 0 0 0-3.4 0z")
    val Help = glyph("M12 17h.01M9.4 9.4a2.6 2.6 0 1 1 4.4 1.9c-.9.8-1.8 1.2-1.8 2.7M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18")
    val Camera = glyph("M3 7h4l2-3h6l2 3h4v14H3zM16 13a4 4 0 1 1-8 0 4 4 0 0 1 8 0")
    val Photo = glyph("M3 3h18v18H3zM5 16l4.5-4.5 4 4 3-3 3 3M8 7h.01")
    val Upload = glyph("M3 6h7l2 3h9v12H3zM12 18v-6m-3 3 3-3 3 3")
    val Plugin = glyph("M12 22v-5M9 8V2M15 8V2M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z")
    val Phone = glyph("M8 3h8v18H8zM12 18h.01")
    val Skill = glyph("M5 3h14v18H5zM8 7h8M8 11h8M8 15h5")
    val Globe = glyph("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M3 12h18M12 3c-5 5-5 13 0 18M12 3c5 5 5 13 0 18")
    val ArrowDown = glyph("M12 4v16m-6-6 6 6 6-6")
    val Edit = glyph("M4 20h4L19 9l-4-4L4 16zM13 7l4 4")
    val Terminal = glyph("M4 5h16v14H4zM8 9l2.2 2L8 13M12.5 13H16")
    val Trash = glyph("M6 7h12M9 7V5h6v2M8 7l1 13h6l1-13")
    val Codex = glyph("M12 3.8 19.1 7.9 19.1 16.1 12 20.2 4.9 16.1 4.9 7.9Z M12 8.2 16.2 10.6 16.2 13.4 12 15.8 7.8 13.4 7.8 10.6Z")
    val Claude = glyph("M12 3.5 14.7 9.3 20.5 12 14.7 14.7 12 20.5 9.3 14.7 3.5 12 9.3 9.3Z")
    val OpenCode = glyph("M15.9 6.4A6.8 6.8 0 1 0 15.9 17.6 M8.6 10.4 11.8 13 8.6 15.6 M12.2 15.6H15.2")
}

@Composable
internal fun AppIcon(
    icon: AppGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Spacer(
        modifier
            .then(
                if (contentDescription != null) Modifier.semantics {
                    this.contentDescription = contentDescription
                    role = Role.Image
                } else Modifier,
            )
            .drawWithCache {
                val scale = size.minDimension / 24f
                val scaled = Path().apply {
                    addPath(icon.path)
                    transform(Matrix().apply { scale(scale, scale) })
                }
                val style = Stroke(
                    width = IconStroke.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.cornerPathEffect(2.4.dp.toPx()),
                )
                onDrawBehind {
                    if (icon.filled) drawPath(scaled, tint) else drawPath(scaled, tint, style = style)
                }
            },
    )
}

private fun glyph(path: String) = AppGlyph(PathParser().parsePathString(path).toPath())
