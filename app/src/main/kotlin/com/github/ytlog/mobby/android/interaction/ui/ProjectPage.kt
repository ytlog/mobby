package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.interaction.domain.*

internal data class ProjectEditor(val name: String = "", val busy: Boolean = false, val error: String? = null, val operation: Long)

@Composable internal fun ProjectChoices(projects: List<Project>, selected: String?, enabled: Boolean = true, select: (Project?) -> Unit) {
    Text(AppStrings.project, style = MaterialTheme.typography.labelLarge)
    ChoiceRow(AppStrings.noProject, selected == null, { select(null) }, enabled = enabled)
    projects.forEach { project -> ChoiceRow(project.name, project.name == selected, { select(project) }, enabled = enabled) }
    if (projects.isEmpty()) Text(AppStrings.createProjectsInProjectManagementInTheConversationDrawer, style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun ProjectGroupDialog(conversation: Conversation, projects: List<Project>, dismiss: () -> Unit, save: (String?) -> Unit) {
    var selected by rememberSaveable(conversation.id.value) { mutableStateOf(conversation.project) }
    AlertDialog(onDismissRequest = dismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(AppStrings.addToProject) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(AppStrings.movingConversationChangesFutureExecutionDirectory)
            ProjectChoices(projects, selected) { selected = it?.name }
        }
    }, confirmButton = { TextButton(onClick = { save(selected) }, enabled = selected == null || projects.any { it.name == selected }) { Text(AppStrings.save) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(AppStrings.cancel) } })
}

@Composable internal fun ProjectPage(vm: ConversationViewModel, back: () -> Unit, openProject: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editor by vm.projectEditor.collectAsStateWithLifecycle()
    val created by vm.projectCreated.collectAsStateWithLifecycle()
    LaunchedEffect(created) { created?.let { openProject(it); vm.projectCreated.value = null } }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.projectManagement, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption(AppStrings.projectIsWorkspace)
            state.error?.let { SettingsCaption(it, error = true) }
            if (state.loading) SettingsCaption(AppStrings.readingProjects)
            SettingsGroup {
                SettingsAction(AppStrings.newProject, enabled = !state.loading && state.error == null) { vm.openProject() }
                state.projects.forEach { project ->
                    GroupDivider()
                    val count = state.conversations.count { it.conversation.project == project.name && !it.conversation.deleted }
                    SettingsItem(project.name, { openProject(project.name) }, AppStrings.projectConversationCount(count))
                }
            }
            if (state.projects.isEmpty() && !state.loading && state.error == null) EmptyPlaceholder(AppStrings.noProjectsYet, AppStrings.createProjectsToGroupConversationsByWorkspace)
        }
    }
    editor?.let { current -> AlertDialog(onDismissRequest = vm::dismissProject, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), tonalElevation = 0.dp,
        title = { Text(AppStrings.newProject, style = MaterialTheme.typography.titleMedium) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(current.name, { vm.editProject(current.copy(name = it)) }, Modifier.fillMaxWidth(),
                    label = { Text(AppStrings.projectName) }, singleLine = true, enabled = !current.busy)
                current.error?.let { SettingsCaption(it, error = true) }
                if (current.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(onClick = vm::saveProject,
            enabled = !current.busy && current.name.isNotBlank() && current.name.trim().length <= 80) { Text(AppStrings.create) } },
        dismissButton = { TextButton(onClick = vm::dismissProject, enabled = !current.busy) { Text(AppStrings.cancel) } }) }
}
