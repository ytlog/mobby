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
        when (page) {
            "editor" -> if (editor != null) SkillEditorPage(editor, vm) else Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(AppStrings.editorStateWasNotRestoredIfYouJustSaved)
                Button(onClick = { vm.loadSkills(agent); back() }, colors = filledButtonColors()) { Text(AppStrings.backToSkillDirectory) }
            }
            "detail" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val skill = skills.firstOrNull { it.ref == selectedRef }
                Text(skill?.name ?: AppStrings.skills, style = MaterialTheme.typography.headlineSmall)
                Text(AppStrings.agent(agent.label(), skill?.let { skillSourceLabel(it.source) }.orEmpty()))
                Text(AppStrings.theAgentReadsInstructionsAndRequestsPermissionsWhenUsed, style = MaterialTheme.typography.bodySmall)
                detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                detail?.let { content ->
                    Text(content.description)
                    val bound = conversation?.creator != null && conversation.creator == skill?.ref
                    val chosen = conversation?.draft?.capabilities?.contains(skill?.ref) == true
                    Button(onClick = { if (conversation != null && skill != null) vm.enqueue { vm.report(vm.actions.setSkill(conversation.id, skill, !chosen)) } }, enabled = conversation != null && skill?.available == true && !bound, colors = filledButtonColors()) { Text(if (bound) AppStrings.boundToThisCreationConversation else if (chosen) AppStrings.remove else AppStrings.use) }
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
                                action = when {
                                    bound -> AppStrings.bound
                                    chosen -> AppStrings.remove
                                    else -> AppStrings.use
                                },
                                actionEnabled = conversation != null && skill.available && !bound,
                                onAction = { if (conversation != null && skill.available && !bound) vm.enqueue { vm.report(vm.actions.setSkill(conversation.id, skill, !chosen)) } },
                                onClick = { selectedRef = skill.ref; page = "detail" },
                            )
                        }
                    }
                }
            }
        }
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = raisedColor()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppStrings.addSkill, style = MaterialTheme.typography.titleLarge)
            val creator = skills.firstOrNull { it.name == "skill-creator" && it.available }
            TextButton(onClick = {
                adding = false
                if (conversation != null) vm.enqueue {
                    when (val created = vm.actions.createSkillConversation(conversation.id, agent)) {
                        is DataResult.Loaded -> onConversation()
                        is DataResult.Failed -> vm.feedback.send(created.message)
                    }
                }
            }, enabled = creator != null && conversation != null) { Text(AppStrings.createWithMobby) }
            if (creator == null) Text(AppStrings.noAvailableSkillCreatorFoundForThisAgent, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { adding = false; importAgent = agent; picker.launch(arrayOf("text/markdown", "text/plain", "text/x-markdown", "application/octet-stream")) }) { Text(AppStrings.importSkillFileMd) }
            TextButton(onClick = { adding = false; vm.openManualSkill(agent); page = "editor" }) { Text(AppStrings.createManually) }
        }
    }
}

@Composable private fun SkillEditorPage(editor: SkillEditor?, vm: ConversationViewModel) {
    if (editor == null) return
    fun change(value: SkillEditor) { vm.editSkill(value) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(AppStrings.agent2(editor.agent.label()))
        editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (editor.preview != null) {
            val preview = editor.preview
            Text(AppStrings.previewBeforeSaving, style = MaterialTheme.typography.titleMedium)
            Text(preview.name); Text(preview.description)
            preview.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            androidx.compose.foundation.text.selection.SelectionContainer { ReplyContent(preview.body, streaming = false, read = { _, _ -> }) }
            TextButton(onClick = { change(editor.copy(preview = null)) }, enabled = !editor.busy) { Text(AppStrings.backToEditing) }
            Button(onClick = vm::saveSkillEditor, enabled = !editor.busy && preview.issues.isEmpty(), colors = filledButtonColors()) { Text(AppStrings.saveSkill) }
        } else {
            if (editor.manual) {
                OutlinedTextField(editor.name, { change(editor.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text(AppStrings.nameLowercaseLettersDigitsHyphens) }, singleLine = true, enabled = !editor.busy)
                OutlinedTextField(editor.description, { change(editor.copy(description = it)) }, Modifier.fillMaxWidth(), label = { Text(AppStrings.purposeAndUseCases) }, minLines = 2, maxLines = 4, enabled = !editor.busy)
                OutlinedTextField(editor.body, { change(editor.copy(body = it)) }, Modifier.fillMaxWidth(), label = { Text(AppStrings.stepsAndRequirements) }, minLines = 5, maxLines = 12, enabled = !editor.busy)
            } else {
                Text(AppStrings.readingTheFileOnlyCreatesAPreviewCompleteName, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(editor.markdown, { change(editor.copy(markdown = it)) }, Modifier.fillMaxWidth(), label = { Text(AppStrings.skillMdSource) }, minLines = 6, maxLines = 14, enabled = !editor.busy)
                if (!editor.markdown.removePrefix("\uFEFF").trimStart().startsWith("---")) TextButton(onClick = { change(editor.copy(manual = true, body = editor.markdown)) }, enabled = !editor.busy) { Text(AppStrings.addMetadataToPlainMarkdown) }
            }
            Button(onClick = vm::validateSkillEditor, enabled = !editor.busy, colors = filledButtonColors()) { Text(AppStrings.validatePreview) }
        }
        Text(AppStrings.existingSkillsAreNeverOverwrittenSavingDoesNotStart, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun SkillProposalDialog(proposal: SkillProposal, vm: ConversationViewModel, sourceAvailable: Boolean, onDismiss: () -> Unit) {
    val state by vm.skillProposal.collectAsStateWithLifecycle()
    LaunchedEffect(proposal.ref) { vm.openSkillProposal(proposal) }
    val editor = state?.takeIf { it.proposal.ref == proposal.ref } ?: return
    AlertDialog(onDismissRequest = { if (!editor.busy) onDismiss() }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(AppStrings.skillDraft(proposal.agent.label())) }, text = {
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
