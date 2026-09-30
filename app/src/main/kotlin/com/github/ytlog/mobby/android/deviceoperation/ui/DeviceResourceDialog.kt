package com.github.ytlog.mobby.android.deviceoperation.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.deviceoperation.ui.DeviceLabels
import com.github.ytlog.mobby.android.conversation.ui.raisedColor
import java.io.File

class DevicePreview(val name: String, val mediaType: String, val bytes: ByteArray)

/** Input has already passed the host resource store's ownership and integrity checks. */
@Composable fun DeviceResourceDialog(ref: String, load: suspend () -> DevicePreview?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var loading by remember(ref) { mutableStateOf(true) }
    var error by remember(ref) { mutableStateOf(false) }
    val preview by produceState<DevicePreview?>(null, ref) { try { value = load() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { value = null } finally { loading = false } }
    val bitmap = remember(preview) { preview?.takeIf { it.mediaType.startsWith("image/") }?.bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    DisposableEffect(ref) { onDispose { player?.release(); audioFile?.delete() } }
    AlertDialog(onDismissRequest = onDismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp),
        title = { Text(preview?.name ?: DeviceLabels.text("附件", "Attachment")) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                loading -> CircularProgressIndicator()
                preview == null -> Text(DeviceLabels.text("附件已过期或无法读取", "Attachment expired or unavailable"))
                bitmap != null -> Image(bitmap.asImageBitmap(), preview!!.name, Modifier.fillMaxWidth())
                preview!!.mediaType.startsWith("text/") -> Text(preview!!.bytes.toString(Charsets.UTF_8))
                preview!!.mediaType.startsWith("audio/") -> {
                    Text(DeviceLabels.text("已确认的录音素材", "Confirmed audio capture"))
                    TextButton(onClick = {
                        try {
                            player?.release()
                            audioFile?.delete()
                            val file = File.createTempFile("device-audio-", ".m4a", context.cacheDir).also { it.writeBytes(preview!!.bytes) }
                            audioFile = file
                            player = MediaPlayer().apply { setDataSource(file.absolutePath); prepare(); start() }
                        } catch (_: Exception) { error = true }
                    }) { Text(DeviceLabels.text("播放", "Play")) }
                    TextButton(onClick = { player?.pause() }) { Text(DeviceLabels.text("暂停播放", "Pause playback")) }
                }
                else -> Text(DeviceLabels.text("已保存文件 · ${preview!!.bytes.size} 字节", "Saved file · ${preview!!.bytes.size} bytes"))
            }
            if (error) Text(DeviceLabels.text("无法播放录音", "Could not play audio"))
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(DeviceLabels.text("关闭", "Close")) } })
}
