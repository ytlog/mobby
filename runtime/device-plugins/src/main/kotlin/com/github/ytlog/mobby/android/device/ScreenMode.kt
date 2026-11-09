package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.DeviceFailure
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import com.github.ytlog.mobby.android.runtime.api.device.deviceFailure

internal fun <T> selectScreenBackend(mode: ScreenMode, current: () -> T, virtual: () -> T): T =
    when (mode) {
        ScreenMode.CURRENT -> current()
        ScreenMode.ADB, ScreenMode.ROOT -> virtual()
    }

internal interface ScreenSession : AutoCloseable {
    fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit): ScreenAccessService.Observation
    fun inspect(query: String?, expectedInput: String?): ScreenEvidence
}

/** A lost ADB worker releases its display. Recovery must obtain a new display before resuming. */
internal class RecoveringAdbScreenSession(
    private var session: ScreenSession,
    private val displayAlive: () -> Boolean,
    private val connected: () -> Boolean,
    private val checkAuthorization: () -> Unit = {},
    private val reconnect: () -> Unit = {},
    private val reconnectTimeoutMillis: Long = 60_000,
    private val reopen: () -> ScreenSession,
) : ScreenSession {
    private var needsObservation = false

    override fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit): ScreenAccessService.Observation {
        checkActive()
        checkAuthorization()
        if (!displayAlive()) recover(checkActive)
        if (needsObservation && action !in setOf("apps", "snapshot", "launch"))
            deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.adbScreenReadAfterReconnect)
        val result = try {
            session.operate(action, args, checkActive)
        } catch (failure: Exception) {
            if (failure is java.util.concurrent.CancellationException ||
                failure is DeviceFailure && failure.error.code == DeviceErrorCode.ADB_AUTHORIZATION_REQUIRED || displayAlive()) throw failure
            // The prior action may already have reached Android. Wait for recovery, then report it.
            recover(checkActive)
            throw failure
        }
        if (action == "snapshot" || action == "launch") needsObservation = false
        return result
    }

    /**
     * A running task holds the screen, so nothing else reconnects for it. Recovery reconnects with the saved pairing
     * itself. A [DeviceFailure] from [reconnect] (wireless debugging off, pairing revoked) needs the user and ends
     * recovery at once; other failures (worker restarting, endpoint briefly missing) are retried until the deadline.
     */
    private fun recover(checkActive: () -> Unit) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(reconnectTimeoutMillis)
        var lastFailure: Exception? = null
        while (!connected()) {
            checkActive()
            checkAuthorization()
            try { reconnect() }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (failure: DeviceFailure) { throw failure }
            catch (failure: Exception) { lastFailure = failure }
            if (connected()) break
            if (System.nanoTime() >= deadline) deviceFailure(DeviceErrorCode.TIMEOUT,
                listOfNotNull(AppStrings.adbScreenReconnectTimedOut, lastFailure?.message?.takeIf { it.isNotBlank() }).joinToString(" "))
            Thread.sleep(200)
        }
        checkActive()
        session.close()
        session = reopen()
        needsObservation = true
    }

    override fun inspect(query: String?, expectedInput: String?): ScreenEvidence {
        checkAuthorization()
        if (needsObservation || !displayAlive())
            deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.adbScreenReadAfterReconnect)
        return session.inspect(query, expectedInput)
    }

    override fun close() = session.close()
}
