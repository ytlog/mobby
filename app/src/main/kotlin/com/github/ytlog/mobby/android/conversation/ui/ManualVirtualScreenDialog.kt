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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
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
import com.github.ytlog.mobby.android.runtime.api.device.ScreenTouchPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ManualVirtualScreenDialog(controller: ScreenControlPort, id: String, bitmap: ImageBitmap?, screenError: String?,
    onMinimize: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember(id) { mutableStateOf(false) }
    var inputTarget by remember(id) { mutableStateOf<String?>(null) }
    var text by remember(id) { mutableStateOf("") }
    var error by remember(id) { mutableStateOf<String?>(null) }
    fun closeEditor() {
        inputTarget = null
        text = ""
        error = null
    }
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
    val startTouch by rememberUpdatedState<(ManualScreenInput.Touch) -> Channel<ManualScreenInput>?>({ down ->
        if (busy || inputTarget != null) null else {
            // Keep the latest position while the previous request is in flight.
            // DOWN is sent separately, and UP carries the final position.
            val updates = Channel<ManualScreenInput>(Channel.CONFLATED)
            execute {
                var finished = false
                try {
                    controller.manualInput(id, down)
                    for (input in updates) {
                        controller.manualInput(id, input)
                        finished = input == ManualScreenInput.CancelTouch ||
                            (input is ManualScreenInput.Touch && input.phase == ScreenTouchPhase.UP)
                        if (finished) break
                    }
                } finally {
                    updates.cancel()
                    if (!finished) withContext(NonCancellable) {
                        // Best effort if the display itself has already disconnected.
                        runCatching { controller.manualInput(id, ManualScreenInput.CancelTouch) }
                    }
                }
            }
            updates
        }
    })
    val canOperatePreview = !busy && inputTarget == null
    Dialog(onDismissRequest = { if (canOperatePreview) onMinimize() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(UiStrings.virtualScreenTakeOver, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onMinimize, enabled = canOperatePreview) { Text(UiStrings.virtualScreenMinimizeManual) }
                }
                Text(UiStrings.virtualScreenInUserControl, style = MaterialTheme.typography.bodySmall)
                Text(UiStrings.virtualScreenHandoffPrompt, style = MaterialTheme.typography.bodySmall)
                if (inputTarget == null) (error ?: screenError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center) {
                    if (bitmap == null) {
                        Text(UiStrings.virtualScreenWaitingForFrame)
                    } else {
                        val ratio = bitmap.width.toFloat() / bitmap.height
                        val width = minOf(maxWidth, maxHeight * ratio)
                        val touch = Modifier.pointerInput(id) { forwardScreenTouches { startTouch(it) } }
                        Image(bitmap, UiStrings.virtualScreenPreview, Modifier.width(width).aspectRatio(ratio).then(touch))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { execute { controller.manualInput(id, ManualScreenInput.Back) } }, enabled = canOperatePreview) {
                        Text(UiStrings.back)
                    }
                    TextButton(onClick = { execute { inputTarget = controller.prepareManualText(id) } }, enabled = canOperatePreview) {
                        Text(UiStrings.virtualScreenManualInput)
                    }
                    TextButton(onClick = { execute { controller.handBack(id) } }, enabled = canOperatePreview) {
                        Text(UiStrings.virtualScreenHandBack)
                    }
                }
            }
        }
    }
    inputTarget?.let { target ->
        val focus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { if (!busy) closeEditor() },
            properties = DialogProperties(dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(UiStrings.virtualScreenManualInput) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { if (it.length <= 2000) text = it }, Modifier.fillMaxWidth().focusRequester(focus),
                        label = { Text(target.ifBlank { UiStrings.virtualScreenManualInput }) },
                        supportingText = { Text(UiStrings.virtualScreenManualTargetHelp) },
                        singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                LaunchedEffect(focus) { focus.requestFocus() }
            },
            dismissButton = {
                TextButton(onClick = ::closeEditor, enabled = !busy) { Text(UiStrings.cancel) }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = text
                    execute {
                        controller.manualInput(id, ManualScreenInput.Text(value))
                        closeEditor()
                    }
                }, enabled = !busy && text.isNotEmpty()) { Text(UiStrings.virtualScreenManualFill) }
            },
        )
    }
}

private suspend fun PointerInputScope.forwardScreenTouches(
    start: (ManualScreenInput.Touch) -> Channel<ManualScreenInput>?,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = true)
    if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
    fun touch(phase: ScreenTouchPhase, point: Offset) =
        ManualScreenInput.Touch(phase, (point.x / size.width).coerceIn(0f, 1f), (point.y / size.height).coerceIn(0f, 1f))
    val updates = start(touch(ScreenTouchPhase.DOWN, down.position)) ?: return@awaitEachGesture
    down.consume()
    var released = false
    try {
        while (true) {
            val event = awaitPointerEvent()
            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
            if (pointer.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
            pointer.consume()
            if (!pointer.pressed) {
                updates.trySend(touch(ScreenTouchPhase.UP, pointer.position))
                released = true
                break
            }
            if (pointer.position != pointer.previousPosition) {
                updates.trySend(touch(ScreenTouchPhase.MOVE, pointer.position))
            }
        }
    } finally {
        if (!released) updates.trySend(ManualScreenInput.CancelTouch)
        updates.close()
    }
}
