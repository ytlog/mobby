package com.github.ytlog.mobby.android.speech

import com.github.ytlog.mobby.android.localization.AppStrings
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SherpaSpeechEngine(context: Context) : SpeechEngine {
    private val store = SpeechModelStore(File(context.filesDir, "speech"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<PrepareListener>()
    private val sessionLock = Any()
    private var session: ListenSession? = null

    override val ready: Boolean get() = SpeechRecognizerHolder.recognizer != null

    override fun prepare(onProgress: (Long, Long) -> Unit, onLoading: () -> Unit, onReady: () -> Unit, onError: (String) -> Unit) {
        val listener = PrepareListener(onProgress, onLoading, onReady, onError)
        listeners += listener
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SpeechRecognizerHolder.load(store,
                        onProgress = { read, total ->
                            val snapshot = listeners.toList()
                            main.post { snapshot.forEach { it.onProgress(read, total) } }
                        },
                        onLoading = {
                            val snapshot = listeners.toList()
                            main.post { snapshot.forEach { it.onLoading() } }
                        })
                }
                if (listener in listeners) listener.onReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (listener in listeners) listener.onError(speechFailure(error))
            } finally { listeners.remove(listener) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun listen(onLevel: (Float) -> Unit, onPartial: (String) -> Unit, onSegment: (String) -> Unit,
        onFinal: (String) -> Unit, onError: (String) -> Unit) {
        stop(false)
        val recognizer = SpeechRecognizerHolder.recognizer ?: run { onError(AppStrings.speechModelIsNotReady); return }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) { onError(AppStrings.cannotUseTheMicrophoneRetryLater); return }
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minimum.coerceAtLeast(3200))
        } catch (_: SecurityException) { onError(AppStrings.microphonePermissionNotGrantedTypeInsteadOrGrantPermission); return }
        catch (_: IllegalArgumentException) { onError(AppStrings.cannotUseTheMicrophoneRetryLater); return }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); onError(AppStrings.cannotUseTheMicrophoneRetryLater); return
        }
        val running = AtomicBoolean(true)
        val deliver = AtomicBoolean(true)
        val thread = Thread({
            val stream = recognizer.createStream()
            val buffer = ByteArray(minimum.coerceAtLeast(3200))
            val committed = mutableListOf<String>()
            var partial = ""
            var failure: String? = null
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    failure = AppStrings.cannotUseTheMicrophoneRetryLater
                } else {
                    while (running.get()) {
                        val count = record.read(buffer, 0, buffer.size)
                        if (count < 0 || !running.get()) break
                        val samples = FloatArray(count / 2) { i ->
                            val low = buffer[2 * i].toInt() and 0xff
                            val high = buffer[2 * i + 1].toInt()
                            ((high shl 8 or low).toShort().toFloat() / 32768f)
                        }
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                        partial = normalizeTranscript(recognizer.getResult(stream).text)
                        val display = (committed + partial.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" ")
                        main.post { onLevel(pcmLevel(buffer, count)); onPartial(display) }
                        if (recognizer.isEndpoint(stream)) {
                            if (partial.isNotBlank()) {
                                committed += partial
                                val segment = partial
                                main.post { onSegment(segment) }
                            }
                            recognizer.reset(stream)
                            partial = ""
                        }
                    }
                    if (deliver.get()) {
                        stream.inputFinished()
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                        partial = normalizeTranscript(recognizer.getResult(stream).text)
                    }
                }
            } catch (_: Exception) { failure = AppStrings.cannotUseTheMicrophoneRetryLater }
            finally {
                runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
                record.release()
                stream.release()
            }
            when {
                failure != null && deliver.get() -> main.post { onError(failure!!) }
                deliver.get() -> {
                    val text = (committed + partial.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" ")
                    main.post { onFinal(text) }
                }
            }
        }, "mobby-speech")
        thread.isDaemon = true
        synchronized(sessionLock) { session = ListenSession(running, deliver, record) }
        thread.start()
    }

    override fun stop(deliver: Boolean) {
        val current = synchronized(sessionLock) { session?.also { session = null } } ?: return
        current.deliver.set(deliver)
        current.running.set(false)
        runCatching { current.record.stop() }
    }

    override fun close() { listeners.clear(); stop(false) }

    private class PrepareListener(val onProgress: (Long, Long) -> Unit, val onLoading: () -> Unit,
        val onReady: () -> Unit, val onError: (String) -> Unit)
    private class ListenSession(val running: AtomicBoolean, val deliver: AtomicBoolean, val record: AudioRecord)
}

private const val SAMPLE_RATE = 16_000

private object SpeechRecognizerHolder {
    private val mutex = Mutex()
    @Volatile var recognizer: OnlineRecognizer? = null

    suspend fun load(store: SpeechModelStore, onProgress: (Long, Long) -> Unit, onLoading: () -> Unit) {
        store.removeLegacyCache()
        if (recognizer != null) return
        mutex.withLock {
            if (recognizer != null) return@withLock
            if (!store.ready()) store.ensure(onProgress)
            onLoading()
            val dir = store.modelDirectory()
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                        joiner = File(dir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 1.2f, 0f),
                    rule2 = EndpointRule(true, 0.7f, 0f),
                    rule3 = EndpointRule(false, 0f, 15f),
                ),
            )
            recognizer = OnlineRecognizer(config = config)
        }
    }
}

private fun speechFailure(error: Throwable): String = when ((error as? SpeechModelException)?.kind) {
    SpeechModelException.Kind.NETWORK -> AppStrings.speechModelDownloadFailedCheckNetworkAndRetry
    SpeechModelException.Kind.CHECKSUM -> AppStrings.speechModelVerificationFailedPleaseRetry
    SpeechModelException.Kind.INCOMPLETE, null -> AppStrings.cannotLoadSpeechModelPleaseRetry
}
