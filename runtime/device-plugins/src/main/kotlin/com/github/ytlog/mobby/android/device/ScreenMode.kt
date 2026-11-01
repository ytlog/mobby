package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode

internal fun <T> selectScreenBackend(mode: ScreenMode, current: () -> T, virtual: () -> T): T =
    when (mode) {
        ScreenMode.CURRENT -> current()
        ScreenMode.SHIZUKU, ScreenMode.ROOT -> virtual()
    }

internal interface ScreenSession : AutoCloseable {
    fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit): ScreenAccessService.Observation
    fun inspect(query: String?, expectedInput: String?): ScreenEvidence
}
