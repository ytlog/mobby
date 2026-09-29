package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

internal class CameraLaunch(val busy: Boolean, val start: (Conversation) -> Unit)
internal class CapturePictureContract : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        clipData = ClipData.newRawUri("camera output", input)
    }
}

/** Kept composed at screen root, so leaving the add sheet cannot unregister the result callback. */
@Composable internal fun rememberCameraCapture(actions: ConversationUseCases, onImport: suspend (CameraCapture) -> DataResult<Attachment>, report: (String) -> Unit): CameraLaunch {
    val scope = rememberCoroutineScope()
    var capture by remember { mutableStateOf<CameraCapture?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun fail(message: String) { problem = message; if (capture == null) report(message) }
    var busy by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    var launchId by rememberSaveable { mutableStateOf<String?>(null) }
    fun update(result: DataResult<CameraCapture?>) {
        when (result) {
            is DataResult.Loaded -> capture = result.value?.takeUnless { it.phase in setOf(CapturePhase.IMPORTING, CapturePhase.DISCARDING) }
            is DataResult.Failed -> fail(result.message)
        }
    }
    suspend fun restore() {
        try { update(actions.capture()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail(AppStrings.photoCaptureRecoveryIsIncompleteRetryLater) }
    }
    val launcher = rememberLauncherForActivityResult(CapturePictureContract()) { success ->
        val expected = launchId; launchId = null; revision++
        scope.launch {
            busy = true
            try {
                val snapshot = actions.capture()
                val current = (snapshot as? DataResult.Loaded)?.value
                if (expected != null && current != null && current.id == expected && current.phase == CapturePhase.CAPTURING)
                    update(actions.finishCapture(current.id, success))
                else update(snapshot)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail(AppStrings.couldNotRecoverThePhotoReopenTheAppTo) }
            finally { busy = false }
        }
    }
    LaunchedEffect(actions) {
        val initial = revision
        try {
            val restored = actions.capture()
            if (revision == initial) update(restored)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail(AppStrings.photoCaptureRecoveryIsIncompleteRetryLater) }
        finally { if (revision == initial) busy = false }
    }
    val current = capture
    if (current != null) {
        fun discard() {
            if (busy) return
            revision++; problem = null; busy = true
            scope.launch {
                try {
                    when (val result = actions.discardCapture(current.id)) {
                        OperationResult.Done -> capture = null
                        is OperationResult.Failed -> fail(result.message)
                    }
                } finally { busy = false }
            }
        }
        CameraReviewDialog(current, busy, { actions.previewCapture(current.id) }, onDiscard = { discard() }, onConfirm = {
            revision++; problem = null; busy = true
            scope.launch {
                try {
                    val result = onImport(current)
                    if (result is DataResult.Failed) fail(result.message)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { fail(AppStrings.photoHasNotBeenAddedToTheDraftCheck) }
                finally { busy = false }
                restore()
            }
        }, onCheck = {
            revision++; problem = null; busy = true
            scope.launch { try { update(actions.finishCapture(current.id, true)) } finally { busy = false } }
        }, problem = problem)
    }
    return CameraLaunch(busy || capture != null || launchId != null) { conversation ->
        if (!busy && capture == null && launchId == null) {
            revision++; problem = null; busy = true
            scope.launch {
                try {
                    when (val result = actions.beginCapture(conversation.id, conversation.config.workspace)) {
                        is DataResult.Failed -> fail(result.message)
                        is DataResult.Loaded -> {
                            capture = result.value; launchId = result.value.id
                            try { launcher.launch(Uri.parse(result.value.captureUri)) }
                            catch (_: Exception) {
                                launchId = null; update(actions.finishCapture(result.value.id, false))
                                fail(AppStrings.cannotOpenTheSystemCameraCheckTheCameraApp)
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { fail(AppStrings.cannotStartPhotoCaptureCheckTheCurrentConversationAnd) }
                finally { busy = false }
            }
        }
    }
}

@Composable internal fun CameraReviewDialog(capture: CameraCapture, busy: Boolean, preview: suspend (Boolean) -> DataResult<AttachmentPreview>, onDiscard: () -> Unit, onConfirm: () -> Unit, onCheck: () -> Unit, problem: String? = null) {
    var ready by remember(capture.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDiscard() }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(AppStrings.photoPreview) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (busy) Text(AppStrings.processingPhoto, style = MaterialTheme.typography.bodySmall)
            when (capture.phase) {
                CapturePhase.REVIEW -> {
                    AttachmentImage(capture.id, AppStrings.capturePhoto, true, preview, Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 240.dp), onReady = { ready = it })
                    Text(AppStrings.photoConvertedToJpegWithAMaximumEdgeOf, style = MaterialTheme.typography.bodySmall)
                }
                CapturePhase.CAPTURING -> Text(AppStrings.photoCaptureIsIncompleteIfYouReturnedFromThe)
                CapturePhase.ERROR -> Text(capture.error ?: AppStrings.photoProcessingFailedCancelAndRetake)
                CapturePhase.IMPORTING, CapturePhase.DISCARDING -> Unit
            }
        } },
        confirmButton = {
            if (capture.phase == CapturePhase.REVIEW) TextButton(enabled = !busy && ready, onClick = onConfirm) { Text(AppStrings.addToDraft) }
            else if (capture.phase == CapturePhase.CAPTURING) TextButton(enabled = !busy, onClick = onCheck) { Text(AppStrings.checkPhotoResult) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDiscard) { Text(AppStrings.cancel) } }
    )
}
