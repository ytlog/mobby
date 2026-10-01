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
