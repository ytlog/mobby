package com.mobby.interaction.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Stroke icons matching the conversation HTML prototype, used in place of Material icon fonts. */
internal object AppIcons {
    val Menu = stroke("menu", "M4 6h16M4 12h16M4 18h16")
    val ChevronDown = stroke("down", "m6 9 6 6 6-6")
    val ChevronUp = stroke("up", "m6 15 6-6 6 6")
    val ChevronRight = stroke("right", "m9 5 7 7-7 7")
    val New = stroke("new", "M12 5v14M5 12h14")
    val More = stroke("more", "M12 6.5a1 1 0 1 1 0-2 1 1 0 0 1 0 2M12 13a1 1 0 1 1 0-2 1 1 0 0 1 0 2M12 19.5a1 1 0 1 1 0-2 1 1 0 0 1 0 2", fill = true)
    val Plus = stroke("plus", "M12 5v14M5 12h14")
    val Mic = stroke("mic", "M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0zM5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8")
    val Stop = stroke("stop", "M8 8h8v8H8z", fill = true)
    val Send = stroke("send", "m5 12 7-7 7 7M12 5v15")
    val Close = stroke("close", "m6 6 12 12M18 6 6 18")
    val Copy = stroke("copy", "M9 9h11v11H9zM5 15H3V3h12v2")
    val Expand = stroke("expand", "M9 3H3v6M15 3h6v6M3 15v6h6M21 15v6h-6")
    val Wrap = stroke("wrap", "M3 5h18M3 10h14a4 4 0 0 1 0 8h-5m3-3-3 3 3 3M3 16h4")
    val File = stroke("file", "M6 2h8l4 4v16H6zM14 2v5h5")
    val Folder = stroke("folder", "M3 6h7l2 3h9v11H3z")
    val Search = stroke("search", "M16 16l5 5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0")
    val Settings = stroke("settings", "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2")
    val Back = stroke("back", "m14 5-7 7 7 7")
    val Share = stroke("share", "M12 16V3m-5 5 5-5 5 5M5 12v9h14v-9")
    val Pin = stroke("pin", "M8 3h8l-1 7 4 4H5l4-4zM12 14v8")
    val Check = stroke("check", "M5 12.5 9.5 17 19 7")
    val Error = stroke("error", "M12 9v4M12 16.5h.01M10.3 4.8 2.6 18.2A2 2 0 0 0 4.3 21h15.4a2 2 0 0 0 1.7-2.8L13.7 4.8a2 2 0 0 0-3.4 0z")
    val Help = stroke("help", "M12 17h.01M9.4 9.4a2.6 2.6 0 1 1 4.4 1.9c-.9.8-1.8 1.2-1.8 2.7M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18")
    val Camera = stroke("camera", "M3 7h4l2-3h6l2 3h4v14H3zM16 13a4 4 0 1 1-8 0 4 4 0 0 1 8 0")
    val Photo = stroke("photo", "M3 3h18v18H3zM3 17l6-6 4 4 3-3 5 5M8 7h.01")
    val Upload = stroke("upload", "M3 6h7l2 3h9v12H3zM12 18v-6m-3 3 3-3 3 3")
    val Plugin = stroke("plug", "m8 3 3 4m5-4 3 4M7 8l9-3 3 8-6 6-6-6zM7 17l-4 4")
    val Phone = stroke("phone", "M8 3h8v18H8zM12 18h.01")
    val Skill = stroke("skill", "M5 3h14v18H5zM8 7h8M8 11h8M8 15h5")
    val Globe = stroke("globe", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M3 12h18M12 3c-5 5-5 13 0 18M12 3c5 5 5 13 0 18")
    val ArrowDown = stroke("arrowDown", "M12 4v16m-6-6 6 6 6-6")
    val Edit = stroke("edit", "M4 20h4L19 9l-4-4L4 16zM13 7l4 4")
    val Terminal = stroke("terminal", "M4 5h16v14H4zM8 9l2.2 2L8 13M12.5 13H16")
    val Trash = stroke("trash", "M6 7h12M9 7V5h6v2M8 7l1 13h6l1-13")
}

private fun stroke(name: String, path: String, fill: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = if (fill) SolidColor(Color.Black) else SolidColor(Color.Transparent),
            stroke = if (fill) null else SolidColor(Color.Black),
            strokeLineWidth = if (fill) 0f else 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
