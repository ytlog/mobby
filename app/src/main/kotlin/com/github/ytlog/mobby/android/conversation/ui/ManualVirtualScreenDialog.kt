package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.github.ytlog.mobby.android.runtime.api.device.ManualScreenInput
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlPort
import com.github.ytlog.mobby.android.runtime.api.device.ScreenTouch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ManualVirtualScreenDialog(controller: ScreenControlPort, id: String, bitmap: ImageBitmap?, screenError: String?,
    onMinimize: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember(id) { mutableStateOf(false) }
    var inputTarget by remember(id) { mutableStateOf<String?>(null) }
    var text by remember(id) { mutableStateOf("") }
    var error by remember(id) { mutableStateOf<String?>(null) }
    fun execute(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: UiStrings.virtualScreenOperationFailed }
            finally { busy = false }
        }
    }
    val submitGesture by rememberUpdatedState<(List<ScreenTouch>) -> Unit>({ points ->
        execute { controller.manualInput(id, ManualScreenInput.Gesture(points)) }
    })
    Dialog(onDismissRequest = { if (!busy) onMinimize() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(UiStrings.virtualScreenTakeOver, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onMinimize, enabled = !busy) { Text(UiStrings.virtualScreenMinimizeManual) }
                }
                Text(UiStrings.virtualScreenInUserControl, style = MaterialTheme.typography.bodySmall)
                Text(UiStrings.virtualScreenHandoffPrompt, style = MaterialTheme.typography.bodySmall)
                (error ?: screenError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center) {
                    if (bitmap == null) {
                        Text(UiStrings.virtualScreenWaitingForFrame)
                    } else {
                        val ratio = bitmap.width.toFloat() / bitmap.height
                        val width = minOf(maxWidth, maxHeight * ratio)
                        val touch = if (!busy && inputTarget == null) Modifier.pointerInput(id) { recordScreenTouches { submitGesture(it) } } else Modifier
                        Image(bitmap, UiStrings.virtualScreenPreview, Modifier.width(width).aspectRatio(ratio).then(touch))
                    }
                }
                inputTarget?.let { target ->
                    OutlinedTextField(text, { if (it.length <= 2000) text = it }, Modifier.fillMaxWidth(),
                        label = { Text(target.ifBlank { UiStrings.virtualScreenManualInput }) },
                        supportingText = { Text(UiStrings.virtualScreenManualTargetHelp) },
                        singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    TextButton(onClick = {
                        val value = text
                        execute {
                            controller.manualInput(id, ManualScreenInput.Text(value))
                            text = ""
                            inputTarget = null
                        }
                    }, enabled = !busy && text.isNotEmpty()) { Text(UiStrings.virtualScreenManualFill) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { execute { controller.manualInput(id, ManualScreenInput.Back) } }, enabled = !busy && inputTarget == null) {
                        Text(UiStrings.back)
                    }
                    TextButton(onClick = {
                        if (inputTarget != null) {
                            inputTarget = null
                            text = ""
                            error = null
                        } else execute { inputTarget = controller.prepareManualText(id) }
                    }, enabled = !busy) {
                        Text(if (inputTarget != null) UiStrings.cancel else UiStrings.virtualScreenManualInput)
                    }
                    TextButton(onClick = { text = ""; execute { controller.handBack(id) } }, enabled = !busy) {
                        Text(UiStrings.virtualScreenHandBack)
                    }
                }
            }
        }
    }
}

private suspend fun PointerInputScope.recordScreenTouches(submit: (List<ScreenTouch>) -> Unit) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = true)
    if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
    fun sample(x: Float, y: Float, time: Long) = ScreenTouch((x / size.width).coerceIn(0f, 1f),
        (y / size.height).coerceIn(0f, 1f), (time - down.uptimeMillis).coerceIn(0, 5_000))
    val points = mutableListOf(sample(down.position.x, down.position.y, down.uptimeMillis))
    down.consume()
    while (true) {
        val event = awaitPointerEvent()
        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
        if (pointer.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
        val point = sample(pointer.position.x, pointer.position.y, pointer.uptimeMillis)
        pointer.consume()
        if (!pointer.pressed) {
            points += point
            submit(points)
            break
        }
        if (points.size < 255 && point.elapsedMillis - points.last().elapsedMillis >= 20) points += point
    }
}
