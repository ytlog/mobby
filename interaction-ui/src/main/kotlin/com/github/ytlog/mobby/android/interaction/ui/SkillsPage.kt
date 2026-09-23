package com.github.ytlog.mobby.android.interaction.ui

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

private val skillCatalogTabs = listOf("已添加", "精选", "用户技能", "CLI 内置")

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
            Box(Modifier.weight(1f)) { PageHeader(when (page) { "editor" -> "添加技能"; "detail" -> "技能详情"; else -> "技能" }, ::back) {
                if (page == "list") ActionIcon("添加技能", { adding = true }, AppIcons.Plus)
            } }
        }
        when (page) {
            "editor" -> if (editor != null) SkillEditorPage(editor, vm) else Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("编辑状态未恢复。若刚才执行过保存，请先到技能目录核对。")
                Button(onClick = { vm.loadSkills(agent); back() }, colors = filledButtonColors()) { Text("返回技能目录") }
            }
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
                    Button(onClick = { if (conversation != null && skill != null) vm.enqueue { vm.report(vm.actions.setSkill(conversation.id, skill, !chosen)) } }, enabled = conversation != null && skill?.available == true && !bound, colors = filledButtonColors()) { Text(if (bound) "已绑定此创建会话" else if (chosen) "移除" else "使用") }
                    ReplyContent(content.body, streaming = false, read = { _, _ -> })
                }
                if (detail == null && detailError == null) CircularProgressIndicator()
            }
            else -> {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索技能") }, singleLine = true, shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = raisedColor(), unfocusedContainerColor = raisedColor(),
                            focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.loadSkills(agent) }) { Text("重试") } }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                CatalogTabs(skillCatalogTabs, pagerState.currentPage) { pagerScope.launch { pagerState.animateScrollToPage(it) } }
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(), key = { skillCatalogTabs[it] }) { pageIndex ->
                    val filter = skillCatalogTabs[pageIndex]
                    val visible = skills.filter { (it.name.contains(query, true) || it.description.contains(query, true)) && (filter == "已添加" || filter == "精选" || filter == it.source) }
                    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (visible.isEmpty() && !loading && error == null) item { EmptyPlaceholder(if (skills.isEmpty()) "尚无技能" else "没有匹配的技能", if (skills.isEmpty()) "点右上角添加，把常用流程保存下来" else "换个分类或关键词试试") }
                        items(visible, key = { it.ref }) { skill ->
                            val chosen = conversation?.draft?.capabilities?.contains(skill.ref) == true
                            val bound = conversation?.creator != null && conversation.creator == skill.ref
                            val swatch = catalogSwatch(skill.ref)
                            CatalogRow(
                                title = skill.name,
                                subtitle = if (!skill.available) skill.unavailableReason ?: "技能不可用" else skill.description,
                                icon = AppIcons.Skill,
                                iconForeground = swatch.first,
                                iconBackground = swatch.second,
                                action = when {
                                    bound -> "已绑定"
                                    chosen -> "移除"
                                    else -> "使用"
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
            TextButton(onClick = { adding = false; vm.openManualSkill(agent); page = "editor" }) { Text("手动创建") }
        }
    }
}

@Composable private fun SkillEditorPage(editor: SkillEditor?, vm: ConversationViewModel) {
    if (editor == null) return
    fun change(value: SkillEditor) { vm.editSkill(value) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("适用 Agent：${editor.agent.label()}")
        editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (editor.preview != null) {
            val preview = editor.preview
            Text("保存前预览", style = MaterialTheme.typography.titleMedium)
            Text(preview.name); Text(preview.description)
            preview.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            androidx.compose.foundation.text.selection.SelectionContainer { ReplyContent(preview.body, streaming = false, read = { _, _ -> }) }
            TextButton(onClick = { change(editor.copy(preview = null)) }, enabled = !editor.busy) { Text("返回修改") }
            Button(onClick = vm::saveSkillEditor, enabled = !editor.busy && preview.issues.isEmpty(), colors = filledButtonColors()) { Text("保存技能") }
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
            Button(onClick = vm::validateSkillEditor, enabled = !editor.busy, colors = filledButtonColors()) { Text("校验并预览") }
        }
        Text("同名技能不会覆盖。保存不会启动任务；技能会进入 Agent 的本机技能目录。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun SkillProposalDialog(proposal: SkillProposal, vm: ConversationViewModel, sourceAvailable: Boolean, onDismiss: () -> Unit) {
    val state by vm.skillProposal.collectAsStateWithLifecycle()
    LaunchedEffect(proposal.ref) { vm.openSkillProposal(proposal) }
    val editor = state?.takeIf { it.proposal.ref == proposal.ref } ?: return
    AlertDialog(onDismissRequest = { if (!editor.busy) onDismiss() }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text("技能草稿 · ${proposal.agent.label()}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("确认保存后才会加入技能目录。可在这里修改生成内容。", style = MaterialTheme.typography.bodySmall)
            if (!sourceAvailable) Text("草稿来源暂不可用，编辑内容仍保留", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(editor.value, vm::editSkillProposal, Modifier.fillMaxWidth(), minLines = 5, maxLines = 10, enabled = !editor.busy)
            editor.preview?.issues?.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        if (editor.preview == null || editor.preview.issues.isNotEmpty()) TextButton(onClick = vm::validateSkillProposal, enabled = !editor.busy) { Text("校验") }
        else TextButton(onClick = vm::saveSkillProposal, enabled = !editor.busy && sourceAvailable) { Text("保存技能") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !editor.busy) { Text("返回，稍后处理") } })
}
