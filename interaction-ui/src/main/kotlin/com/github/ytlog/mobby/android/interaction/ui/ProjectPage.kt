package com.github.ytlog.mobby.android.interaction.ui

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
    Text("项目", style = MaterialTheme.typography.labelLarge)
    ChoiceRow("无项目", selected == null, { select(null) }, enabled = enabled)
    projects.forEach { project -> ChoiceRow(project.name, project.name == selected, { select(project) }, enabled = enabled) }
    if (projects.isEmpty()) Text("可在会话抽屉的项目管理中新建项目。", style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun ProjectGroupDialog(conversation: Conversation, projects: List<Project>, dismiss: () -> Unit, save: (String?) -> Unit) {
    var selected by rememberSaveable(conversation.id.value) { mutableStateOf(conversation.project) }
    AlertDialog(onDismissRequest = dismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text("添加到项目") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("只改变分组，已有对话的执行目录与内容保持不变。")
            ProjectChoices(projects, selected) { selected = it?.name }
        }
    }, confirmButton = { TextButton(onClick = { save(selected) }, enabled = selected == null || projects.any { it.name == selected }) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable internal fun ProjectPage(vm: ConversationViewModel, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val editor by vm.projectEditor.collectAsStateWithLifecycle()
    val creatingWorkspace by vm.workspaceCreating.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadWorkspaces() }
    Column(Modifier.fillMaxSize()) {
        PageHeader("项目管理", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption("项目默认工作区用于新建对话。修改默认值不会移动已有对话。")
            state.error?.let { SettingsCaption(it, error = true) }
            if (state.loading) SettingsCaption("正在读取项目…")
            SettingsGroup {
                SettingsAction("新建项目", enabled = !state.loading && state.error == null) { vm.openProject(null) }
                state.projects.forEach { project ->
                    GroupDivider()
                    val workspace = workspaces.firstOrNull { it.ref == project.defaultWorkspace }?.name ?: "暂不可用"
                    SettingsItem(project.name, { vm.openProject(project) }, "默认工作区：$workspace")
                }
            }
            if (state.projects.isEmpty() && !state.loading && state.error == null) EmptyPlaceholder("尚无项目", "新建项目后，对话可以按工作区分组")
        }
    }
    editor?.let { current -> AlertDialog(onDismissRequest = vm::dismissProject, containerColor = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(24.dp), tonalElevation = 0.dp,
        title = { Text(if (current.existing) current.name else "新建项目", style = MaterialTheme.typography.titleMedium) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsCaption(if (current.existing) "修改默认工作区不会移动已有对话。" else "这个工作区用于项目里新建的对话。")
                if (!current.existing) SettingsGroup { SettingsField(current.name, { vm.editProject(current.copy(name = it)) }, "项目名称", enabled = !current.busy) }
                WorkspacePicker(vm, current.workspace, "project:${current.operation}", !current.busy) { vm.editProject(current.copy(workspace = it)) }
                current.error?.let { SettingsCaption(it, error = true) }
                if (current.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(onClick = vm::saveProject,
            enabled = !current.busy && !creatingWorkspace && current.name.isNotBlank() && (current.existing || current.name.trim().length <= 80) && workspaces.any { it.ref == current.workspace }) { Text("保存项目") } },
        dismissButton = { TextButton(onClick = vm::dismissProject, enabled = !current.busy) { Text("取消") } }) }
}
