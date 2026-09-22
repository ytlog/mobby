package com.github.ytlog.mobby.android.interaction.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal val MobbyDarkScheme: ColorScheme = darkColorScheme(
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF2A2A2C),
    primary = Color(0xFF80BAFF),
    onPrimary = Color(0xFF102033),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF9A9A9A),
    secondary = Color(0xFF80BAFF),
    error = Color(0xFFE88B8B),
    onError = Color(0xFF3B1010),
    outline = Color(0xFF2E2E30),
    outlineVariant = Color(0xFF2E2E30),
)

internal val MobbyLightScheme: ColorScheme = lightColorScheme(
    background = Color(0xFFF5F5F7),
    surface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFFF0F1F3),
    primary = Color(0xFF2F80FF),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF8A8A8A),
    secondary = Color(0xFF2F80FF),
    outline = Color(0xFFE6E6E8),
    outlineVariant = Color(0xFFE6E6E8),
)

@Composable internal fun darkChrome(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

@Composable internal fun cardColor(): Color = if (darkChrome()) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)

@Composable internal fun raisedColor(): Color = cardColor()

@Composable internal fun floatingElevation() = if (darkChrome()) 8.dp else 12.dp

@Composable internal fun menuInk(): Color = if (darkChrome()) Color(0xFFF2F2F4) else Color(0xFF1C1C1E)

@Composable internal fun menuMuted(): Color = Color(0xFF8E8E93)

@Composable internal fun menuAccent(): Color = if (darkChrome()) Color(0xFF0A84FF) else Color(0xFF007AFF)

@Composable internal fun menuTick(): Color = if (darkChrome()) Color.White else Color(0xFF007AFF)

@Composable internal fun EmptyPlaceholder(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (detail != null) Text(detail, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable internal fun FrostedMenu(expanded: Boolean, onDismissRequest: () -> Unit, anchor: IntRect = IntRect.Zero, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val host = LocalView.current
    var snapshot by remember { mutableStateOf(snapshotView(host)) }
    LaunchedEffect(host) { captureWindow(host)?.let { snapshot = it } }
    val night = darkChrome()
    val density = LocalDensity.current
    val margin = with(density) { 16.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val windowWidth = host.rootView.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels
    val popupWidth = (windowWidth - margin * 2).coerceAtLeast(1)
    val predictedHeight = with(density) { 560.dp.roundToPx() }
    val frostTop = if (anchor.bottom > 0) anchor.bottom + gap else margin + with(density) { 56.dp.roundToPx() }
    var cardHeight by remember { mutableStateOf(predictedHeight) }
    val frost = remember(snapshot, popupWidth, frostTop, cardHeight) {
        snapshot?.let { frostRegion(it, margin, frostTop, popupWidth, cardHeight).asImageBitmap() }
    }
    val shape = RoundedCornerShape(22.dp)
    val scrim = if (night) Color(0x99141416) else Color(0xCCF5F5F7)
    val stroke = if (night) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.06f)
    val position = remember(margin, gap, frostTop, anchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = margin.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val rawY = when {
                    anchor.bottom > 0 -> anchor.bottom + gap
                    anchorBounds.bottom > 0 -> anchorBounds.bottom + gap
                    else -> frostTop
                }
                val y = rawY.coerceAtMost((windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        val popupView = LocalView.current
        SideEffect { clearPopupChrome(popupView) }
        Box(
            Modifier
                .width(with(density) { popupWidth.toDp() })
                .clip(shape)
                .border(0.5.dp, stroke, shape)
                .onGloballyPositioned { coordinates ->
                    if (coordinates.size.height > 0 && coordinates.size.height != cardHeight) cardHeight = coordinates.size.height
                },
        ) {
            frost?.let { image ->
                Image(image, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            }
            Box(Modifier.matchParentSize().background(scrim))
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    primary = menuAccent(),
                    onPrimary = Color.White,
                    onSurface = menuInk(),
                    onSurfaceVariant = menuMuted(),
                ),
            ) {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}

internal fun frostRegion(source: Bitmap, left: Int, top: Int, width: Int, height: Int): Bitmap {
    val pad = 64
    val l = (left - pad).coerceAtLeast(0)
    val t = (top - pad).coerceAtLeast(0)
    val r = (left + width + pad).coerceAtMost(source.width)
    val b = (top + height + pad).coerceAtMost(source.height)
    val cropped = Bitmap.createBitmap(source, l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
    val small = Bitmap.createScaledBitmap(cropped, (cropped.width / 8).coerceAtLeast(1), (cropped.height / 8).coerceAtLeast(1), true)
    repeat(3) { boxBlur(small, 3) }
    val up = Bitmap.createScaledBitmap(small, cropped.width, cropped.height, true)
    val ox = (left - l).coerceIn(0, (up.width - 1).coerceAtLeast(0))
    val oy = (top - t).coerceIn(0, (up.height - 1).coerceAtLeast(0))
    return Bitmap.createBitmap(
        up,
        ox,
        oy,
        width.coerceAtMost(up.width - ox).coerceAtLeast(1),
        height.coerceAtMost(up.height - oy).coerceAtLeast(1),
    )
}

private fun boxBlur(bitmap: Bitmap, radius: Int) {
    if (radius < 1) return
    val w = bitmap.width
    val h = bitmap.height
    val src = IntArray(w * h)
    val tmp = IntArray(w * h)
    bitmap.getPixels(src, 0, w, 0, 0, w, h)
    blurAxis(src, tmp, w, h, radius, true)
    blurAxis(tmp, src, w, h, radius, false)
    bitmap.setPixels(src, 0, w, 0, 0, w, h)
}

private fun blurAxis(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    val span = radius * 2 + 1
    if (horizontal) {
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var r = 0; var g = 0; var b = 0
            fun px(x: Int): Int = src[row + x.coerceIn(0, w - 1)]
            fun acc(p: Int, sign: Int) { a += sign * (p ushr 24); r += sign * ((p shr 16) and 255); g += sign * ((p shr 8) and 255); b += sign * (p and 255) }
            for (i in -radius..radius) acc(px(i), 1)
            for (x in 0 until w) {
                dst[row + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
                acc(px(x + radius + 1), 1)
                acc(px(x - radius), -1)
            }
        }
    } else {
        for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0
            fun px(y: Int): Int = src[y.coerceIn(0, h - 1) * w + x]
            fun acc(p: Int, sign: Int) { a += sign * (p ushr 24); r += sign * ((p shr 16) and 255); g += sign * ((p shr 8) and 255); b += sign * (p and 255) }
            for (i in -radius..radius) acc(px(i), 1)
            for (y in 0 until h) {
                dst[y * w + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
                acc(px(y + radius + 1), 1)
                acc(px(y - radius), -1)
            }
        }
    }
}

private fun snapshotView(view: View): Bitmap? {
    val root = view.rootView
    if (root.width <= 0 || root.height <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()
}

private suspend fun captureWindow(view: View): Bitmap? {
    val window = (view.context as? Activity)?.window ?: return snapshotView(view)
    val root = window.decorView
    if (root.width <= 0 || root.height <= 0) return snapshotView(view)
    return suspendCancellableCoroutine { cont ->
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { result ->
            cont.resume(if (result == PixelCopy.SUCCESS) bitmap else snapshotView(view))
        }, Handler(Looper.getMainLooper()))
    }
}

private fun clearPopupChrome(view: View) {
    val transparent = ColorDrawable(android.graphics.Color.TRANSPARENT)
    view.background = transparent
    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    val parent = view.parent as? ViewGroup ?: return
    parent.background = transparent
    parent.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    parent.clipChildren = false
    parent.clipToPadding = false
}
