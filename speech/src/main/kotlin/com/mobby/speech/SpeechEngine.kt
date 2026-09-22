package com.mobby.speech

import android.content.Context

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
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    )

    fun stop(deliver: Boolean)
    fun close()
}

object SpeechEngines {
    /** Creates the engine. Native code and the model stay unloaded until [SpeechEngine.prepare] or [SpeechEngine.listen]. */
    fun create(context: Context): SpeechEngine = VoskSpeechEngine(context.applicationContext)
}
