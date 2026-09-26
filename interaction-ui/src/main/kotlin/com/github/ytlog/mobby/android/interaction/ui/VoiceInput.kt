package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.github.ytlog.mobby.android.speech.SpeechEngine
import com.github.ytlog.mobby.android.speech.SpeechEngines
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.withTimeoutOrNull

internal val VoiceCancelDistance = 72.dp
internal const val VoiceSpectrumBars = 96

/** Download or load of the on-device model. A null [fraction] means the size is not known yet. */
internal class VoiceModelTransfer(val read: Long, val total: Long, val loading: Boolean = false) {
    val fraction: Float?
        get() = when {
            total <= 0L -> null
            loading -> 1f
            else -> (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    val label: String
        get() = when {
            loading -> AppStrings.loadingSpeechModel
            total > 0L || read > 0L -> AppStrings.downloadingSpeechModel
            else -> AppStrings.preparingSpeechModel
        }

    val percent: String?
        get() = fraction?.let { "${(it * 100).toInt().coerceIn(0, 100)}%" }

    val size: String?
        get() = when {
            total > 0L -> "${voiceSizeLabel(read.coerceAtMost(total))} / ${voiceSizeLabel(total)}"
            read > 0L -> voiceSizeLabel(read)
            else -> null
        }
}

internal fun voiceSizeLabel(bytes: Long): String {
    val tenths = (bytes.coerceAtLeast(0L) * 10 + 524_288) / 1_048_576
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (whole >= 100) "$whole MB" else "$whole.$fraction MB"
}

internal fun rmsToLevel(rmsdB: Float): Float {
    if (rmsdB.isNaN()) return 0f
    return ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
}

/** Bar heights for the hold-to-talk spectrum. Speech drives the middle from the floor to the top; both edges stay short. */
internal fun spectrumBars(level: Float, time: Float, count: Int = VoiceSpectrumBars): FloatArray {
    val boosted = kotlin.math.sqrt(level.coerceIn(0f, 1f)).coerceIn(0f, 1f).let { (it * 1.35f).coerceAtMost(1f) }
    return FloatArray(count) { index ->
        val x = if (count <= 1) 0.5f else index / (count - 1f)
        val shaped = (1f - abs(x * 2f - 1f)).coerceIn(0f, 1f)
        val envelope = shaped * shaped
        val wave = sin(index * 0.42f + time * (7.5f + (index % 5) * 1.4f))
        val flutter = (wave + 1f) / 2f
        val travel = (0.08f + 0.92f * boosted) * envelope
        (0.02f + travel * flutter).coerceIn(0f, 1f)
    }
}

/** One capture. The speech engine and its model are created only from [start], so composing the composer does not load native code. */
internal class VoiceCapture(context: Context) {
    var phase by mutableStateOf("idle")
    var level by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var transfer by mutableStateOf<VoiceModelTransfer?>(null)
    var liveTranscript by mutableStateOf("")
    var formattedTranscript by mutableStateOf("")
    var formattedSource by mutableStateOf("")
    val displayTranscript: String get() = if (formattedSource.isNotBlank() && liveTranscript.startsWith(formattedSource))
        formattedTranscript + liveTranscript.removePrefix(formattedSource) else liveTranscript
    var onTranscript: (String) -> Unit = {}
    var onPause: (String) -> Unit = {}
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var engine: SpeechEngine? = null
    private var generation = 0
    private var listening = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) cancel(AppStrings.anotherAppIsUsingAudioRecordingStopped) }.build()

    fun start(): Boolean {
        val speech = engine ?: SpeechEngines.create(app).also { engine = it }
        speech.stop(false)
        error = null
        level = 0f
        liveTranscript = ""
        formattedTranscript = ""
        formattedSource = ""
        val token = ++generation
        listening = true
        if (speech.ready) {
            transfer = null
            return beginListening(token)
        }
        phase = "preparing"
        if (transfer == null) transfer = VoiceModelTransfer(0, 0)
        speech.prepare(
            onProgress = { read, total ->
                if (token != generation || phase == "recording" || phase == "transcribing") return@prepare
                transfer = VoiceModelTransfer(read, total)
            },
            onLoading = {
                if (token != generation || phase == "recording" || phase == "transcribing") return@prepare
                val current = transfer
                transfer = VoiceModelTransfer(current?.read ?: 0, current?.total ?: 0, loading = true)
            },
            onReady = {
                if (token != generation) return@prepare
                val resume = listening && phase == "preparing"
                transfer = null
                if (resume) beginListening(token)
            },
            onError = { message ->
                if (token != generation) return@prepare
                transfer = null
                fail(message)
            },
        )
        return phase == "preparing" || phase == "recording"
    }

    fun finish() {
        if (phase == "preparing") {
            listening = false
            phase = "idle"
            level = 0f
            return
        }
        if (phase != "recording") return
        phase = "transcribing"
        level = 0f
        engine?.stop(true)
    }

