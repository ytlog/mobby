package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Coordinates are in the safe drawing viewport, not in the physical display. */
internal data class FoldRegion(
    val left: Dp, val top: Dp, val right: Dp, val bottom: Dp,
    val vertical: Boolean,
)

internal sealed interface ConversationLayout {
    data class Single(val left: Dp, val top: Dp, val width: Dp, val height: Dp) : ConversationLayout
    data class Dual(
        val listLeft: Dp, val listWidth: Dp,
        val detailLeft: Dp, val detailWidth: Dp,
        val top: Dp, val height: Dp,
    ) : ConversationLayout
}

/** Policy is independent of device identity, persistence, and Compose state. */
internal fun conversationLayout(width: Dp, height: Dp, fold: FoldRegion?): ConversationLayout {
    val w = width.coerceAtLeast(0.dp)
    val h = height.coerceAtLeast(0.dp)
    val usableFold = fold?.takeIf {
        if (it.vertical) it.right > 0.dp && it.left < w
        else it.bottom > 0.dp && it.top < h
    }
    if (usableFold != null) {
        if (usableFold.vertical) {
            val leftEnd = (usableFold.left - 8.dp).coerceIn(0.dp, w)
            val rightStart = (usableFold.right + 8.dp).coerceIn(leftEnd, w)
            val leftWidth = leftEnd
            val rightWidth = w - rightStart
            if (h >= 480.dp && leftWidth >= 280.dp && rightWidth >= 480.dp) {
                return ConversationLayout.Dual(0.dp, leftWidth, rightStart, rightWidth, 0.dp, h)
            }
            return if (rightWidth > leftWidth) ConversationLayout.Single(rightStart, 0.dp, rightWidth, h)
            else ConversationLayout.Single(0.dp, 0.dp, leftWidth, h)
        }
        val topEnd = (usableFold.top - 8.dp).coerceIn(0.dp, h)
        val bottomStart = (usableFold.bottom + 8.dp).coerceIn(topEnd, h)
        val topHeight = topEnd
        val bottomHeight = h - bottomStart
        return if (bottomHeight > topHeight) ConversationLayout.Single(0.dp, bottomStart, w, bottomHeight)
        else ConversationLayout.Single(0.dp, 0.dp, w, topHeight)
    }
    if (w >= 840.dp && h >= 480.dp) {
        val listWidth = 320.dp
        val detailStart = listWidth + 16.dp
        if (w - detailStart >= 480.dp) return ConversationLayout.Dual(0.dp, listWidth, detailStart, w - detailStart, 0.dp, h)
    }
    return ConversationLayout.Single(0.dp, 0.dp, w, h)
}

/** Where the page and the compact drawer sit in the viewport, shared by the drawer layer and the page card above it. */
internal data class ViewportGeometry(
    val layout: ConversationLayout, val wide: Boolean,
    val contentLeft: Dp, val contentTop: Dp, val contentWidth: Dp, val height: Dp,
) {
    val drawerWidth: Dp get() = minOf(360.dp, (contentWidth - 56.dp).coerceAtLeast(0.dp))
}

internal fun viewportGeometry(width: Dp, height: Dp, fold: FoldRegion?, sidebarExpanded: Boolean): ViewportGeometry {
    val layout = conversationLayout(width, height, fold)
    val wide = layout is ConversationLayout.Dual
    val fullDetail = wide && !sidebarExpanded && fold == null
    return when (layout) {
        is ConversationLayout.Single -> ViewportGeometry(layout, wide, layout.left, layout.top, layout.width, layout.height)
        is ConversationLayout.Dual -> ViewportGeometry(layout, wide, if (fullDetail) 0.dp else layout.detailLeft, layout.top,
            if (fullDetail) width else layout.detailWidth, layout.height)
    }
}
