package com.github.ytlog.mobby.android.conversation.ui

import android.content.Context
import android.os.Build
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

internal fun overlayWindowContext(context: Context): Context {
    if (Build.VERSION.SDK_INT < 30) return context
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return context
    return runCatching {
        context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }.getOrDefault(context)
}

internal data class OverlayFold(val left: Int, val top: Int, val right: Int, val bottom: Int, val vertical: Boolean)

/** Overlay windows use the display's window manager, not the Activity's pane dimensions. */
internal fun overlayDisplaySize(context: Context): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= 30) {
        val bounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
    }
    val metrics = context.resources.displayMetrics
    return metrics.widthPixels to metrics.heightPixels
}

internal fun floatingConversationFrame(screenW: Int, screenH: Int, density: Float, fold: OverlayFold? = null): PetFrame {
    val width = minOf(petPx(420, density), screenW - petPx(24, density)).coerceIn(1, screenW.coerceAtLeast(1))
    val height = minOf(petPx(FloatingConversationHeight, density), screenH - petPx(96, density)).coerceIn(1, screenH.coerceAtLeast(1))
    val initial = PetFrame(
        ((screenW - width) / 2).coerceAtLeast(0),
        ((screenH - height) / 3).coerceIn(0, (screenH - height).coerceAtLeast(0)),
        width, height, true, focusable = true,
    )
    val region = safeOverlayRegion(screenW, screenH, fold, -1, -1, petPx(8, density))
        ?: return initial
    val margin = petPx(8, density)
    val fittedWidth = minOf(initial.width, (region.right - region.left - margin * 2).coerceAtLeast(1))
    val fittedHeight = minOf(initial.height, (region.bottom - region.top - margin * 2).coerceAtLeast(1))
    return initial.copy(
        x = region.left + ((region.right - region.left - fittedWidth) / 2).coerceAtLeast(0),
        y = region.top + ((region.bottom - region.top - fittedHeight) / 3).coerceAtLeast(0),
        width = fittedWidth, height = fittedHeight,
    )
}

internal data class OverlayRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun safeOverlayRegion(screenW: Int, screenH: Int, fold: OverlayFold?, targetX: Int, targetY: Int, clearance: Int, minWidth: Int = 1, minHeight: Int = 1): OverlayRegion? {
    if (fold == null) return null
    val regions = if (fold.vertical) {
        if (fold.right <= 0 || fold.left >= screenW) return null
        listOf(OverlayRegion(0, 0, (fold.left - clearance).coerceIn(0, screenW), screenH),
            OverlayRegion((fold.right + clearance).coerceIn(0, screenW), 0, screenW, screenH))
    } else {
        if (fold.bottom <= 0 || fold.top >= screenH) return null
        listOf(OverlayRegion(0, 0, screenW, (fold.top - clearance).coerceIn(0, screenH)),
            OverlayRegion(0, (fold.bottom + clearance).coerceIn(0, screenH), screenW, screenH))
    }.filter { it.right > it.left && it.bottom > it.top }
    val fitting = regions.filter { it.right - it.left >= minWidth && it.bottom - it.top >= minHeight }
    return (fitting.ifEmpty { regions }).maxByOrNull { region ->
        val contains = targetX in region.left until region.right && targetY in region.top until region.bottom
        (if (contains) 1_000_000_000L else 0L) + (region.right - region.left).toLong() * (region.bottom - region.top)
    }
}

internal fun petFrameOutsideFold(frame: PetFrame, screenW: Int, screenH: Int, ball: Int, fold: OverlayFold?): Pair<PetFrame, Boolean> {
    val centerX = if (frame.ballOnRight) frame.x + frame.width - ball / 2 else frame.x + ball / 2
    val centerY = frame.y + frame.height / 2
    val region = safeOverlayRegion(screenW, screenH, fold, centerX, centerY, ball / 7, ball, ball) ?: return frame to false
    val availableW = region.right - region.left
    val availableH = region.bottom - region.top
    val collapsed = frame.width > availableW || frame.height > availableH
    val width = if (collapsed) ball.coerceAtMost(availableW) else frame.width
    val height = if (collapsed) ball.coerceAtMost(availableH) else frame.height
    val x = frame.x.coerceIn(region.left, region.right - width)
    val y = frame.y.coerceIn(region.top, region.bottom - height)
    return frame.copy(x = x, y = y, width = width, height = height) to collapsed
}