    fun cancel(reason: String? = null) {
        val active = phase == "recording" || phase == "transcribing"
        generation++
        listening = false
        engine?.stop(false)
        abandonFocus()
        phase = "idle"
        level = 0f
        liveTranscript = ""
        formattedTranscript = ""
        formattedSource = ""
        transfer = null
        if (active && reason != null) error = reason
    }

    fun release() {
        cancel()
        engine?.close()
        engine = null
    }

    private fun beginListening(token: Int): Boolean {
        if (token != generation || !listening) return false
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(AppStrings.cannotObtainAudioFocusRetryLater)
            return false
        }
        phase = "recording"
        transfer = null
        engine?.listen(
            onLevel = { if (token == generation && phase == "recording") level = it },
            onPartial = { if (token == generation && phase == "recording") liveTranscript = it },
            onSegment = { if (token == generation && phase == "recording" && liveTranscript.isNotBlank()) onPause(liveTranscript) },
            onFinal = { text ->
                if (token != generation) return@listen
                abandonFocus()
                phase = "idle"
                level = 0f
                liveTranscript = ""
                transfer = null
                listening = false
                generation++
                if (text.isBlank()) error = AppStrings.noSpeechRecognizedRecordAgain else onTranscript(text)
            },
            onError = { message ->
                if (token != generation) return@listen
                fail(message)
            },
        )
        return phase == "recording"
    }

    private fun fail(message: String) {
        generation++
        listening = false
        abandonFocus()
        phase = "idle"
        level = 0f
        liveTranscript = ""
        formattedTranscript = ""
        formattedSource = ""
        transfer = null
        error = message
    }

    private fun abandonFocus() {
        audio.abandonAudioFocusRequest(focus)
    }

    fun showFormatted(source: String, formatted: String) {
        if (phase == "recording" && source.length >= formattedSource.length && liveTranscript.startsWith(source) && formatted.isNotBlank()) {
            formattedSource = source
            formattedTranscript = formatted
        }
    }
}

@Composable internal fun rememberVoiceCapture(): VoiceCapture {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val capture = remember { VoiceCapture(context) }
    DisposableEffect(lifecycle, capture) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) capture.cancel(AppStrings.appMovedToBackgroundRecordingStopped)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            capture.release()
        }
    }
    return capture
}

@Composable internal fun Modifier.voiceHold(
    enabled: Boolean,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldMove: (Boolean) -> Unit,
    onHoldEnd: (Boolean) -> Unit,
): Modifier {
    val tap = rememberUpdatedState(onTap)
    val start = rememberUpdatedState(onHoldStart)
    val move = rememberUpdatedState(onHoldMove)
    val end = rememberUpdatedState(onHoldEnd)
    val slop = with(LocalDensity.current) { VoiceCancelDistance.toPx() }
    return pointerInput(enabled, slop) {
        if (!enabled) return@pointerInput
        detectVoiceHold(slop, { tap.value() }, { start.value() }, { move.value(it) }, { end.value(it) })
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectVoiceHold(
    cancelSlop: Float,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldMove: (Boolean) -> Unit,
    onHoldEnd: (Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val holding = booleanArrayOf(false)
        var cancelArmed = false
        try {
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
            if (released != null) {
                onTap()
                return@awaitEachGesture
            }
            val stillDown = currentEvent.changes.any { it.id == down.id && it.pressed }
            if (!stillDown) return@awaitEachGesture
            holding[0] = true
            onHoldStart()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    onHoldEnd(cancelArmed)
                    holding[0] = false
                    return@awaitEachGesture
                }
                val armed = change.position.y - down.position.y <= -cancelSlop
                if (armed != cancelArmed) {
                    cancelArmed = armed
                    onHoldMove(cancelArmed)
                }
                change.consume()
            }
        } catch (cancelled: CancellationException) {
            if (holding[0]) onHoldEnd(true)
            throw cancelled
        }
    }
}

@Composable internal fun VoiceModelProgress(transfer: VoiceModelTransfer, modifier: Modifier = Modifier) {
    val fraction = transfer.fraction
    val animated by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "voice-model-progress",
    )
    val primary = MaterialTheme.colorScheme.primary
    val track = voiceTrack()
    val reduced = rememberReducedMotion()
    val slide = if (fraction != null || reduced) 0f else {
        val pulse = rememberInfiniteTransition(label = "voice-model-progress")
        val value by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "voice-model-progress-slide")
        value
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(transfer.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            transfer.size?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
            transfer.percent?.let {
                Text(it, Modifier.padding(start = 10.dp), color = primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(7.dp))
        Canvas(
            Modifier.fillMaxWidth().height(3.dp).semantics {
                contentDescription = AppStrings.speechModelDownloadProgress
                progressBarRangeInfo = if (fraction != null) ProgressBarRangeInfo(fraction, 0f..1f) else ProgressBarRangeInfo.Indeterminate
            },
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(track, cornerRadius = radius)
            when {
                fraction != null -> {
                    val width = size.width * animated
                    if (width > 0f) drawRoundRect(primary, size = Size(width.coerceAtLeast(size.height), size.height), cornerRadius = radius)
                }
                reduced -> drawRoundRect(primary.copy(alpha = 0.45f), size = Size(size.width * 0.36f, size.height), cornerRadius = radius)
                else -> clipRect {
                    val band = size.width * 0.28f
                    val start = (size.width + band) * slide - band
                    drawRoundRect(primary, topLeft = Offset(start, 0f), size = Size(band, size.height), cornerRadius = radius)
                }
            }
        }
    }
}

