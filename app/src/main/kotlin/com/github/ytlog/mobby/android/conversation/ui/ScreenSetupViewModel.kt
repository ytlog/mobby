package com.github.ytlog.mobby.android.conversation.ui

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode

internal class ScreenSetupViewModel(savedState: SavedStateHandle) : ViewModel() {
    private var restored = savedState.get<Bundle>(PROGRESS)
    private var current: ScreenSetupDraft? = null

    init {
        savedState.setSavedStateProvider(PROGRESS) { current?.saveProgress() ?: restored ?: Bundle() }
    }

    fun draft(selectedMode: ScreenMode, pairing: Boolean): ScreenSetupDraft =
        current ?: ScreenSetupDraft(selectedMode, pairing, restored).also {
            current = it
            restored = null
        }

    fun clear() {
        current = null
        restored = null
    }

    private companion object {
        const val PROGRESS = "screen-setup-progress"
    }
}

internal class ScreenSetupDraft(initialMode: ScreenMode, pairing: Boolean, restored: Bundle?) {
    var history by mutableStateOf(restored?.getStringArrayList("history")?.takeIf { it.isNotEmpty() }
        ?: arrayListOf(ScreenSetupStep.MODE.name).apply { if (pairing) add(ScreenSetupStep.PAIR.name) })
    var selectedMode by mutableStateOf(restored?.getString("mode")?.let(ScreenMode::valueOf) ?: initialMode)
    var family by mutableStateOf(restored?.getString("family")?.let(DeviceSettingsFamily::valueOf) ?: DeviceSettingsFamily.detect())
    var pendingCheck by mutableStateOf(restored?.getString("pendingCheck")?.let(ScreenSetupStep::valueOf))
    var manualAdbSetup by mutableStateOf(restored?.getBoolean("manualAdbSetup") == true)
    private var progressMode by mutableStateOf(restored?.getString("progressMode")?.let(ScreenMode::valueOf))
    private var furthestStep by mutableIntStateOf(restored?.getInt("furthestStep") ?: 0)

    var connectionPort by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var developerConfirmed by mutableStateOf(false)
    var wirelessConfirmed by mutableStateOf(false)
    var openWirelessOnArrival by mutableStateOf(false)
    var forcePair by mutableStateOf(false)
    var connectAfterPairing by mutableStateOf(false)
    var pairingSuccessAtStart by mutableLongStateOf(0)

    fun furthestStep(mode: ScreenMode): Int = if (progressMode == mode) furthestStep else 0

    fun recordStep(mode: ScreenMode, index: Int) {
        furthestStep = maxOf(furthestStep(mode), index)
        progressMode = mode
    }

    fun saveProgress() = Bundle().apply {
        putStringArrayList("history", history)
        putString("mode", selectedMode.name)
        putString("family", family.name)
        putString("pendingCheck", pendingCheck?.name)
        putBoolean("manualAdbSetup", manualAdbSetup)
        putString("progressMode", progressMode?.name)
        putInt("furthestStep", furthestStep)
    }
}
