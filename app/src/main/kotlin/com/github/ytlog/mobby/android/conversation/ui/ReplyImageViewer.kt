package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.ytlog.mobby.android.conversation.domain.AttachmentPreview
import com.github.ytlog.mobby.android.conversation.domain.DataResult
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings
import kotlin.math.roundToInt

@Composable internal fun ReplyImageViewer(
    source: String,
    name: String,
    load: suspend (Boolean) -> DataResult<AttachmentPreview>,
    close: () -> Unit,
) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = close) { Text(AppStrings.closePreview) }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    AttachmentImage(source, name, true, load, Modifier.fillMaxSize(), render = { bitmap, description, frame ->
                        ZoomableImage(bitmap, description, frame)
                    })
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                zoom = (zoom / ImageZoomStep).coerceAtLeast(1f)
                if (zoom == 1f) offset = Offset.Zero
            }, enabled = zoom > 1f) { Text(AppStrings.zoomOutImage) }
            Text("${(zoom * 100).roundToInt()}%", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { zoom = (zoom * ImageZoomStep).coerceAtMost(MaxImageZoom) }, enabled = zoom < MaxImageZoom) {
                Text(AppStrings.zoomInImage)
            }
        }
    }
}
