package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A project's conversations and shared context live together; the directory is not separately selectable. */
@Composable internal fun ProjectDetailPage(
    name: String, vm: ConversationViewModel, back: () -> Unit, moreProjects: () -> Unit,
    openConversation: (Conversation) -> Unit, openedNewConversation: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val skillsLoading by vm.skillsLoading.collectAsStateWithLifecycle()
    val skillsError by vm.skillsError.collectAsStateWithLifecycle()
    val project = state.projects.firstOrNull { it.name == name }
    var tab by rememberSaveable(name) { mutableIntStateOf(0) }
    var message by rememberSaveable(name) { mutableStateOf("") }
    var editingRules by remember { mutableStateOf(false) }
    var editingSkills by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab == 1) vm.loadSkills(AgentId.CODEX) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(name, back, trailing = { TextButton(onClick = moreProjects) { Text(AppStrings.moreProjects) } })
        if (project == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyPlaceholder(AppStrings.projectUnavailable) }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AppStrings.projectConversations, AppStrings.projectKnowledgeBase).forEachIndexed { index, title ->
                FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(title) })
            }
        }
        if (tab == 0) {
            val conversations = state.conversations.filter { it.conversation.project == name && !it.conversation.deleted && !it.conversation.archived }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (conversations.isEmpty()) item { EmptyPlaceholder(AppStrings.noProjectConversations) }
                items(conversations, key = { it.conversation.id.value }) { summary ->
                    Column(Modifier.fillMaxWidth().clickable { openConversation(summary.conversation) }.padding(horizontal = 8.dp, vertical = 14.dp)) {
                        Text(summary.conversation.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(summary.conversation.config.agent.label() + " · " + summary.conversation.config.model,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
            Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(24.dp), color = buttonColor(), contentColor = onButtonColor()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(message, { message = it }, Modifier.weight(1f).heightIn(min = 42.dp), maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = onButtonColor()), cursorBrush = SolidColor(onButtonColor()),
                        decorationBox = { inner -> Box { if (message.isEmpty()) Text(AppStrings.projectChatPlaceholder(name), color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                    ActionIcon(AppStrings.send, {
                        val text = message.trim()
                        if (text.isNotEmpty()) vm.sendInProject(name, text) { message = ""; openedNewConversation() }
                    }, AppIcons.Send)
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsGroup {
                    SettingsItem(AppStrings.projectRules, { editingRules = true }, project.rules.ifBlank { AppStrings.projectRuleHint })
                }
                SettingsGroup {
                    SettingsItem(AppStrings.skills, { editingSkills = true },
                        project.skills.sorted().joinToString().ifBlank { AppStrings.projectSkillHint })
                }
            }
        }
        if (editingRules) ProjectRulesDialog(project, vm) { editingRules = false }
        if (editingSkills) ProjectSkillsDialog(project, skills, skillsLoading, skillsError, vm) { editingSkills = false }
    }
}

@Composable private fun ProjectRulesDialog(project: Project, vm: ConversationViewModel, dismiss: () -> Unit) {
    var value by remember(project.name) { mutableStateOf(project.rules) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp),
        title = { Text(AppStrings.projectRules) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value, { value = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    placeholder = { Text(AppStrings.projectRuleHint) }, enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = {
            busy = true
            scope.launch {
                try { when (val result = vm.actions.saveProject(project.copy(rules = value))) {
                    OperationResult.Done -> dismiss()
                    is OperationResult.Failed -> error = result.message
                } } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = AppStrings.projectSaveIncompleteCheckAndRetry }
                finally { busy = false }
            }
        }, enabled = !busy && value.length <= 16_000) { Text(AppStrings.save) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(AppStrings.cancel) } })
}

@Composable private fun ProjectSkillsDialog(project: Project, skills: List<Skill>, loading: Boolean, loadError: String?, vm: ConversationViewModel, dismiss: () -> Unit) {
    var selected by remember(project.name) { mutableStateOf(project.skills) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val available = skills.filter { it.available }.map { it.name }.toSet()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp),
        title = { Text(AppStrings.skills) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (available + selected).sorted().forEach { name ->
                    ChoiceRow(if (name in available) name else "$name · ${AppStrings.unavailable}", name in selected,
                        { selected = if (name in selected) selected - name else selected + name }, enabled = !busy && (name in selected || name in available))
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                (error ?: loadError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = {
            busy = true
            scope.launch {
                try { when (val result = vm.actions.saveProject(project.copy(skills = selected))) {
                    OperationResult.Done -> dismiss()
                    is OperationResult.Failed -> error = result.message
                } } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = AppStrings.projectSaveIncompleteCheckAndRetry }
                finally { busy = false }
            }
        }, enabled = !busy && (!loading || selected.isEmpty()) && (loadError == null || selected.isEmpty())) { Text(AppStrings.save) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(AppStrings.cancel) } })
}
