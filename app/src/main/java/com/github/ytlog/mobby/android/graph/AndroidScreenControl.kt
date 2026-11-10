package com.github.ytlog.mobby.android.graph

import android.content.Context
import com.github.ytlog.mobby.android.device.ScreenControl
import com.github.ytlog.mobby.android.runtime.api.device.ManualScreenInput
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlPort
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidScreenControl(context: Context) : ScreenControlPort {
    private val context = context.applicationContext
    override val state = ScreenControl.state
    override suspend fun hasRootCommand() = ScreenControl.hasRootCommand()
    override suspend fun checkRootPermission() = ScreenControl.checkRootPermission()
    override suspend fun select(mode: ScreenMode) = withContext(Dispatchers.IO) { ScreenControl.select(context, mode) }
    override suspend fun connect() = ScreenControl.connect(context)
    override suspend fun disconnect() = withContext(Dispatchers.IO) { ScreenControl.disconnect() }
    override suspend fun setAdbPort(port: String) = ScreenControl.setAdbPort(context, port)
    override fun startAdbPairing(returnToActivity: String?) = ScreenControl.startAdbPairing(context, returnToActivity)
    override fun cancelAdbPairing() = ScreenControl.cancelAdbPairing(context)
    override suspend fun refreshPreview() = ScreenControl.refreshPreview()
    override suspend fun takeOver() = withContext(Dispatchers.IO) { ScreenControl.takeOver(context) }
    override suspend fun showTakeoverKeyboard(id: String, visible: Boolean) =
        withContext(Dispatchers.IO) { ScreenControl.showTakeoverKeyboard(context, id, visible) }
    override suspend fun prepareManualText(id: String) = withContext(Dispatchers.IO) { ScreenControl.prepareManualText(context, id) }
    override suspend fun manualInput(id: String, input: ManualScreenInput) =
        withContext(Dispatchers.IO) { ScreenControl.manualInput(context, id, input) }
    override suspend fun handBack(id: String) = withContext(Dispatchers.IO) { ScreenControl.handBack(context, id) }
}
