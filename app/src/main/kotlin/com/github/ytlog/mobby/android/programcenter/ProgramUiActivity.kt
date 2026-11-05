package com.github.ytlog.mobby.android.programcenter

import com.github.ytlog.mobby.android.conversation.ui.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.github.ytlog.mobby.android.MobbyApplication
import com.github.ytlog.mobby.android.conversation.ui.MobbyDarkScheme
import com.github.ytlog.mobby.android.conversation.ui.MobbyLightScheme
import com.github.ytlog.mobby.android.conversation.ui.MobbyTypography
import com.github.ytlog.mobby.android.conversation.ui.PageHeader
import com.github.ytlog.mobby.android.conversation.ui.VirtualScreenPreviewLayer
import com.github.ytlog.mobby.android.conversation.ui.TextButton
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.programs.ui.UiAttach
import com.github.ytlog.mobby.android.programs.ui.UiSession
import kotlinx.coroutines.launch

/**
 * A program's page. mobby's own title bar (program name, back, stop) stays above the WebView so
 * the program cannot hide which program it is or how to leave it.
 */
class ProgramUiActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var session: UiSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val programId = intent.getStringExtra(EXTRA_PROGRAM) ?: return finish()
        val platform = (application as MobbyApplication).programs
        if (savedInstanceState == null) ProgramOpenHistory(this).record(programId)
        setContent {
            var title by remember { mutableStateOf(programId) }
            var failure by remember { mutableStateOf<String?>(null) }
            var link by remember { mutableStateOf<Uri?>(null) }
            var generation by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { platform.programs.collect { list -> list.firstOrNull { it.id == programId }?.let { title = it.name } } }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) MobbyDarkScheme else MobbyLightScheme, typography = MobbyTypography) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        PageHeader(title, { finish() }) {
                            TextButton(onClick = { lifecycleScope.launch { platform.stop(programId) } }) { Text(AppStrings.stopProgram) }
                        }
                        if (failure != null) Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(failure!!, style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = { failure = null; generation++ }) { Text(AppStrings.reopen) }
                        } else key(generation) {
                            AndroidView(factory = { context ->
                                WebView(context).also { view ->
                                    webView = view
                                    lifecycleScope.launch {
                                        when (val result = platform.ui.attach(view, programId, { uri -> link = uri }, { failure = AppStrings.programPageCrashed; release() })) {
                                            is UiAttach.Opened -> session = result.session
                                            is UiAttach.Refused -> failure = "${AppStrings.programPageUnavailable}: ${result.reason}"
                                        }
                                    }
                                }
                            }, modifier = Modifier.fillMaxSize())
                        }
                    }
                    VirtualScreenPreviewLayer()
                }
                link?.let { uri ->
                    AlertDialog(onDismissRequest = { link = null }, title = { Text(AppStrings.openLinkTitle) }, text = { Text(uri.toString()) },
                        confirmButton = { TextButton(onClick = { link = null; startActivity(Intent(Intent.ACTION_VIEW, uri)) }) { Text(AppStrings.open) } },
                        dismissButton = { TextButton(onClick = { link = null }) { Text(AppStrings.cancel) } })
                }
            }
        }
    }

    private fun release() {
        session?.let { (application as MobbyApplication).programs.ui.close(it) }
        session = null
        webView?.destroy()
        webView = null
    }

    override fun onDestroy() { release(); super.onDestroy() }

    companion object {
        private const val EXTRA_PROGRAM = "programId"

        fun intent(context: Context, programId: String) = Intent(context, ProgramUiActivity::class.java)
            .putExtra(EXTRA_PROGRAM, programId).setData(Uri.parse("mobby-program://$programId"))

        fun pendingIntent(context: Context, programId: String): PendingIntent = PendingIntent.getActivity(context, programId.hashCode(),
            intent(context, programId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
