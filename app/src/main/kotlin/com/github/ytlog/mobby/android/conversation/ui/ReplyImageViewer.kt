package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.ytlog.mobby.android.conversation.domain.AttachmentPreview
import com.github.ytlog.mobby.android.conversation.domain.DataResult
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings
import kotlin.math.roundToInt

private val ImageViewerColors = darkColorScheme(
    surface = Color(0xFF111214),
    surfaceContainer = Color(0xFF232529),
    onSurface = Color(0xFFF0F0F2),
    onSurfaceVariant = Color(0xFFB7B9C0),
    primary = Color(0xFFE6E9F1),
)

@Composable internal fun ReplyImageViewer(
    source: String,
    name: String,
    load: suspend (Boolean) -> DataResult<AttachmentPreview>,
    close: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(colorScheme = ImageViewerColors) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                    FilledIconButton(
                        onClick = close,
                        modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        AppIcon(AppIcons.Close, AppStrings.closePreview, Modifier.size(22.dp))
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        AttachmentImage(source, name, true, load, Modifier.fillMaxSize(), render = { bitmap, description, frame ->
                            ZoomableImage(bitmap, description, frame)
                        })
                    }
                    if (actions != null) Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) { actions() }
                }
            }
        }
    }
}

private const val MaxImageZoom = 4f
private const val ImageZoomStep = 1.5f

@Composable private fun ZoomableImage(bitmap: ImageBitmap, description: String, modifier: Modifier) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds(), contentAlignment = Alignment.Center) {
            val viewportWidth = constraints.maxWidth.toFloat()
            val viewportHeight = constraints.maxHeight.toFloat()
            val fit = minOf(viewportWidth / bitmap.width, viewportHeight / bitmap.height)
            val width = bitmap.width * fit
            val height = bitmap.height * fit
            fun bounded(position: Offset, scale: Float): Offset {
                val x = (width * scale - viewportWidth).coerceAtLeast(0f) / 2
                val y = (height * scale - viewportHeight).coerceAtLeast(0f) / 2
                return Offset(position.x.coerceIn(-x, x), position.y.coerceIn(-y, y))
            }
            Box(Modifier.fillMaxSize().pointerInput(viewportWidth, viewportHeight, bitmap) {
                val center = Offset(viewportWidth / 2, viewportHeight / 2)
                detectTransformGestures { centroid, pan, scale, _ ->
                    val nextZoom = (zoom * scale).coerceIn(1f, MaxImageZoom)
                    val anchor = centroid - center
                    offset = bounded(anchor + (bounded(offset, zoom) - anchor) * (nextZoom / zoom) + pan, nextZoom)
                    zoom = nextZoom
                }
            }, contentAlignment = Alignment.Center) {
                val (imageWidth, imageHeight) = with(LocalDensity.current) { width.toDp() to height.toDp() }
                Image(bitmap, description, Modifier.size(imageWidth, imageHeight).graphicsLayer {
                    val position = bounded(offset, zoom)
                    scaleX = zoom
                    scaleY = zoom
                    translationX = position.x
                    translationY = position.y
                })
            }
        }
        Surface(
            Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    zoom = (zoom / ImageZoomStep).coerceAtLeast(1f)
                    if (zoom == 1f) offset = Offset.Zero
                }, enabled = zoom > 1f) {
                    AppIcon(AppIcons.Minus, AppStrings.zoomOutImage, Modifier.size(20.dp))
                }
                Text("${(zoom * 100).roundToInt()}%", Modifier.widthIn(min = 52.dp), style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center)
                IconButton(onClick = { zoom = (zoom * ImageZoomStep).coerceAtMost(MaxImageZoom) }, enabled = zoom < MaxImageZoom) {
                    AppIcon(AppIcons.Plus, AppStrings.zoomInImage, Modifier.size(20.dp))
                }
            }
        }
    }
}
