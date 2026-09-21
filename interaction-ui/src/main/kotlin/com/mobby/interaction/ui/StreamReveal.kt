package com.mobby.interaction.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import kotlin.math.max

internal const val STREAM_PARSE_INTERVAL_MS = 1_000L

internal data class StreamPresentation(val markdown: String, val tail: String, val cursor: Boolean)

internal class StreamEngine(
    private val parseIntervalMs: Long = STREAM_PARSE_INTERVAL_MS,
    private val charsPerSecond: Int = 72,
) {
    private var committed = ""
    private var parsed = ""
    private var shown = 0
    private var lastCommitAt = Long.MIN_VALUE / 4

    fun finish(source: String): StreamPresentation {
        committed = source
        parsed = source
        shown = source.length
        lastCommitAt = Long.MIN_VALUE / 4
        return StreamPresentation(source, "", false)
    }

    fun frame(source: String, nowMs: Long, reducedMotion: Boolean, dtMs: Long): StreamPresentation {
        val replacement = committed.isNotEmpty() && !source.startsWith(committed) && !committed.startsWith(source)
        if (committed.isEmpty() || replacement || nowMs - lastCommitAt >= parseIntervalMs) {
            committed = source
            lastCommitAt = nowMs
            if (replacement) {
                parsed = ""
                shown = 0
            }
        }
        shown = if (reducedMotion) committed.length
            else (shown + step(committed.length - shown, dtMs)).coerceIn(0, committed.length)
        val visible = committed.take(shown)
        if (shown >= committed.length) parsed = committed
        val markdown = if (visible.startsWith(parsed)) parsed else ""
        val tail = if (markdown.isEmpty()) visible else visible.removePrefix(markdown)
        return StreamPresentation(markdown, tail, true)
    }

    private fun step(remaining: Int, dtMs: Long): Int {
        if (remaining <= 0) return 0
        val planned = max(1, charsPerSecond * dtMs.toInt() / 1000)
        return if (remaining > charsPerSecond) remaining - charsPerSecond + planned else planned
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
    var view by remember { mutableStateOf(engine.frame(source, SystemClock.uptimeMillis(), reduced, 16)) }
    val latest = rememberUpdatedState(source)
    val motion = rememberUpdatedState(reduced)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { time ->
                val dt = if (last == 0L) 16L else ((time - last) / 1_000_000L).coerceIn(8L, 50L)
                last = time
                view = engine.frame(latest.value, time / 1_000_000L, motion.value, dt)
            }
        }
    }
    return view
}
