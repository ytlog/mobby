package com.mobby.interaction.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** One user-started capture. Closing, backgrounding and focus loss invalidate late callbacks. */
private class VoiceCapture(context: Context) {
    var phase by mutableStateOf("idle")
    var transcript by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    val available = SpeechRecognizer.isRecognitionAvailable(context)
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) cancel("音频被其他应用占用，录音已停止") }.build()
    fun cancel(reason: String? = null) {
        generation++
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        audio.abandonAudioFocusRequest(focus)
        if (phase == "recording" || phase == "transcribing") { phase = "idle"; error = reason }
    }
    fun start() {
        cancel()
        if (!available) { error = "设备没有可用的语音识别服务，请使用文字输入"; return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { error = "无法获取音频焦点，请稍后重试"; return }
        val token = ++generation
        try {
            val service = SpeechRecognizer.createSpeechRecognizer(app)
            recognizer = service
            service.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEndOfSpeech() { if (token == generation) phase = "transcribing" }
                override fun onError(code: Int) {
                    if (token != generation) return
                    cancel()
                    error = when (code) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限未授予，请使用文字输入或重试授权"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未识别到文字，请重新录音"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务连接失败，请检查网络后重试"
                        else -> "转写失败（$code），请重试或返回文字输入"
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (token != generation) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    cancel()
                    if (text.isBlank()) error = "未识别到文字，请重新录音"
                    else { transcript = text; phase = "editing"; error = null }
                }
            })
            phase = "recording"; error = null
            service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        } catch (_: Exception) { cancel(); phase = "idle"; error = "无法启动语音识别，请使用文字输入" }
    }
    fun finish() { if (phase == "recording") { phase = "transcribing"; recognizer?.stopListening() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VoiceInputSheet(onDismiss: () -> Unit, insert: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val capture = remember { VoiceCapture(context) }
    var alive by remember { mutableStateOf(true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (alive) { if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) capture.start()
            else capture.error = "麦克风权限未授予或页面已离开，草稿保持不变" }
    }
    DisposableEffect(lifecycle, capture) {
        alive = true
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) capture.cancel("应用进入后台，录音已停止") }
        lifecycle.addObserver(observer)
        onDispose { alive = false; lifecycle.removeObserver(observer); capture.cancel() }
    }
    VoiceInputPanel(capture.phase, capture.transcript, capture.error, capture.available,
        onTranscript = { capture.transcript = it }, onFinish = capture::finish,
        onInsert = { if (insert(capture.transcript)) onDismiss() else capture.error = "原草稿已改变，未插入文字；请复制转写内容后返回" },
        onStart = {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) capture.start()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }, onDismiss = onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VoiceInputPanel(phase: String, transcript: String, error: String?, available: Boolean,
    onTranscript: (String) -> Unit, onFinish: () -> Unit, onInsert: () -> Unit, onStart: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = raisedColor()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("语音输入", style = MaterialTheme.typography.titleLarge)
            Text("使用设备的语音识别服务。转写后可校对，放入输入框后由你发送。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (phase) {
                "recording" -> { Text("正在录音…"); Button(onClick = onFinish) { Text("结束录音") } }
                "transcribing" -> { Text("正在转写…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                "editing" -> {
                    OutlinedTextField(transcript, onTranscript, Modifier.fillMaxWidth(), label = { Text("编辑校对") }, maxLines = 5)
                    Button(onClick = onInsert, enabled = transcript.isNotBlank()) { Text("放入输入框") }
                }
            }
            if (phase == "idle" || phase == "editing") Button(onClick = onStart, enabled = available) { Text(if (phase == "editing") "重新录音" else "开始录音") }
            if (!available) Text("设备没有可用的语音识别服务，请使用文字输入")
            TextButton(onClick = onDismiss) { Text("取消，保留原草稿") }
        }
    }
}
