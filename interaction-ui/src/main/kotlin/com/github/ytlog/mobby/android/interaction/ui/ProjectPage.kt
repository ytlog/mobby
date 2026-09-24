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

internal data class ProjectEditor(val name: String, val workspace: String, val existing: Boolean,
    val busy: Boolean = false, val error: String? = null, val operation: Long)

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
            Text(AppStrings.onlyChangesGroupingExistingConversationsKeepTheirExecutionDirectory)
            ProjectChoices(projects, selected) { selected = it?.name }
        }
    }, confirmButton = { TextButton(onClick = { save(selected) }, enabled = selected == null || projects.any { it.name == selected }) { Text(AppStrings.save) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(AppStrings.cancel) } })
}

@Composable internal fun ProjectPage(vm: ConversationViewModel, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val editor by vm.projectEditor.collectAsStateWithLifecycle()
    val creatingWorkspace by vm.workspaceCreating.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadWorkspaces() }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.projectManagement, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption(AppStrings.theProjectSDefaultWorkspaceIsUsedForNew)
            state.error?.let { SettingsCaption(it, error = true) }
            if (state.loading) SettingsCaption(AppStrings.readingProjects)
            SettingsGroup {
                SettingsAction(AppStrings.newProject, enabled = !state.loading && state.error == null) { vm.openProject(null) }
                state.projects.forEach { project ->
                    GroupDivider()
                    val workspace = workspaces.firstOrNull { it.ref == project.defaultWorkspace }?.name ?: AppStrings.unavailable
                    SettingsItem(project.name, { vm.openProject(project) }, AppStrings.defaultWorkspace(workspace))
                }
            }
            if (state.projects.isEmpty() && !state.loading && state.error == null) EmptyPlaceholder(AppStrings.noProjectsYet, AppStrings.createProjectsToGroupConversationsByWorkspace)
        }
    }
    editor?.let { current -> AlertDialog(onDismissRequest = vm::dismissProject, containerColor = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(24.dp), tonalElevation = 0.dp,
        title = { Text(if (current.existing) current.name else AppStrings.newProject, style = MaterialTheme.typography.titleMedium) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsCaption(if (current.existing) AppStrings.changingTheDefaultWorkspaceDoesNotMoveExistingConversations else AppStrings.thisWorkspaceIsUsedForNewConversationsInThe)
                if (!current.existing) SettingsGroup { SettingsField(current.name, { vm.editProject(current.copy(name = it)) }, AppStrings.projectName, enabled = !current.busy) }
                WorkspacePicker(vm, current.workspace, "project:${current.operation}", !current.busy) { vm.editProject(current.copy(workspace = it)) }
                current.error?.let { SettingsCaption(it, error = true) }
                if (current.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(onClick = vm::saveProject,
            enabled = !current.busy && !creatingWorkspace && current.name.isNotBlank() && (current.existing || current.name.trim().length <= 80) && workspaces.any { it.ref == current.workspace }) { Text(AppStrings.saveProject) } },
        dismissButton = { TextButton(onClick = vm::dismissProject, enabled = !current.busy) { Text(AppStrings.cancel) } }) }
}
