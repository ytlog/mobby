package com.mobby.interaction.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

@Composable internal fun RaisedDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val raised = raisedColor()
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = raised), shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(20.dp))) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)
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
    val x = margin
    val y = if (anchor.bottom > 0) anchor.bottom + gap else margin + with(density) { 56.dp.roundToPx() }
    val widthPx = (host.resources.displayMetrics.widthPixels - margin * 2).coerceAtLeast(1)
    val widthDp = with(density) { widthPx.toDp() }
    val scrim = if (night) Color(0x731C1C1C) else Color(0x80FFFFFF)
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { applyClearCardWindow(view, x, y, widthPx) }
        Box(Modifier.width(widthDp).clip(RoundedCornerShape(22.dp))) {
            Box(Modifier.matchParentSize()) {
                snapshot?.let { image ->
                    Image(
                        image,
                        contentDescription = null,
                        modifier = Modifier
                            .requiredWidth(with(density) { image.width.toDp() })
                            .requiredHeight(with(density) { image.height.toDp() })
                            .offset { IntOffset(-x, -y) }
                            .blur(24.dp),
                    )
                }
                Box(Modifier.fillMaxSize().background(scrim))
            }
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

private fun snapshotView(view: View): ImageBitmap? {
    val root = view.rootView
    if (root.width <= 0 || root.height <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()
}

private suspend fun captureWindow(view: View): ImageBitmap? {
    val window = (view.context as? Activity)?.window ?: return snapshotView(view)
    val root = window.decorView
    if (root.width <= 0 || root.height <= 0) return snapshotView(view)
    return suspendCancellableCoroutine { cont ->
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { result ->
            cont.resume(if (result == PixelCopy.SUCCESS) bitmap.asImageBitmap() else snapshotView(view))
        }, Handler(Looper.getMainLooper()))
    }
}

private fun applyClearCardWindow(view: View, x: Int, y: Int, width: Int) {
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    window.setDimAmount(0f)
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(0)
    window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
    window.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    (view.parent as? View)?.let { parent ->
        parent.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        (parent as? ViewGroup)?.setPadding(0, 0, 0, 0)
    }
    window.setGravity(Gravity.TOP or Gravity.START)
    window.attributes = window.attributes.apply {
        this.width = width
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        dimAmount = 0f
        flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv() and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
    }
}