@Composable internal fun VoiceRecordingOverlay(cancelArmed: Boolean, level: Float, transcript: String, modifier: Modifier = Modifier) {
    val wash = voiceWash(cancelArmed)
    val hint = if (cancelArmed) AppStrings.releaseToCancel else AppStrings.releaseToSendSwipeUpToCancel
    Box(
        modifier.fillMaxWidth().heightIn(min = 168.dp).background(Brush.verticalGradient(listOf(Color.Transparent, wash))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (transcript.isNotBlank()) Text(transcript, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
            Text(hint, color = if (cancelArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            VoiceSpectrum(level, cancelArmed, Modifier.fillMaxWidth())
        }
    }
}

@Composable internal fun VoiceSpectrum(level: Float, cancelArmed: Boolean, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val time = if (reduced) 0f else {
        val pulse = rememberInfiniteTransition(label = "voice-spectrum")
        val value by pulse.animateFloat(0f, 64f, infiniteRepeatable(tween(64_000, easing = LinearEasing), RepeatMode.Reverse), label = "voice-spectrum-time")
        value
    }
    val bars = spectrumBars(level, time)
    val color = if (cancelArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Canvas(modifier.height(96.dp).semantics { contentDescription = AppStrings.recordingWaveform }) {
        val count = bars.size
        val gap = 1.5.dp.toPx()
        val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.dp.toPx())
        val minH = 2.dp.toPx()
        bars.forEachIndexed { index, value ->
            val height = minH + (size.height - minH) * value
            val x = index * (width + gap) + width / 2f
            drawLine(color, Offset(x, (size.height - height) / 2f), Offset(x, (size.height + height) / 2f), width, StrokeCap.Round)
        }
    }
}

@Composable internal fun VoiceComposerBar(
    voiceMode: Boolean,
    recording: Boolean,
    cancelArmed: Boolean,
    level: Float,
    transcript: String,
    enabled: Boolean,
    micAvailable: Boolean,
    stop: Boolean,
    stopEnabled: Boolean,
    sendEnabled: Boolean,
    onAdd: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
    onEnterVoice: () -> Unit,
    onExitVoice: () -> Unit,
    onHoldTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldMove: (Boolean) -> Unit,
    onHoldEnd: (Boolean) -> Unit,
    textField: @Composable RowScope.() -> Unit,
) {
    val hold = voiceMode && micAvailable && !stop
    Box(Modifier.fillMaxWidth()) {
        if (recording) VoiceRecordingOverlay(cancelArmed, level, transcript, Modifier.align(Alignment.BottomCenter))
        ComposerShell(
            Modifier.alpha(if (recording) 0f else 1f).align(Alignment.BottomCenter)
                .then(if (recording) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            ActionIcon(AppStrings.addContentAndCapabilities, onAdd, AppIcons.Plus, enabled = enabled && !recording)
            if (hold) {
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).testTag("hold-to-speak").voiceHold(enabled, onHoldTap, onHoldStart, onHoldMove, onHoldEnd),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(AppStrings.holdToSpeak2, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                ActionIcon(AppStrings.keyboardInput, onExitVoice, AppIcons.Keyboard, enabled = enabled)
            } else {
                textField()
                when {
                    stop -> ActionIcon(AppStrings.stopCurrentTask, onStop, AppIcons.Stop, enabled = stopEnabled, filled = true)
                    micAvailable -> HoldIcon(AppStrings.voiceInput, AppIcons.Mic, enabled, "voice-mic", onEnterVoice, onHoldStart, onHoldMove, onHoldEnd)
                    else -> ActionIcon(AppStrings.sendTask, onSend, AppIcons.Send, enabled = sendEnabled, filled = true)
                }
            }
        }
    }
}

@Composable internal fun ComposerShell(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val dark = darkChrome()
    Surface(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (dark) 0.dp else 12.dp, bottom = 10.dp)
            .then(if (dark) Modifier else Modifier.lightInputShadow(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = buttonColor(),
        contentColor = onButtonColor(),
        shadowElevation = if (dark) floatingElevation() else 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom, content = content)
    }
}

@Composable private fun HoldIcon(
    label: String,
    icon: AppGlyph,
    enabled: Boolean,
    tag: String,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldMove: (Boolean) -> Unit,
    onHoldEnd: (Boolean) -> Unit,
) {
    Box(
        Modifier.size(ToolbarControl).testTag(tag).voiceHold(enabled, onTap, onHoldStart, onHoldMove, onHoldEnd).semantics {
            role = Role.Button
            contentDescription = label
            if (enabled) onClick(label) { onTap(); true }
        },
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, null, Modifier.size(22.dp), tint = onButtonColor().copy(alpha = if (enabled) 1f else 0.38f))
    }
}
