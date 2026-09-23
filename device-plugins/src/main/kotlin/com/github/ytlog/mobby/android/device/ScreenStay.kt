package com.github.ytlog.mobby.android.device

internal interface DisplayHold {
    fun hold()
    fun release()
}

/** Keeps one platform hold while at least one screen run is open. */
internal class ScreenStay(private val display: DisplayHold) {
    private var depth = 0
    private var generation = 0
    private var platform = false

    fun acquire(): AutoCloseable {
        val ticket = synchronized(this) {
            depth += 1
            if (depth != 1) -1 else generation
        }
        if (ticket >= 0) {
            try {
                display.hold()
            } catch (error: Throwable) {
                synchronized(this) { if (generation == ticket) depth -= 1 }
                throw error
            }
            val stale = synchronized(this) {
                if (generation != ticket || depth == 0) true else {
                    platform = true
                    false
                }
            }
            if (stale) display.release()
        }
        return AutoCloseable { release() }
    }

    fun drop() {
        val stop = synchronized(this) {
            generation += 1
            depth = 0
            val stop = platform
            platform = false
            stop
        }
        if (stop) display.release()
    }

    private fun release() {
        val stop = synchronized(this) {
            if (depth == 0) return
            depth -= 1
            val stop = depth == 0 && platform
            if (stop) platform = false
            stop
        }
        if (stop) display.release()
    }
}
