package com.github.ytlog.mobby.android.speech

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

interface SpeechEngine {
    /** True after the model has been loaded in this process. Does not download or load native code by itself. */
    val ready: Boolean

    fun prepare(
        onProgress: (read: Long, total: Long) -> Unit,
        onLoading: () -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    )

    fun listen(
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    )

    fun stop(deliver: Boolean)
    fun close()
}

object SpeechEngines {
    private val preloadStarted = AtomicBoolean(false)

    /** Creates the engine. Native code and the model stay unloaded until [SpeechEngine.prepare] or [SpeechEngine.listen]. */
    fun create(context: Context): SpeechEngine = SherpaSpeechEngine(context.applicationContext)

    /** Starts model preparation in the background. A later capture joins the same process-wide load. */
    fun preload(context: Context) {
        if (!preloadStarted.compareAndSet(false, true)) return
        create(context).prepare(
            onProgress = { _, _ -> },
            onLoading = {},
            onReady = {},
            onError = {},
        )
    }
}
