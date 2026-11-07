package com.github.ytlog.mobby.android.conversation.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap

/** Linux-style aliases for text shown by the app. Runtime paths remain the source of truth. */
internal class RuntimePathDisplay(packageName: String, dataDir: String) {
    private val aliases = mapOf(
        "/files/libtermux/usr/tmp" to "/tmp",
        "/files/libtermux/home" to "~",
        "/files/libtermux/usr" to "/usr",
        "/files" to "/var/lib/mobby",
        "/cache" to "/var/cache/mobby",
        "" to "/var/lib/mobby",
    )
    private val path = Regex(
        "(?<![A-Za-z0-9_./~%+@-])(?:file:(?://)?)?" +
            "(?:${Regex.escape(dataDir)}|/data/(?:data|user(?:_de)?/\\d+)/${Regex.escape(packageName)})" +
            "(${aliases.keys.joinToString("|") { Regex.escape(it) }})" +
            """(?=$|[\s/;:,'"?!#=<>()\[\]{}|&`，。；：！？（）【】“”‘’]|\.(?=$|\s))""",
    )

    fun format(text: String): String = if ('/' !in text) text else path.replace(text) {
        aliases.getValue(it.groupValues[1])
    }
}

private val runtimePathDisplays = ConcurrentHashMap<Pair<String, String>, RuntimePathDisplay>()

internal fun runtimePaths(context: Context): RuntimePathDisplay {
    val key = context.packageName to context.applicationInfo.dataDir
    return runtimePathDisplays.getOrPut(key) { RuntimePathDisplay(key.first, key.second) }
}

@Composable internal fun rememberRuntimePaths(): RuntimePathDisplay {
    val context = LocalContext.current.applicationContext
    return remember(context) { runtimePaths(context) }
}

@Composable internal fun displayRuntimePaths(text: String): String {
    val paths = rememberRuntimePaths()
    return remember(paths, text) { paths.format(text) }
}
