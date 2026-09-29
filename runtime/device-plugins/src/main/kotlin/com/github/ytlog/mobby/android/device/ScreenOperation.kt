package com.github.ytlog.mobby.android.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** Installed by the app's overlay owner. Both acquisition and release run on main. */
object ScreenOperation {
    var hideOverlay: () -> AutoCloseable = { AutoCloseable {} }

    internal fun <T> run(checkActive: () -> Unit, action: suspend () -> T): T = runBlocking {
        withTimeout(8_000) {
            withContext(Dispatchers.Main.immediate) {
                coroutineContext.ensureActive()
                checkActive()
                hideOverlay().use {
                    coroutineContext.ensureActive()
                    checkActive()
                    action()
                }
            }
        }
    }
}
