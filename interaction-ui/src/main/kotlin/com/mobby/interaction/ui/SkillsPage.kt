package com.mobby.interaction.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobby.interaction.domain.*

internal data class SkillEditor(val agent: AgentId, val manual: Boolean, val name: String = "", val description: String = "", val body: String = "",
    val markdown: String = "", val preview: SkillContent? = null, val error: String? = null, val busy: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SkillsPage(vm: ConversationViewModel, onBack: () -> Unit, onConversation: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val conversation = state.selected?.conversation
    val agent = conversation?.config?.agent ?: AgentId.CODEX
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
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
            vm.skillEditor.value = SkillEditor(importAgent, manual = false, busy = true)
            vm.enqueue {
                when (val loaded = vm.actions.readSkillImport(uri.toString())) {
                    is DataResult.Loaded -> vm.skillEditor.value = SkillEditor(importAgent, false, loaded.value.name, loaded.value.description, loaded.value.body, loaded.value.markdown,
                        preview = loaded.value.takeIf { it.issues.isEmpty() }, error = loaded.value.issues.joinToString("\n").ifBlank { null })
                    is DataResult.Failed -> vm.skillEditor.value = SkillEditor(importAgent, false, error = loaded.message)
                }
            }
        }
    }
    LaunchedEffect(agent) { vm.loadSkills(agent) }
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
            Box(Modifier.weight(1f)) { PageHeader(when (page) { "editor" -> "添加技能"; "detail" -> "技能详情"; else -> "技能" }, ::back) }
            if (page == "list") ActionIcon("添加技能", { adding = true }, Icons.Outlined.Add)
        }
        when (page) {
            "editor" -> SkillEditorPage(editor, vm, onSaved = { page = "list"; vm.loadSkills(agent) })
            "detail" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val skill = skills.firstOrNull { it.ref == selectedRef }
                Text(skill?.name ?: "技能", style = MaterialTheme.typography.headlineSmall)
                Text("适用 Agent：${agent.label()} · ${skill?.source.orEmpty()}")
                Text("使用时由 Agent 读取说明并申请所需权限；访问范围取决于技能内容与 Agent 权限。", style = MaterialTheme.typography.bodySmall)
                detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                detail?.let { content ->
                    Text(content.description)
                    val bound = conversation?.creator != null && conversation.creator == skill?.ref
                    val chosen = conversation?.draft?.capabilities?.contains(skill?.ref) == true
                    Button(onClick = { if (conversation != null && skill != null) vm.enqueue { vm.report(vm.actions.setSkill(conversation.id, skill, !chosen)) } }, enabled = conversation != null && skill?.available == true && !bound) { Text(if (bound) "已绑定此创建会话" else if (chosen) "从本轮移除" else "加入本轮草稿") }
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(content.body, style = MaterialTheme.typography.bodyLarge) }
                }
                if (detail == null && detailError == null) CircularProgressIndicator()
            }
            else -> {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("为 ${agent.label()} 保存和选择可重复使用的工作流程。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索技能") }, singleLine = true)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("全部", "用户技能", "CLI 内置", "不可用").forEach { value -> FilterChip(filter == value, { filter = value }, label = { Text(value) }) }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.loadSkills(agent) }) { Text("重试") } }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val visible = skills.filter { (it.name.contains(query, true) || it.description.contains(query, true)) && (filter == "全部" || filter == "不可用" && !it.available || filter == it.source) }
                    if (visible.isEmpty() && !loading && error == null) item { Text(if (skills.isEmpty()) "尚无技能，可从右上角添加。" else "没有匹配的技能") }
                    items(visible, key = { it.ref }) { skill ->
                        OutlinedCard(onClick = { selectedRef = skill.ref; page = "detail" }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(skill.name, style = MaterialTheme.typography.titleMedium)
                                Text(skill.description, maxLines = 3)
                                Text(if (!skill.available) skill.unavailableReason ?: "技能不可用" else if (conversation?.draft?.capabilities?.contains(skill.ref) == true) "已加入本轮 · ${skill.source}" else skill.source, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("添加技能", style = MaterialTheme.typography.titleLarge)
            val creator = skills.firstOrNull { it.name == "skill-creator" && it.available }
            TextButton(onClick = {
                adding = false
                if (conversation != null) vm.enqueue {
                    when (val created = vm.actions.createSkillConversation(conversation.id, agent)) {
                        is DataResult.Loaded -> onConversation()
                        is DataResult.Failed -> vm.feedback.send(created.message)
                    }
                }
            }, enabled = creator != null && conversation != null) { Text("与 mobby 对话创建") }
            if (creator == null) Text("当前 Agent 未发现可用的 Skill Creator", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { adding = false; importAgent = agent; picker.launch(arrayOf("text/markdown", "text/plain", "text/x-markdown", "application/octet-stream")) }) { Text("导入技能文件（.md）") }
            TextButton(onClick = { adding = false; vm.skillEditor.value = SkillEditor(agent, manual = true); page = "editor" }) { Text("手动创建") }
        }
    }
}

