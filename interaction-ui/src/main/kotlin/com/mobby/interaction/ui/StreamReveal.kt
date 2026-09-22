package com.mobby.interaction.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext

internal const val STREAM_PARSE_INTERVAL_MS = 1_000L

internal data class StreamPresentation(val markdown: String, val tail: String, val cursor: Boolean)

internal class StreamEngine(
    private val parseIntervalMs: Long = STREAM_PARSE_INTERVAL_MS,
) {
    private var parsed = ""
    private var lastParseAt = Long.MIN_VALUE / 4

    fun finish(source: String): StreamPresentation {
        parsed = source
        lastParseAt = Long.MIN_VALUE / 4
        return StreamPresentation(source, "", false)
    }

    /** Text that has already arrived stays visible. Markdown refreshes on an interval so the layout does not reflow on every token. */
    fun frame(source: String, nowMs: Long, reducedMotion: Boolean): StreamPresentation {
        val due = parsed.isEmpty() || reducedMotion || !source.startsWith(parsed) || nowMs - lastParseAt >= parseIntervalMs
        if (due) {
            parsed = source
            lastParseAt = nowMs
        }
        val markdown = if (source.startsWith(parsed)) parsed else ""
        val tail = if (markdown.isEmpty()) source else source.removePrefix(markdown)
        return StreamPresentation(markdown, tail, true)
    }
}

@Composable internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable internal fun rememberStreamPresentation(source: String, streaming: Boolean): StreamPresentation {
    val reduced = rememberReducedMotion()
    val engine = remember { StreamEngine() }
    if (!streaming) return engine.finish(source)
    var view by remember { mutableStateOf(engine.frame(source, SystemClock.uptimeMillis(), reduced)) }
    val latest = rememberUpdatedState(source)
    val motion = rememberUpdatedState(reduced)
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { time ->
                view = engine.frame(latest.value, time / 1_000_000L, motion.value)
            }
        }
    }
    return view
}
