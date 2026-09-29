package com.github.ytlog.mobby.android.conversation.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.localization.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private data class LicenseNotice(val id: String, val title: String, val license: String, val text: String)

@Composable internal fun LicensePage(back: () -> Unit) {
    val context = LocalContext.current
    val sourceUrl = remember(context) {
        val version = requireNotNull(context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        Uri.parse("https://github.com/ytlog/mobby/releases/tag").buildUpon().appendPath("v$version").build()
    }
    var notices by remember { mutableStateOf<List<LicenseNotice>?>(null) }
    var selected by remember { mutableStateOf<LicenseNotice?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            notices = withContext(Dispatchers.IO) {
                listOf("third-party/maven.json", "third-party/runtime.json").flatMap { asset ->
                    context.assets.open(asset).bufferedReader().use { input ->
                        Json.parseToJsonElement(input.readText()).jsonArray.map { item ->
                            item.jsonObject.let { LicenseNotice(it.getValue("id").jsonPrimitive.content, it.getValue("title").jsonPrimitive.content,
                                it.getValue("license").jsonPrimitive.content, it.getValue("text").jsonPrimitive.content) }
                        }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    BackHandler(selected != null) { selected = null }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.openSourceLicenses, { if (selected != null) selected = null else back() })
        val detail = selected
        if (detail != null) {
            val paragraphs = remember(detail) { detail.text.split("\n\n") }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(detail.title, style = MaterialTheme.typography.titleSmall) }
                    items(paragraphs) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SettingsCaption(AppStrings.thirdPartyLicenseHelp)
                    SettingsGroup {
                        SettingsAction(AppStrings.correspondingSource) { context.startActivity(Intent(Intent.ACTION_VIEW, sourceUrl)) }
                        GroupDivider()
                        SettingsAction(AppStrings.claudeOfficialLicense) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/anthropics/claude-code/blob/main/LICENSE.md"))) }
                    }
                }
                if (error) item { SettingsCaption(AppStrings.licenseLoadFailed, error = true) }
                if (notices == null && !error) item { SettingsCaption(AppStrings.licenseLoading) }
                items(notices.orEmpty(), key = { it.id }) { notice ->
                    SettingsGroup { SettingsItem(notice.title, { selected = notice }, notice.license.takeIf { it.isNotBlank() }) }
                }
            }
        }
    }
}