@Composable private fun SkillEditorPage(editor: SkillEditor?, vm: ConversationViewModel, onSaved: () -> Unit) {
    if (editor == null) return
    fun change(value: SkillEditor) { vm.skillEditor.value = value.copy(error = null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("适用 Agent：${editor.agent.label()}")
        editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (editor.preview != null) {
            val preview = editor.preview
            Text("保存前预览", style = MaterialTheme.typography.titleMedium)
            Text(preview.name); Text(preview.description)
            preview.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            androidx.compose.foundation.text.selection.SelectionContainer { Text(preview.body, style = MaterialTheme.typography.bodyLarge) }
            TextButton(onClick = { change(editor.copy(preview = null)) }, enabled = !editor.busy) { Text("返回修改") }
            Button(onClick = {
                change(editor.copy(busy = true))
                vm.enqueue {
                    val result = if (editor.manual) vm.actions.saveManualSkill(editor.agent, editor.name, editor.description, editor.body)
                        else vm.actions.importSkill(editor.agent, preview.markdown)
                    when (result) {
                        is DataResult.Loaded -> { vm.skillEditor.value = null; vm.feedback.send("技能已保存，可加入本轮草稿"); onSaved() }
                        is DataResult.Failed -> vm.skillEditor.value = editor.copy(error = result.message, busy = false)
                    }
                }
            }, enabled = !editor.busy && preview.issues.isEmpty()) { Text("保存技能") }
        } else {
            if (editor.manual) {
                OutlinedTextField(editor.name, { change(editor.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("名称（小写英文、数字、连字符）") }, singleLine = true, enabled = !editor.busy)
                OutlinedTextField(editor.description, { change(editor.copy(description = it)) }, Modifier.fillMaxWidth(), label = { Text("用途与使用场景") }, minLines = 2, maxLines = 4, enabled = !editor.busy)
                OutlinedTextField(editor.body, { change(editor.copy(body = it)) }, Modifier.fillMaxWidth(), label = { Text("执行步骤与要求") }, minLines = 5, maxLines = 12, enabled = !editor.busy)
            } else {
                Text("读取文件只用于预览。请补全 name、description 与正文，确认后才会保存。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(editor.markdown, { change(editor.copy(markdown = it)) }, Modifier.fillMaxWidth(), label = { Text("SKILL.md 原文") }, minLines = 6, maxLines = 14, enabled = !editor.busy)
                if (!editor.markdown.removePrefix("\uFEFF").trimStart().startsWith("---")) TextButton(onClick = { change(editor.copy(manual = true, body = editor.markdown)) }, enabled = !editor.busy) { Text("为普通 Markdown 补全元信息") }
            }
            Button(onClick = {
                change(editor.copy(busy = true))
                vm.enqueue {
                    when (val result = if (editor.manual) vm.actions.previewManualSkill(editor.agent, editor.name, editor.description, editor.body) else vm.actions.previewSkill(editor.markdown)) {
                        is DataResult.Loaded -> vm.skillEditor.value = editor.copy(preview = result.value, busy = false)
                        is DataResult.Failed -> vm.skillEditor.value = editor.copy(error = result.message, busy = false)
                    }
                }
            }, enabled = !editor.busy) { Text("校验并预览") }
        }
        Text("同名技能不会覆盖。保存不会启动任务；技能会进入 Agent 的本机技能目录。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun SkillProposalDialog(proposal: SkillProposal, vm: ConversationViewModel, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var markdown by remember(proposal.ref) { mutableStateOf(proposal.markdown) }
    var preview by remember(proposal.ref) { mutableStateOf<SkillContent?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun validate() {
        busy = true; error = null
        val content = markdown
        vm.enqueue {
            try { when (val result = vm.actions.previewSkill(content)) {
                is DataResult.Loaded -> preview = result.value
                is DataResult.Failed -> error = result.message
            } } finally { busy = false }
        }
    }
    LaunchedEffect(proposal.ref) { validate() }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("技能草稿 · ${proposal.agent.label()}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("确认保存后才会加入技能目录。可在这里修改生成内容。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(markdown, { markdown = it; preview = null; error = null }, Modifier.fillMaxWidth(), minLines = 5, maxLines = 10, enabled = !busy)
            preview?.issues?.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        if (preview == null || preview?.issues?.isNotEmpty() == true) TextButton(onClick = ::validate, enabled = !busy) { Text("校验") }
        else TextButton(onClick = {
            busy = true; error = null
            val content = markdown
            vm.enqueue {
                try { when (val saved = vm.actions.importSkill(proposal.agent, content)) {
                    is DataResult.Loaded -> { vm.feedback.send("技能已保存"); onSaved() }
                    is DataResult.Failed -> error = saved.message
                } } finally { busy = false }
            }
        }, enabled = !busy) { Text("保存技能") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("返回，稍后处理") } })
}
