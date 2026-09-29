package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun EventHistoryPage(load: suspend () -> DataResult<EventHistoryLimits>, save: suspend (EventHistoryLimits) -> OperationResult, back: () -> Unit) {
    var days by rememberSaveable { mutableStateOf("") }
    var mib by rememberSaveable { mutableStateOf("") }
    var outputDays by rememberSaveable { mutableStateOf("") }
    var outputMiB by rememberSaveable { mutableStateOf("") }
    var attachmentMiB by rememberSaveable { mutableStateOf("") }
    var resourceCacheMiB by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(true) }
    var retry by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(retry) {
        loaded = false; message = null; messageError = true
        try {
            when (val result = load()) {
                is DataResult.Loaded -> { if (!initialized) { days = result.value.days.toString(); mib = result.value.mib.toString(); outputDays = result.value.outputDays.toString(); outputMiB = result.value.outputMiB.toString(); attachmentMiB = result.value.attachmentMiB.toString(); resourceCacheMiB = result.value.resourceCacheMiB.toString(); initialized = true }; loaded = true }
                is DataResult.Failed -> message = result.message
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = AppStrings.cannotReadStorageSettingsPleaseRetry }
    }
    val valid = days.toIntOrNull()?.let { it in 1..3650 } == true && mib.toIntOrNull()?.let { it in 1..1024 } == true &&
        outputDays.toIntOrNull()?.let { it in 1..3650 } == true && outputMiB.toIntOrNull()?.let { it in 1..4096 } == true && attachmentMiB.toIntOrNull()?.let { it in 1..8192 } == true && resourceCacheMiB.toIntOrNull()?.let { it in 1..8192 } == true
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.storageRetention, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption(AppStrings.cleanUpOldLogsAndRawOutputOfFinished)
            SettingsCaption(AppStrings.conversationOutputCacheHasASeparateMibLimitOld)
            SettingsGroup {
                SettingsField(days, { days = it; message = null }, AppStrings.retentionDays, enabled = loaded && !busy)
                GroupDivider()
                SettingsField(mib, { mib = it; message = null }, AppStrings.eventContentLimitMib, enabled = loaded && !busy)
                GroupDivider()
                SettingsField(outputDays, { outputDays = it; message = null }, AppStrings.outputRetentionDays, enabled = loaded && !busy)
                GroupDivider()
                SettingsField(outputMiB, { outputMiB = it; message = null }, AppStrings.rawOutputLimitMib, enabled = loaded && !busy)
                GroupDivider()
                SettingsField(attachmentMiB, { attachmentMiB = it; message = null }, AppStrings.attachmentStorageLimitMib, enabled = loaded && !busy)
                GroupDivider()
                SettingsField(resourceCacheMiB, { resourceCacheMiB = it; message = null }, AppStrings.resourceCacheLimitMib, enabled = loaded && !busy)
            }
            SettingsCaption(AppStrings.attachmentCacheEvictsLeastRecentlyUsed)
            if (!loaded && message == null) SettingsCaption(AppStrings.readingSettings)
            message?.let { SettingsCaption(it, error = messageError) }
            if (!loaded && message != null) SettingsGroup { SettingsAction(AppStrings.retryReading) { retry++ } }
            SettingsGroup {
                SettingsAction(if (busy) AppStrings.saving else AppStrings.saveStorageSettings, enabled = loaded && valid && !busy) {
                    val value = EventHistoryLimits(days.toInt(), mib.toInt(), outputDays.toInt(), outputMiB.toInt(), attachmentMiB.toInt(), resourceCacheMiB.toInt())
                    busy = true; message = null; messageError = true
                    scope.launch {
                        try {
                            message = when (val result = save(value)) {
                                OperationResult.Done -> { messageError = false; AppStrings.savedLogsAndOutputWillBeCleanedUpDuring }
                                is OperationResult.Failed -> result.message
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = AppStrings.saveUnconfirmedPleaseRetry }
                        finally { busy = false }
                    }
                }
            }
        }
    }
}
