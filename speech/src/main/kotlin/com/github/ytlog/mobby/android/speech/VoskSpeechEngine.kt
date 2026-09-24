package com.github.ytlog.mobby.android.speech

import com.github.ytlog.mobby.android.localization.AppStrings

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

internal class VoskSpeechEngine(context: Context) : SpeechEngine {
    private val store = SpeechModelStore(File(context.filesDir, "speech"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<PrepareListener>()
    private val sessionLock = Any()
    private var session: ListenSession? = null

    override val ready: Boolean get() = SpeechModels.loaded

    override fun prepare(
        onProgress: (read: Long, total: Long) -> Unit,
        onLoading: () -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val listener = PrepareListener(onProgress, onLoading, onReady, onError)
        listeners += listener
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SpeechModels.load(
                        store,
                        onProgress = { read, total ->
                            val snapshot = listeners.toList()
                            main.post { snapshot.forEach { it.onProgress(read, total) } }
                        },
                        onLoading = {
                            val snapshot = listeners.toList()
                            main.post { snapshot.forEach { it.onLoading() } }
                        },
                    )
                }
                if (listener in listeners) listener.onReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (listener in listeners) listener.onError(speechFailure(error))
            } finally {
                listeners.remove(listener)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun listen(onLevel: (Float) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        stop(deliver = false)
        val loaded = SpeechModels.model ?: run {
            onError(AppStrings.speechModelIsNotReady)
            return
        }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            onError(AppStrings.cannotUseTheMicrophoneRetryLater)
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimum.coerceAtLeast(3200),
            )
        } catch (_: SecurityException) {
            onError(AppStrings.microphonePermissionNotGrantedTypeInsteadOrGrantPermission)
            return
        } catch (_: IllegalArgumentException) {
            onError(AppStrings.cannotUseTheMicrophoneRetryLater)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError(AppStrings.cannotUseTheMicrophoneRetryLater)
            return
        }
        val recognizer = try {
            Recognizer(loaded, SAMPLE_RATE.toFloat())
        } catch (_: Exception) {
            record.release()
            onError(AppStrings.cannotLoadSpeechModelPleaseRetry)
            return
        }
        val running = AtomicBoolean(true)
        val deliver = AtomicBoolean(true)
        val thread = Thread({
            val buffer = ByteArray(minimum.coerceAtLeast(3200))
            var failure: String? = null
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    failure = AppStrings.cannotUseTheMicrophoneRetryLater
                } else {
                    while (running.get()) {
                        val count = record.read(buffer, 0, buffer.size)
                        if (count < 0 || !running.get()) break
                        val level = pcmLevel(buffer, count)
                        main.post { onLevel(level) }
                        recognizer.acceptWaveForm(buffer, count)
                    }
                }
            } catch (_: Exception) {
                failure = AppStrings.cannotUseTheMicrophoneRetryLater
            } finally {
                runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                }
                record.release()
            }
            val problem = failure
            val text = if (problem == null && deliver.get()) {
                runCatching { transcriptOf(recognizer.finalResult) }.getOrDefault("")
            } else {
                null
            }
            runCatching { recognizer.close() }
            when {
                problem != null && deliver.get() -> main.post { onError(problem) }
                text != null -> main.post { onFinal(text) }
            }
        }, "mobby-speech")
        thread.isDaemon = true
        synchronized(sessionLock) { session = ListenSession(running, deliver, record, thread) }
        thread.start()
    }

    override fun stop(deliver: Boolean) {
        val current = synchronized(sessionLock) { session?.also { session = null } } ?: return
        current.deliver.set(deliver)
        current.running.set(false)
        runCatching { current.record.stop() }
    }

    override fun close() {
        listeners.clear()
        stop(deliver = false)
    }

    private class PrepareListener(
        val onProgress: (Long, Long) -> Unit,
        val onLoading: () -> Unit,
        val onReady: () -> Unit,
        val onError: (String) -> Unit,
    )

    private class ListenSession(
        val running: AtomicBoolean,
        val deliver: AtomicBoolean,
        val record: AudioRecord,
        val thread: Thread,
    )
}

private const val SAMPLE_RATE = 16_000

/** Process-wide model. Loading happens inside [load], never from a class initializer. */
private object SpeechModels {
    private val mutex = Mutex()

    @Volatile var model: Model? = null
    val loaded: Boolean get() = model != null

    suspend fun load(store: SpeechModelStore, onProgress: (Long, Long) -> Unit, onLoading: () -> Unit) {
        if (model != null) return
        mutex.withLock {
            if (model != null) return@withLock
            if (!store.ready()) store.ensure(onProgress)
            onLoading()
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(store.modelDirectory().absolutePath)
        }
    }
}

private fun speechFailure(error: Throwable): String = when ((error as? SpeechModelException)?.kind) {
    SpeechModelException.Kind.NETWORK -> AppStrings.speechModelDownloadFailedCheckNetworkAndRetry
    SpeechModelException.Kind.CHECKSUM -> AppStrings.speechModelVerificationFailedPleaseRetry
    SpeechModelException.Kind.INCOMPLETE, null -> AppStrings.cannotLoadSpeechModelPleaseRetry
}
