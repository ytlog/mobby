package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.localization.CatalogIds

import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.launch

internal data class SkillEditor(val agent: AgentId, val manual: Boolean, val name: String = "", val description: String = "", val body: String = "",
    val markdown: String = "", val preview: SkillContent? = null, val error: String? = null, val busy: Boolean = false, val operation: Long = 0)

private val skillCatalogTabs get() = listOf(AppStrings.added, AppStrings.featured, AppStrings.userSkills, AppStrings.builtIntoCli)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable internal fun SkillsPage(vm: ConversationViewModel, onBack: () -> Unit, onConversation: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val conversation = state.selected?.conversation
    val agent = conversation?.config?.agent ?: AgentId.CODEX
    var query by rememberSaveable { mutableStateOf("") }
    val pagerState = rememberPagerState(pageCount = { skillCatalogTabs.size })
    val pagerScope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("list") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var selectedRef by rememberSaveable { mutableStateOf<String?>(null) }
    val catalogue by vm.skills.collectAsStateWithLifecycle()
    val skills = catalogue.filter { it.agent == agent }
    var importAgent by rememberSaveable { mutableStateOf(agent) }
    val error by vm.skillsError.collectAsStateWithLifecycle()
    val loading by vm.skillsLoading.collectAsStateWithLifecycle()
    val editor by vm.skillEditor.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<SkillContent?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            page = "editor"
            vm.importSkillFile(importAgent, uri.toString())
        }
    }
    val saved by vm.skillEditorSaved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) {
        saved?.let { operation ->
            if (page == "editor" && vm.skillEditor.value == null) page = "list"
            vm.consumeSkillEditorSaved(operation)
        }
    }
    LaunchedEffect(agent, LanguagePreferences.current) { vm.loadSkills(agent) }
    LaunchedEffect(selectedRef) {
        detail = null; detailError = null
        selectedRef?.let { ref -> when (val loaded = vm.actions.readSkill(ref)) {
            is DataResult.Loaded -> detail = loaded.value
            is DataResult.Failed -> detailError = loaded.message
        } }
    }
    fun back() { if (page != "list") { page = "list"; selectedRef = null } else onBack() }
    BackHandler(page != "list" || adding) { if (adding) adding = false else back() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { PageHeader(when (page) { "editor" -> AppStrings.addSkill; "detail" -> AppStrings.skillDetails; else -> AppStrings.skills }, ::back) {
                if (page == "list") ActionIcon(AppStrings.addSkill, { adding = true }, AppIcons.Plus)
            } }
        }
        val activeEditor = editor
        when (page) {
            "editor" -> if (activeEditor != null) SkillEditorPage(activeEditor, vm) else Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(AppStrings.editorStateWasNotRestoredIfYouJustSaved)
                Button(onClick = { vm.loadSkills(agent); back() }, colors = filledButtonColors()) { Text(AppStrings.backToSkillDirectory) }
            }
            "detail" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val skill = skills.firstOrNull { it.ref == selectedRef }
                Text(skill?.name ?: AppStrings.skills, style = MaterialTheme.typography.headlineSmall)
                Text(skill?.let { skillSourceLabel(it.source) }.orEmpty())
                Text(AppStrings.theAgentReadsInstructionsAndRequestsPermissionsWhenUsed, style = MaterialTheme.typography.bodySmall)
                detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                detail?.let { content ->
                    Text(content.description)
                    val bound = conversation?.creator != null && conversation.creator == skill?.ref
                    val chosen = conversation?.draft?.capabilities?.contains(skill?.ref) == true
                    Button(onClick = { if (conversation != null && skill != null) vm.enqueue { vm.report(vm.actions.setSkill(conversation.id, skill, !bound && !chosen)) } }, enabled = conversation != null && (bound || chosen || skill?.available == true), colors = filledButtonColors()) { Text(if (bound || chosen) AppStrings.remove else AppStrings.use) }
                    if (bound) Text(AppStrings.removeSkillCreatorBindingHint, style = MaterialTheme.typography.bodySmall)
                    ReplyContent(content.body, streaming = false, read = { _, _ -> })
                }
                if (detail == null && detailError == null) CircularProgressIndicator()
            }
            else -> {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(AppStrings.searchSkills) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = raisedColor(), unfocusedContainerColor = raisedColor(),
                            focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.loadSkills(agent) }) { Text(AppStrings.retry) } }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                CatalogTabs(skillCatalogTabs, pagerState.currentPage) { pagerScope.launch { pagerState.animateScrollToPage(it) } }
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(), key = { it }) { pageIndex ->
                    val filter = when (pageIndex) { 2 -> CatalogIds.USER_SKILLS; 3 -> CatalogIds.BUILTIN_SKILLS; else -> null }
                    val visible = skills.filter { (it.name.contains(query, true) || it.description.contains(query, true)) && (filter == null || filter == it.source) }
                    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (visible.isEmpty() && !loading && error == null) item { EmptyPlaceholder(if (skills.isEmpty()) AppStrings.noSkillsYet else AppStrings.noMatchingSkills, if (skills.isEmpty()) AppStrings.tapAddAtTheTopRightToSaveA else AppStrings.tryAnotherCategoryOrSearchTerm) }
                        items(visible, key = { it.ref }) { skill ->
                            val chosen = conversation?.draft?.capabilities?.contains(skill.ref) == true
                            val bound = conversation?.creator != null && conversation.creator == skill.ref
                            val swatch = catalogSwatch(skill.ref)
                            CatalogRow(
                                title = skill.name,
                                subtitle = if (!skill.available) skill.unavailableReason ?: AppStrings.skillUnavailable else skill.description,
                                icon = AppIcons.Skill,
                                iconForeground = swatch.first,
                                iconBackground = swatch.second,
                                action = if (bound || chosen) AppStrings.remove else AppStrings.use,
                                actionEnabled = conversation != null && (bound || chosen || skill.available),
                                onAction = { if (conversation != null && (bound || chosen || skill.available)) vm.enqueue { vm.report(vm.actions.setSkill(conversation.id, skill, !bound && !chosen)) } },
                                onClick = { selectedRef = skill.ref; page = "detail" },
                            )
                        }
                    }
                }
            }
        }
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = addSheetColor(), contentColor = addInkColor(), shape = RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp)) {
        val ink = addInkColor()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(AppStrings.addSkill, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleLarge, color = ink)
            Text(AppStrings.chooseHowToAddSkill, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = ink)
            val creator = skills.firstOrNull { it.name == "skill-creator" && it.available }
            val canCreateWithAgent = creator != null && conversation != null
            val creatorDetail = when {
                creator == null -> AppStrings.skillCreatorUnavailable
                conversation == null -> AppStrings.skillCreatorRequiresConversation
                else -> AppStrings.createSkillWithCurrentConversation
            }
            SkillAddAction(AppStrings.createWithMobby, creatorDetail,
                AppIcons.Skill, canCreateWithAgent) {
                adding = false
                if (conversation != null) vm.enqueue {
                    when (val created = vm.actions.createSkillConversation(conversation.id, agent)) {
                        is DataResult.Loaded -> onConversation()
                        is DataResult.Failed -> vm.feedback.send(created.message)
                    }
                }
            }
            SkillAddAction(AppStrings.importSkillFileMd, AppStrings.importExistingSkillDescription, AppIcons.Upload) {
                adding = false; importAgent = agent; picker.launch(arrayOf("text/markdown", "text/plain", "text/x-markdown", "application/octet-stream"))
            }
            SkillAddAction(AppStrings.createManually, AppStrings.createManualSkillDescription, AppIcons.Edit) {
                adding = false; vm.openManualSkill(agent); page = "editor"
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable private fun SkillAddAction(title: String, detail: String, icon: AppGlyph, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = addInkColor()
    val content = if (enabled) ink else ink.copy(alpha = 0.38f)
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp), color = addTileColor(), contentColor = content) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon(icon, null, Modifier.size(24.dp), tint = content)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = content)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = content)
            }
            AppIcon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = content)
        }
    }
}

