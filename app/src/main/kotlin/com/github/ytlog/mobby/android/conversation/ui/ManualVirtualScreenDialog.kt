package com.github.ytlog.mobby.android.conversation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import android.view.WindowManager
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
    // The page window never takes focus, so Back reaches the activity below it.
    BackHandler(enabled = inputTarget == null) { if (canOperatePreview) onMinimize() }
    Dialog(onDismissRequest = { if (canOperatePreview) onMinimize() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false)) {
        // A field tapped in the virtual screen opens the keyboard on the main screen. Android hides it when a window
        // that may use the input method gains focus, and layers it below a focusable window that opts out of it, so
        // this page never takes focus and still lets the keyboard sit above it.
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        ManualVirtualScreenPanel(
            bitmap = bitmap,
            error = if (inputTarget == null) error ?: screenError else null,
            enabled = canOperatePreview,
            screenTouch = Modifier.pointerInput(id) { forwardScreenTouches { startTouch(it) } },
            onMinimize = onMinimize,
            onBack = { execute { controller.manualInput(id, ManualScreenInput.Back) } },
            onEnterText = { execute { inputTarget = controller.prepareManualText(id) } },
            onHandBack = { execute { controller.handBack(id) } },
        )
    }
    inputTarget?.let { target ->
        val focus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { if (!busy) closeEditor() },
            properties = DialogProperties(dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn),
            icon = { AppIcon(AppIcons.Keyboard, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) },
            title = { Text(UiStrings.virtualScreenManualInput) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { if (it.length <= 2000) text = it }, Modifier.fillMaxWidth().focusRequester(focus),
                        label = { Text(target.ifBlank { UiStrings.virtualScreenManualInput }) },
                        supportingText = { Text(UiStrings.virtualScreenManualTargetHelp) },
                        singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    error?.let { ScreenErrorBanner(it) }
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

/** The full-screen takeover page: who is in control, what to do, the live display and the actions that act on it. */
@Composable
internal fun ManualVirtualScreenPanel(
    bitmap: ImageBitmap?,
    error: String?,
    enabled: Boolean,
    screenTouch: Modifier,
    onMinimize: () -> Unit,
    onBack: () -> Unit,
    onEnterText: () -> Unit,
    onHandBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = conversationInk()
    val muted = toolCallInk()
    Surface(Modifier.fillMaxSize(), color = conversationCanvas(), contentColor = ink) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    AppIcon(AppIcons.Hand, null, Modifier.size(20.dp), tint = colors.primary)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    Text(UiStrings.virtualScreenTakeOver, style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold), color = ink)
                    Row(verticalAlignment = Alignment.Top) {
                        // Pinned to the first line so a wrapped status still reads as one bullet.
                        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(statusAccentColor()))
                        Text(UiStrings.virtualScreenInUserControl, Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                TextButton(onClick = onMinimize, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    AppIcon(AppIcons.ChevronDown, null, Modifier.size(16.dp))
                    Text(UiStrings.virtualScreenMinimizeManual, Modifier.padding(start = 4.dp), maxLines = 1)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(conversationCardColor())
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppIcons.Check, null, Modifier.size(16.dp), tint = colors.primary)
                    Text(UiStrings.virtualScreenHandoffPrompt, Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = ink)
                }
                // Text typed on the display's own keyboard is not redacted after handback; the local editor's is.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Top) {
                    AppIcon(AppIcons.Keyboard, null, Modifier.padding(top = 1.dp).size(14.dp), tint = muted)
                    Text(UiStrings.virtualScreenManualPrivateHint, Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
                error?.let { ScreenErrorBanner(it) }
            }
            ScreenFrame(bitmap, screenTouch, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ScreenTool(AppIcons.Back, UiStrings.back, enabled, onBack)
                ScreenTool(AppIcons.Keyboard, UiStrings.virtualScreenManualInput, enabled, onEnterText)
                Button(onClick = onHandBack, enabled = enabled, modifier = Modifier.weight(1f).padding(start = 8.dp).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Text(UiStrings.virtualScreenHandBack, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The live display in a thin device bezel, sized to the largest frame that keeps the display's own aspect ratio. */
@Composable
private fun ScreenFrame(bitmap: ImageBitmap?, screenTouch: Modifier, modifier: Modifier) {
    val bezel = 5.dp
    val dark = darkChrome()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (9f / 19.5f)
        val width = minOf(maxWidth - bezel * 2, (maxHeight - bezel * 2) * ratio).coerceAtLeast(0.dp)
        Surface(shape = RoundedCornerShape(26.dp), color = if (dark) Color(0xFF2A2B30) else Color(0xFF16171A),
            shadowElevation = if (dark) 0.dp else 10.dp,
            border = if (dark) BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)) else null) {
            Box(Modifier.padding(bezel).width(width).aspectRatio(ratio).clip(RoundedCornerShape(21.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                if (bitmap == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Text(UiStrings.virtualScreenWaitingForFrame, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Image(bitmap, UiStrings.virtualScreenPreview, Modifier.fillMaxSize().then(screenTouch))
                }
            }
        }
    }
}

@Composable
private fun ScreenTool(icon: AppGlyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = conversationInk().copy(alpha = if (enabled) 1f else 0.38f)
    Column(Modifier.widthIn(min = 64.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        AppIcon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun ScreenErrorBanner(message: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.errorContainer)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(AppIcons.Error, null, Modifier.size(16.dp), tint = colors.onErrorContainer)
        Text(message, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
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
