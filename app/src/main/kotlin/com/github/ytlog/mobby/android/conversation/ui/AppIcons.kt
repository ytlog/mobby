package com.github.ytlog.mobby.android.conversation.ui

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
import androidx.compose.ui.graphics.PathFillType
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

internal class AppGlyph(val path: Path, val filled: Boolean = false, val evenOdd: Boolean = false)

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
    val Stop = mark("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z")
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
    val Codex = mark("M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z")
    val Claude = mark("m4.7144 15.9555 4.7174-2.6471.079-.2307-.079-.1275h-.2307l-.7893-.0486-2.6956-.0729-2.3375-.0971-2.2646-.1214-.5707-.1215-.5343-.7042.0546-.3522.4797-.3218.686.0608 1.5179.1032 2.2767.1578 1.6514.0972 2.4468.255h.3886l.0546-.1579-.1336-.0971-.1032-.0972L6.973 9.8356l-2.55-1.6879-1.3356-.9714-.7225-.4918-.3643-.4614-.1578-1.0078.6557-.7225.8803.0607.2246.0607.8925.686 1.9064 1.4754 2.4893 1.8336.3643.3035.1457-.1032.0182-.0728-.164-.2733-1.3539-2.4467-1.445-2.4893-.6435-1.032-.17-.6194c-.0607-.255-.1032-.4674-.1032-.7285L6.287.1335 6.6997 0l.9957.1336.419.3642.6192 1.4147 1.0018 2.2282 1.5543 3.0296.4553.8985.2429.8318.091.255h.1579v-.1457l.1275-1.706.2368-2.0947.2307-2.6957.0789-.7589.3764-.9107.7468-.4918.5828.2793.4797.686-.0668.4433-.2853 1.8517-.5586 2.9021-.3643 1.9429h.2125l.2429-.2429.9835-1.3053 1.6514-2.0643.7286-.8196.85-.9046.5464-.4311h1.0321l.759 1.1293-.34 1.1657-1.0625 1.3478-.8804 1.1414-1.2628 1.7-.7893 1.36.0729.1093.1882-.0183 2.8535-.607 1.5421-.2794 1.8396-.3157.8318.3886.091.3946-.3278.8075-1.967.4857-2.3072.4614-3.4364.8136-.0425.0304.0486.0607 1.5482.1457.6618.0364h1.621l3.0175.2247.7892.522.4736.6376-.079.4857-1.2142.6193-1.6393-.3886-3.825-.9107-1.3113-.3279h-.1822v.1093l1.0929 1.0686 2.0035 1.8092 2.5075 2.3314.1275.5768-.3218.4554-.34-.0486-2.2039-1.6575-.85-.7468-1.9246-1.621h-.1275v.17l.4432.6496 2.3436 3.5214.1214 1.0807-.17.3521-.6071.2125-.6679-.1214-1.3721-1.9246L14.38 17.959l-1.1414-1.9428-.1397.079-.674 7.2552-.3156.3703-.7286.2793-.6071-.4614-.3218-.7468.3218-1.4753.3886-1.9246.3157-1.53.2853-1.9004.17-.6314-.0121-.0425-.1397.0182-1.4328 1.9672-2.1796 2.9446-1.7243 1.8456-.4128.164-.7164-.3704.0667-.6618.4008-.5889 2.386-3.0357 1.4389-1.882.929-1.0868-.0062-.1579h-.0546l-6.3385 4.1164-1.1293.1457-.4857-.4554.0608-.7467.2307-.2429 1.9064-1.3114Z")
    val Pi = mark("M5 5h14v3h-3v11h-3V8H9v11H6V8H5z")
    val OpenCode = mark("M22 24H2V0h20zM17 4.8H7v14.4h10z", evenOdd = true)
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
                    fillType = if (icon.evenOdd) PathFillType.EvenOdd else icon.path.fillType
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

private fun mark(path: String, evenOdd: Boolean = false): AppGlyph {
    val parsed = PathParser().parsePathString(path).toPath()
    if (evenOdd) parsed.fillType = PathFillType.EvenOdd
    return AppGlyph(parsed, filled = true, evenOdd = evenOdd)
}