@Composable private fun SkillEditorPage(editor: SkillEditor, vm: ConversationViewModel) {
    fun change(value: SkillEditor) { vm.editSkill(value) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption(AppStrings.sharedAcrossAgents)
            editor.error?.let { SettingsCaption(it, error = true) }
            if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (editor.preview != null) {
                val preview = editor.preview
                SettingsGroup(AppStrings.previewBeforeSaving) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(preview.name, style = MaterialTheme.typography.titleMedium)
                        Text(preview.description, style = MaterialTheme.typography.bodyMedium)
                        preview.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        androidx.compose.foundation.text.selection.SelectionContainer { ReplyContent(preview.body, streaming = false, read = { _, _ -> }) }
                    }
                }
            } else if (editor.manual) {
                SettingsGroup(AppStrings.skillBasics) {
                    SettingsField(editor.name, { change(editor.copy(name = it)) }, AppStrings.nameLowercaseLettersDigitsHyphens, enabled = !editor.busy)
                    GroupDivider()
                    SettingsField(editor.description, { change(editor.copy(description = it)) }, AppStrings.purposeAndUseCases,
                        enabled = !editor.busy, singleLine = false, maxLines = 4)
                }
                SettingsGroup(AppStrings.skillInstructions) {
                    OutlinedTextField(editor.body, { change(editor.copy(body = it)) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        label = { Text(AppStrings.stepsAndRequirements) }, minLines = 7, maxLines = 14, enabled = !editor.busy,
                        shape = RoundedCornerShape(12.dp), colors = settingsFieldColors())
                }
            } else {
                SettingsCaption(AppStrings.readingTheFileOnlyCreatesAPreviewCompleteName)
                SettingsGroup(AppStrings.skillMdSource) {
                    OutlinedTextField(editor.markdown, { change(editor.copy(markdown = it)) }, Modifier.fillMaxWidth().padding(12.dp),
                        minLines = 8, maxLines = 16, enabled = !editor.busy, shape = RoundedCornerShape(12.dp), colors = settingsFieldColors())
                }
                if (!editor.markdown.removePrefix("\uFEFF").trimStart().startsWith("---")) SettingsAction(AppStrings.addMetadataToPlainMarkdown, enabled = !editor.busy) {
                    change(editor.copy(manual = true, body = editor.markdown))
                }
            }
            SettingsCaption(AppStrings.existingSkillsAreNeverOverwrittenSavingDoesNotStart)
        }
        Surface(color = raisedColor()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editor.preview != null) TextButton(onClick = { change(editor.copy(preview = null)) }, enabled = !editor.busy) { Text(AppStrings.backToEditing) }
                Spacer(Modifier.weight(1f))
                Button(onClick = if (editor.preview == null) vm::validateSkillEditor else vm::saveSkillEditor,
                    enabled = !editor.busy && (editor.preview == null || editor.preview.issues.isEmpty()), colors = filledButtonColors()) {
                    Text(if (editor.preview == null) AppStrings.validatePreview else AppStrings.saveSkill)
                }
            }
        }
    }
}

