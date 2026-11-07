package com.github.ytlog.mobby.android.conversation.ui

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.github.ytlog.mobby.android.conversation.domain.Attachment
import com.github.ytlog.mobby.android.conversation.domain.DataResult
import com.github.ytlog.mobby.android.conversation.domain.ResultFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal class ResultFileAccess(
    val read: suspend (String) -> DataResult<ResultFile>,
    val edit: (suspend (String) -> DataResult<Attachment>)?,
)

internal val LocalResultFileAccess = staticCompositionLocalOf<ResultFileAccess?> { null }

@Composable internal fun TurnResultFiles(replies: List<String>, access: ResultFileAccess) {
    val files by produceState(emptyList<ReplyFileLink>(), replies) {
        value = withContext(replyParsingDispatcher) { ReplyMarkdown.resultFiles(replies) }
    }
    if (files.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    TextButton(onClick = { expanded = !expanded }) { Text(UiStrings.resultFiles(files.size)) }
    if (expanded) files.forEach { file ->
        TextButton(onClick = { selected = file.source }, modifier = Modifier.fillMaxWidth()) {
            Text(file.name, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    files.firstOrNull { it.source == selected }?.let { file ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                ResultFileButtons(file.source, access, image = file.image, allowOpen = true) { selected = null }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(UiStrings.close) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ResultFileButtons(
    source: String,
    access: ResultFileAccess,
    image: Boolean,
    allowOpen: Boolean = false,
    onEdited: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by rememberSaveable(source) { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = UiStrings.resultFileActionFailed }
            finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val staged = pending
        pending = null
        if (staged != null) run {
            val location = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
            withContext(Dispatchers.IO) { ResultFileDelivery.finishSave(context, staged, location) }
            if (location != null) status = UiStrings.resultFileSaved
        }
    }

    fun withFile(action: suspend (ResultFile) -> Unit) = run {
        when (val result = access.read(source)) {
            is DataResult.Loaded -> action(result.value)
            is DataResult.Failed -> status = result.message
        }
    }

    val enabled = !busy && pending == null
    Column(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            if (allowOpen) TextButton(enabled = enabled, onClick = {
                withFile { file ->
                    val uri = withContext(Dispatchers.IO) { ResultFileDelivery.contentUri(context, file) }
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mediaType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }) { Text(UiStrings.openResultFile) }
            TextButton(enabled = enabled, onClick = {
                withFile { file ->
                    if (image && file.mediaType.startsWith("image/") && Build.VERSION.SDK_INT >= 29) {
                        withContext(Dispatchers.IO) { ResultFileDelivery.savePhoto(context, file) }
                        status = UiStrings.resultFileSaved
                    } else {
                        val staged = withContext(Dispatchers.IO) { ResultFileDelivery.stage(context, file) }
                        pending = staged
                        try {
                            picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType(file.mediaType).putExtra(Intent.EXTRA_TITLE, staged.substringAfter('/')))
                        } catch (failure: Exception) {
                            pending = null
                            withContext(Dispatchers.IO) { ResultFileDelivery.finishSave(context, staged, null) }
                            throw failure
                        }
                    }
                }
            }) { Text(if (image && Build.VERSION.SDK_INT >= 29) UiStrings.saveResultImage else UiStrings.saveResultFile) }
            TextButton(enabled = enabled, onClick = {
                withFile { file ->
                    val uri = withContext(Dispatchers.IO) { ResultFileDelivery.contentUri(context, file) }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(file.mediaType)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .apply { clipData = ClipData.newRawUri(file.name, uri) }
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                }
            }) { Text(UiStrings.shareResultFile) }
            val edit = access.edit
            if (image && edit != null) TextButton(enabled = enabled, onClick = {
                run {
                    when (val result = edit(source)) {
                        is DataResult.Loaded -> onEdited()
                        is DataResult.Failed -> status = result.message
                    }
                }
            }) { Text(UiStrings.editResultImage) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

private object ResultFileDelivery {
    private const val MAX_STAGED_BYTES = 100L * 1024 * 1024
    private const val RETAIN_MILLIS = 24L * 60 * 60 * 1000
    private val token = Regex("[a-f0-9-]{36}")

    private fun root(context: Context) = File(context.cacheDir, "reply-results")

    private fun safeName(file: ResultFile): String {
        val name = file.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(180).trim()
        return name.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "result"
    }

    @Synchronized fun stage(context: Context, file: ResultFile): String {
        val root = root(context)
        val expired = System.currentTimeMillis() - RETAIN_MILLIS
        root.listFiles().orEmpty().filter { it.lastModified() < expired }.forEach { it.deleteRecursively() }
        check(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } + file.bytes.size <= MAX_STAGED_BYTES)
        val directory = File(root, UUID.randomUUID().toString())
        check(directory.mkdirs())
        val name = safeName(file)
        try { File(directory, name).writeBytes(file.bytes) }
        catch (e: Exception) { directory.deleteRecursively(); throw e }
        return "${directory.name}/$name"
    }

    fun contentUri(context: Context, file: ResultFile): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.results", File(root(context), stage(context, file)))

    fun finishSave(context: Context, staged: String, destination: Uri?) {
        val id = staged.substringBefore('/')
        val name = staged.substringAfter('/', "")
        require(token.matches(id) && name.isNotBlank() && name !in setOf(".", "..") && '/' !in name)
        val directory = File(root(context), id)
        val source = File(directory, name)
        try {
            if (destination != null) {
                source.inputStream().use { input ->
                    requireNotNull(context.contentResolver.openOutputStream(destination, "wt")).use { output -> input.copyTo(output) }
                }
            }
        } finally { source.delete(); directory.delete() }
    }

    @androidx.annotation.RequiresApi(29)
    fun savePhoto(context: Context, file: ResultFile) {
        val resolver = context.contentResolver
        val mime = file.mediaType
        val name = safeName(file)
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val displayName = if (extension == null || android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) == mime) name
            else "${name.substringBeforeLast('.', name)}.$extension"
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/mobby")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(file.bytes) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
