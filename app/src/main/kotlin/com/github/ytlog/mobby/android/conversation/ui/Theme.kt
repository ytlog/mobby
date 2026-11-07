package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@Composable internal fun floatingElevation() = if (darkChrome()) 8.dp else 12.dp

@Composable internal fun EmptyPlaceholder(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (detail != null) Text(detail, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Width of a menu card; the agent menu and the new conversation and conversation action popups all share it. */
private val MenuWidth = 304.dp

/**
 * A menu card that opens below [anchor] over a dimmed window: an opaque card with a hairline border,
 * kept within the window's 16dp margins. Glass is turned off inside it.
 */
@Composable internal fun DimmedMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: IntRect = IntRect.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val host = LocalView.current
    val density = LocalDensity.current
    val margin = with(density) { 16.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val windowWidth = host.rootView.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels
    val popupWidth = minOf((windowWidth - margin * 2).coerceAtLeast(1), with(density) { MenuWidth.roundToPx() })
    val popupLeft = (if (anchor.width > 0) anchor.left else margin)
        .coerceIn(margin, (windowWidth - popupWidth - margin).coerceAtLeast(margin))
    val fallbackTop = margin + with(density) { 56.dp.roundToPx() }
    val shape = RoundedCornerShape(22.dp)
    val panelColor = cardColor()
    val dimAmount = if (darkChrome()) 0.26f else 0.18f
    val stroke = menuStroke()
    val position = remember(margin, gap, fallbackTop, anchor, popupLeft) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = popupLeft.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val rawY = when {
                    anchor.bottom > 0 -> anchor.bottom + gap
                    anchorBounds.bottom > 0 -> anchorBounds.bottom + gap
                    else -> fallbackTop
                }
                val y = rawY.coerceAtMost((windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        val popupView = LocalView.current
        SideEffect {
            clearPopupChrome(popupView)
            dimPopupBackdrop(popupView, dimAmount)
        }
        Box(
            Modifier
                .width(with(density) { popupWidth.toDp() })
                .clip(shape)
                .border(0.5.dp, stroke, shape)
                .background(panelColor),
        ) {
            CompositionLocalProvider(LocalGlassEnabled provides false, LocalGlassControls provides false, LocalConversationBackdrop provides null) {
                MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme.copy(
                        primary = menuAccent(),
                        onPrimary = MobbyColors.onAccent,
                        onSurface = menuInk(),
                        onSurfaceVariant = menuMuted(),
                    ),
                ) {
                    Column(Modifier.fillMaxWidth(), content = content)
                }
            }
        }
    }
}

private fun dimPopupBackdrop(view: View, amount: Float) {
    val window = view.parent as? View ?: return
    val params = window.layoutParams as? WindowManager.LayoutParams ?: return
    val flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
    if (window.isAttachedToWindow && (params.flags != flags || params.dimAmount != amount)) {
        params.flags = flags
        params.dimAmount = amount
        window.context.getSystemService(WindowManager::class.java).updateViewLayout(window, params)
    }
}

internal fun clearPopupChrome(view: View) {
    val transparent = ColorDrawable(android.graphics.Color.TRANSPARENT)
    view.background = transparent
    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    val parent = view.parent as? ViewGroup ?: return
    parent.background = transparent
    parent.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    parent.clipChildren = false
    parent.clipToPadding = false
}
