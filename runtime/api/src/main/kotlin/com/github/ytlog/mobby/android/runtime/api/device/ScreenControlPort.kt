package com.github.ytlog.mobby.android.runtime.api.device

import kotlinx.coroutines.flow.StateFlow

enum class ScreenMode {
    CURRENT, ADB, ROOT;
    companion object {
        fun restore(value: String?): ScreenMode = if (value == null) CURRENT else valueOf(value)
    }
}

data class ScreenWorkflowPause(val completed: Int, val total: Int, val nextAction: String)

data class ScreenControlState(val mode: ScreenMode = ScreenMode.CURRENT, val connected: Boolean = false,
    val active: Boolean = false, val displayId: Int? = null, val jpeg: ByteArray? = null,
    val packageName: String = "", val error: String? = null, val adbPaired: Boolean = false, val adbPairing: Boolean = false,
    /** Live successful pairing sequence; never inferred from a previously saved identity or persisted. */
    val adbPairingSuccess: Long = 0, val modeConfigured: Boolean = false,
    val workflowPause: ScreenWorkflowPause? = null)

/** UI contract. Privilege acquisition and Android display APIs belong to the host implementation. */
interface ScreenControlPort {
    val state: StateFlow<ScreenControlState>
    suspend fun select(mode: ScreenMode)
    suspend fun connect()
    suspend fun disconnect()
    suspend fun setAdbPort(port: String)
    fun startAdbPairing()
    fun cancelAdbPairing()
    suspend fun refreshPreview()
}