@Composable internal fun SkillProposalDialog(proposal: SkillProposal, vm: ConversationViewModel, sourceAvailable: Boolean, onDismiss: () -> Unit) {
    val state by vm.skillProposal.collectAsStateWithLifecycle()
    LaunchedEffect(proposal.ref) { vm.openSkillProposal(proposal) }
    val editor = state?.takeIf { it.proposal.ref == proposal.ref } ?: return
    AlertDialog(onDismissRequest = { if (!editor.busy) onDismiss() }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(AppStrings.skillDraft) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AppStrings.theSkillIsAddedToTheDirectoryOnlyAfter, style = MaterialTheme.typography.bodySmall)
            if (!sourceAvailable) Text(AppStrings.draftSourceTemporarilyUnavailableYourEditsArePreserved, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(editor.value, vm::editSkillProposal, Modifier.fillMaxWidth(), minLines = 5, maxLines = 10, enabled = !editor.busy)
            editor.preview?.issues?.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        if (editor.preview == null || editor.preview.issues.isNotEmpty()) TextButton(onClick = vm::validateSkillProposal, enabled = !editor.busy) { Text(AppStrings.validate) }
        else TextButton(onClick = vm::saveSkillProposal, enabled = !editor.busy && sourceAvailable) { Text(AppStrings.saveSkill) }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !editor.busy) { Text(AppStrings.backHandleLater) } })
}

internal fun skillSourceLabel(source: String): String = when (source) {
    CatalogIds.USER_SKILLS -> AppStrings.userSkills
    CatalogIds.BUILTIN_SKILLS -> AppStrings.builtIntoCli
    else -> source
}
