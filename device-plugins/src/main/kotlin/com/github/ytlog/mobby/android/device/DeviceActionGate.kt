package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Shared by transport admission and queued screen actions; independent of UI and Agent type. */
internal class DeviceActionGate(private val isCancelled: () -> Boolean = { false }) {
    private val closed = AtomicBoolean(false)
    fun close() { closed.set(true) }
    fun checkActive() {
        if (closed.get() || isCancelled()) throw CancellationException(AppStrings.stepCancelled)
    }
}
