package com.mobby.interaction.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobby.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt

class InteractionHostActions(val share: (String) -> Unit, val shortcut: (String, String) -> Unit, val appearance: (Boolean) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun InteractionEntry(actions: InteractionUseCases, hostActions: InteractionHostActions) {
    val factory = remember(actions) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ConversationViewModel(actions) as T
    } }
    val vm: ConversationViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val system by vm.status.collectAsStateWithLifecycle()
    val agentOptions by vm.agents.collectAsStateWithLifecycle()
    var fileTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var fileWorkspace by rememberSaveable { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = fileTarget; val workspace = fileWorkspace
        fileTarget = null; fileWorkspace = null
        if (uri != null && id != null && workspace != null) vm.importAttachment(ConversationId(id), workspace, uri.toString())
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = fileTarget; val workspace = fileWorkspace
        fileTarget = null; fileWorkspace = null
        if (uri != null && id != null && workspace != null) vm.importAttachment(ConversationId(id), workspace, uri.toString())
    }
    var route by rememberSaveable { mutableStateOf("conversation") }
    var drawer by rememberSaveable { mutableStateOf(false) }
    val appearance by actions.appearance.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var skillProposal by remember { mutableStateOf<SkillProposal?>(null) }
    var voice by remember { mutableStateOf<ComposerState?>(null) }
    var reading by remember { mutableStateOf<Pair<String, String>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dark = appearance == Appearance.DARK || appearance == Appearance.SYSTEM && isSystemInDarkTheme()
    LaunchedEffect(dark) { hostActions.appearance(dark) }
    val colors = if (dark) darkColorScheme(background = Color(0xFF111213), surface = Color(0xFF111213), surfaceVariant = Color(0xFF2C2E30), primary = Color(0xFF80BAFF))
        else lightColorScheme(background = Color(0xFFFAFAFA), surface = Color(0xFFFAFAFA), surfaceVariant = Color(0xFFE5E7E9), primary = Color(0xFF145BB0))
    LaunchedEffect(vm) { for (message in vm.feedback) snackbar.showSnackbar(message) }
    fun navigate(next: String) { keyboard?.hide(); focus.clearFocus(); drawer = false; route = next }
    BackHandler(drawer) { drawer = false }
    BackHandler(route != "conversation") { route = when (route) { "gateway", "diagnostic", "archived" -> "settings"; "skills", "plugins" -> "add"; else -> "conversation" } }
    MaterialTheme(colorScheme = colors) {
        val camera = rememberCameraCapture(actions, { captured ->
            actions.importAttachment(ConversationId(captured.conversation), captured.workspace, requireNotNull(captured.attachmentUri))
        }, { message -> vm.report(OperationResult.Failed(message)) })
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding().clipToBounds()) {
                val fullWidth = maxWidth
                val availableHeight = maxHeight
                val drawerWidth = minOf(360.dp, (fullWidth - 56.dp).coerceAtLeast(0.dp))
                val progress by animateFloatAsState(if (drawer) 1f else 0f, tween(240), label = "推开式会话抽屉")
                val pixels = with(LocalDensity.current) { drawerWidth.toPx() }
                if (drawer || progress > 0f) ConversationDrawer(state, vm, onSelect = { c -> vm.enqueue { actions.select(c.id) }; drawer = false },
                    onNew = { drawer = false; dialog = "new" }, onSettings = { navigate("settings") }, onClose = { drawer = false },
                    modifier = Modifier.width(drawerWidth).fillMaxHeight().offset { IntOffset(((progress - 1f) * pixels).roundToInt(), 0) })
                Surface(Modifier.requiredWidth(fullWidth).fillMaxHeight().offset { IntOffset((pixels * progress).roundToInt(), 0) }
                    .then(if (drawer) Modifier.clearAndSetSemantics {} else Modifier)) {
                    when (route) {
                        "settings" -> SettingsPage(system, appearance, { value -> vm.enqueue { vm.report(actions.setAppearance(value)) } }, { navigate(it) }, { route = "conversation" }, vm)
                        "gateway" -> GatewayPage(vm) { route = "settings" }
                        "diagnostic" -> DiagnosticPage(vm) { route = "settings" }
                        "archived" -> ArchivedPage(state, vm) { route = "settings" }
                        "skills" -> SkillsPage(vm, onBack = { route = "add" }, onConversation = { route = "conversation" })
                        "plugins" -> PluginPage(onBack = { route = "add" })
                        else -> Column(Modifier.fillMaxSize()) {
                            ConversationToolbar(state.selected?.conversation, vm, onMenu = { keyboard?.hide(); focus.clearFocus(); drawer = true },
                                onNew = { dialog = "new" }, onMore = { dialog = it })
                            if (!system.connected || !system.ready) Text(system.message, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                            when {
                                state.error != null -> Text(state.error!!, Modifier.padding(24.dp).weight(1f), color = MaterialTheme.colorScheme.error)
                                state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                state.selected == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Button(onClick = { dialog = "new" }) { Text("新建对话") } }
                                else -> {
                                    val detail = state.selected!!
                                    key(detail.conversation.id) { Timeline(detail, vm, Modifier.weight(1f), read = { title, text -> reading = title to text }, hostActions = hostActions, proposal = { skillProposal = it }) }
                                    Composer(detail, state, system, vm, onAdd = { navigate("add") }, onVoice = { keyboard?.hide(); voice = vm.composer.value })
                                }
                            }
                        }
                    }
                }
                if (drawer) Box(Modifier.offset { IntOffset((pixels * progress).roundToInt(), 0) }.fillMaxSize().clickable { drawer = false })
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                if (route == "add") ModalBottomSheet(onDismissRequest = { route = "conversation" }) {
                    Column(Modifier.heightIn(max = (availableHeight - 48.dp).coerceAtLeast(120.dp)).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("添加内容与能力", style = MaterialTheme.typography.titleLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            val target = state.selected?.conversation
                            val canImport = target != null && !target.archived && !target.deleted && target.draft.pendingAttachment == null && target.draft.attachments.size < 4
                            TextButton(onClick = { if (target != null) { navigate("conversation"); camera.start(target) } },
                                enabled = canImport && !camera.busy && agentOptions.any { it.agent == target?.config?.agent && it.images && it.unavailable == null }) { Text("拍照") }
                            TextButton(onClick = {
                                if (target != null) {
                                    fileTarget = target.id.value; fileWorkspace = target.config.workspace; route = "conversation"
                                    photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            }, enabled = canImport && agentOptions.any { it.agent == target?.config?.agent && it.images && it.unavailable == null }) { Text("照片") }
                            TextButton(onClick = {
                                if (target != null) { fileTarget = target.id.value; fileWorkspace = target.config.workspace; route = "conversation"; filePicker.launch(arrayOf("*/*")) }
                            }, enabled = target != null && !target.archived && !target.deleted && target.draft.pendingAttachment == null && target.draft.attachments.size < 4 && agentOptions.any { it.agent == target.config.agent && it.resources && it.unavailable == null }) { Text("本地文件") }
                        }
                        Text("支持 UTF-8 文本（32 KiB）与 PNG/JPEG（2 MiB，最长边 4096、最多 800 万像素），每轮最多 4 个附件；文字编码后合计最多 64 KiB。模型须支持图片，Codex 的 Messages 网关不支持图片。拍照会先预览确认；PDF 尚不可用。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { route = "conversation"; voice = vm.composer.value }) { Text("语音输入") }
                        TextButton(onClick = { route = "plugins" }) { Text("插件") }
                        TextButton(onClick = { route = "skills" }) { Text("技能") }
                        Text("联网搜索：由 Agent 当前可用工具决定；暂不提供额外搜索开关。", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                    }
                }
                skillProposal?.let { proposal -> SkillProposalDialog(proposal, vm, onDismiss = { skillProposal = null }, onSaved = { skillProposal = null; route = "skills" }) }
                voice?.let { original -> VoiceInputSheet(onDismiss = { voice = null }, insert = { text -> vm.insertVoice(original, text) }) }
                reading?.let { (title, text) ->
                    ModalBottomSheet(onDismissRequest = { reading = null }) {
                        Column(Modifier.fillMaxWidth().heightIn(max = (availableHeight - 48.dp).coerceAtLeast(120.dp))) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                ActionIcon("关闭阅读面板", { reading = null }, Icons.Outlined.Close)
                            }
                            val clipboard = LocalClipboardManager.current
                            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("复制原文") }
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(text, Modifier.heightIn(max = (availableHeight - 180.dp).coerceAtLeast(100.dp)).verticalScroll(rememberScrollState()).padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                val c = state.selected?.conversation
                if (dialog == "new") ConfigDialog(vm, c, onDismiss = { dialog = null }, onApply = { config -> vm.enqueue { actions.create(config) }; route = "conversation"; dialog = null })
                if (c != null) when (dialog) {
                    "rename" -> TextEditDialog("重命名", c.title, { dialog = null }) { value -> vm.enqueue { vm.report(actions.rename(c.id, value)) }; dialog = null }
                    "project" -> TextEditDialog("添加到项目（留空移出分组）", c.project.orEmpty(), { dialog = null }) { value -> vm.enqueue { actions.project(c.id, value) }; dialog = null }
                    "delete" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("删除对话？") }, text = { Text("对话将移入最近删除，可在设置中恢复；工作区文件不会删除。") },
                        confirmButton = { TextButton(onClick = { vm.enqueue { vm.report(actions.delete(c.id, true)) }; dialog = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
                    "attachments" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("对话附件") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text("已发送附件")
                        val sent = full.turns.filter { it.execution != null }.flatMap { it.attachments }
                        if (sent.isEmpty()) Text("无") else AttachmentList(sent.distinct(), c.config.workspace, vm)
                        Text("本轮草稿附件")
                        if (c.draft.attachments.isEmpty()) Text("无") else AttachmentList(c.draft.attachments, c.config.workspace, vm)
                    } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } }) }
                    "find" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> FindDialog(full, onDismiss = { dialog = null }, onSelect = { hit -> vm.jumpTo(c.id, hit); dialog = null }) }
                    "share" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> ShareDialog(full, hostActions.share, onDismiss = { dialog = null }) }
                    "shortcut" -> { LaunchedEffect(c.id) { hostActions.shortcut(c.id.value, c.title); dialog = null } }
                }
            }
        }
    }
}

@Composable private fun ConversationDrawer(state: InteractionState, vm: ConversationViewModel, onSelect: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("mobby", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                ActionIcon("关闭会话抽屉", onClose, Icons.Outlined.Close)
            }
            TextButton(onClick = onNew, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("新对话") }
            val visible = state.conversations.filter { !it.conversation.archived && !it.conversation.deleted && it.conversation.title.contains(query, true) }
            LazyColumn(Modifier.weight(1f)) {
                val groups = visible.groupBy { if (it.conversation.pinned) "置顶" else it.conversation.project?.let { name -> "项目 · $name" } ?: "历史会话" }
                groups.entries.sortedBy { if (it.key == "置顶") "" else it.key }.forEach { (group, entries) ->
                    item(key = "group:$group") { Text(group, Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium) }
                    items(entries, key = { it.conversation.id.value }) { item ->
                        Surface(color = if (state.selected?.conversation?.id == item.conversation.id) MaterialTheme.colorScheme.surface else Color.Transparent, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().clickable { onSelect(item.conversation) }.padding(12.dp)) {
                                Text(item.conversation.title, maxLines = 2)
                                Text("${item.conversation.config.agent.label()} · ${if (item.phase == null) "空对话" else item.phase.label()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (visible.isEmpty()) item { Text("没有匹配的会话", Modifier.padding(12.dp)) }
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索会话") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onSettings, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Settings, null); Text("设置与运行环境") }
        }
    }
}

@Composable private fun ConversationToolbar(c: Conversation?, vm: ConversationViewModel, onMenu: () -> Unit, onNew: () -> Unit, onMore: (String) -> Unit) {
    var config by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon("打开会话抽屉", onMenu, Icons.Outlined.Menu)
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { config = true; vm.enqueue { vm.refresh() } }) { Text(c?.config?.agent?.label() ?: "选择 Agent"); Icon(Icons.Outlined.ExpandMore, null) }
                if (c != null) AgentConfigMenu(config, { config = false }, c, vm)
            }
            ActionIcon("新建对话", onNew, Icons.Outlined.Edit)
            Box {
                ActionIcon("更多会话操作", { more = true }, Icons.Outlined.MoreHoriz, c != null)
                DropdownMenu(more, { more = false }) {
                    if (c != null) {
                        Text(c.title, Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium)
                        listOf("share" to "分享消息", "pin" to if (c.pinned) "取消置顶" else "置顶", "project" to "添加到项目", "attachments" to "对话附件", "find" to "在聊天中查找", "shortcut" to "添加到主屏幕", "rename" to "重命名", "archive" to "归档", "delete" to "删除").forEach { (action, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                more = false
                                when (action) { "pin" -> vm.enqueue { vm.actions.pin(c.id) }; "archive" -> vm.enqueue { vm.report(vm.actions.archive(c.id, true)) }; else -> onMore(action) }
                            })
                        }
                    }
                }
            }
        }
        if (c != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(c.title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelSmall)
            Text(c.config.model.ifBlank { "未配置模型" }, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable private fun Composer(detail: ConversationDetail, state: InteractionState, system: SystemStatus, vm: ConversationViewModel, onAdd: () -> Unit, onVoice: () -> Unit) {
    val composer by vm.composer.collectAsStateWithLifecycle()
    val active = detail.turns.lastOrNull { it.occupied }
    val unavailable = detail.conversation.archived || detail.conversation.deleted
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        if (unavailable) Text("此对话已归档或删除，请先在设置中恢复", style = MaterialTheme.typography.bodySmall)
        if (state.occupied != null && active == null) Text("${state.occupied!!.conversation.title} 正在执行，本轮草稿可继续编辑", style = MaterialTheme.typography.bodySmall)
        if (system.diagnosticBusy) Text("Shell 诊断正在占用运行环境", style = MaterialTheme.typography.bodySmall)
        if (detail.conversation.creator != null) Text("Skill Creator 已绑定此创建会话", style = MaterialTheme.typography.labelSmall)
        if (detail.conversation.draft.capabilities.any { it != detail.conversation.creator }) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            detail.conversation.draft.capabilities.filter { it != detail.conversation.creator }.forEach { ref -> InputChip(selected = true,
                onClick = { vm.enqueue { vm.actions.removeSkill(detail.conversation.id, ref) } }, label = { Text("${ref.split(':').getOrNull(3) ?: "技能"} ×") }) }
        }
        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            AttachmentList(detail.conversation.draft.attachments, detail.conversation.config.workspace, vm) { ref -> vm.enqueue { vm.actions.removeAttachment(detail.conversation.id, ref) } }
        }
        detail.conversation.draft.pendingAttachment?.let { pending ->
            Text(pending.error ?: "正在导入附件，完成后可发送…", style = MaterialTheme.typography.bodySmall)
            if (pending.error != null) Row {
                TextButton(onClick = { vm.importAttachment(detail.conversation.id, pending.workspace, pending.location) }) { Text("重试") }
                TextButton(onClick = { vm.enqueue { vm.actions.discardAttachment(detail.conversation.id, pending.id) } }) { Text("移除待处理附件") }
            }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom) {
                ActionIcon("添加内容与能力", onAdd, Icons.Outlined.Add)
                androidx.compose.foundation.text.BasicTextField(composer.value, vm::edit, Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp),
                    enabled = !unavailable, maxLines = 5, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner -> Box { if (composer.value.text.isEmpty()) Text("输入任务", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                if (active?.execution != null) ActionIcon("停止当前任务", { vm.stop(active.execution!!) }, Icons.Outlined.Stop, active.phase != ExecutionPhase.CANCELLING)
                else if (composer.value.text.isEmpty() && detail.conversation.draft.attachments.isEmpty()) ActionIcon("语音输入", onVoice, Icons.Outlined.Mic, !unavailable)
                else ActionIcon("发送任务", vm::send, Icons.Outlined.ArrowUpward,
                    !unavailable && system.ready && system.connected && !system.diagnosticBusy && state.occupied == null && detail.conversation.draft.pendingAttachment == null && (composer.value.text.isNotBlank() || detail.conversation.draft.attachments.isNotEmpty()))
            }
        }
        if (active?.pending == true) TextButton(onClick = { vm.enqueue { vm.actions.reconcile(detail.conversation.id) } }) { Text("查询待确认请求") }
        Text("在本机执行 · 请核对输出", Modifier.fillMaxWidth().padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(FlowPreview::class)
@Composable private fun Timeline(detail: ConversationDetail, vm: ConversationViewModel, modifier: Modifier, read: (String, String) -> Unit, hostActions: InteractionHostActions, proposal: (SkillProposal) -> Unit) {
    val keys = buildList { if (detail.hasEarlier) add("earlier"); detail.turns.forEach { t -> add("user:${t.id.value}"); add("run:${t.id.value}"); t.messages.forEach { add("message:${t.id.value}:${it.id}") }; t.skillProposals.forEach { add("artifact:${t.id.value}:${it.ref}") }; if (t.creatingSkill && !t.occupied && t.skillProposals.isEmpty() && !t.proposalsLoading) add("creator:${t.id.value}"); if (t.failure != null || t.phase in listOf(ExecutionPhase.CANCELLED, ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN, ExecutionPhase.AWAITING_APPROVAL)) add("status:${t.id.value}") } }
    val initial = keys.indexOf(detail.conversation.anchor).coerceAtLeast(0)
    val list = rememberLazyListState(initial, detail.conversation.anchorOffset.coerceAtLeast(0))
    var follow by remember { mutableStateOf(detail.conversation.anchor == null) }
    val scope = rememberCoroutineScope()
    val target by vm.readingTarget.collectAsStateWithLifecycle()
    LaunchedEffect(target, keys) {
        val jump = target?.takeIf { it.conversation == detail.conversation.id } ?: return@LaunchedEffect
        val index = keys.indexOf(jump.key)
        if (index >= 0) {
            follow = false
            list.scrollToItem(index)
            vm.enqueue { vm.actions.anchor(jump.conversation, jump.key, 0) }
            vm.consumed(jump)
        }
    }
    DisposableEffect(list, detail.conversation.id) {
        val id = detail.conversation.id
        onDispose {
            val key = list.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString()
            val offset = list.firstVisibleItemScrollOffset
            if (key != null && key != "earlier") vm.enqueue { vm.actions.anchor(id, key, offset) }
        }
    }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.totalItemsCount > 0 && !list.isScrollInProgress && !list.canScrollForward }.collect { if (it) follow = true }
    }
    val outputVersion = detail.turns.map { listOf(it.id, it.phase, it.messages.map { m -> m.text.length }, it.steps.map { s -> s.output.length }) }
    LaunchedEffect(outputVersion) { if (follow && !list.isScrollInProgress && keys.isNotEmpty()) list.scrollToItem(keys.lastIndex) }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString() to list.firstVisibleItemScrollOffset }.debounce(250).collect { (key, offset) ->
            if (key != null && key != "earlier") vm.enqueue { vm.actions.anchor(detail.conversation.id, key, offset) }
        }
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (detail.hasEarlier) item(key = "earlier") {
                TextButton(onClick = { follow = false; vm.enqueue { vm.actions.loadEarlier(detail.conversation.id) } }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") }
            }
            if (detail.turns.isEmpty()) item(key = "empty") { Column(Modifier.fillParentMaxHeight().padding(top = 80.dp)) { Text("从一个任务开始", style = MaterialTheme.typography.headlineMedium); Text("${detail.conversation.config.agent.label()} 将在本机工作区执行。", Modifier.padding(top = 12.dp)) } }
            detail.turns.forEach { turn ->
                item(key = "user:${turn.id.value}") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Surface(shape = RoundedCornerShape(21.dp, 21.dp, 6.dp, 21.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 360.dp)) { Column(Modifier.padding(16.dp)) { androidx.compose.foundation.text.selection.SelectionContainer { Text(turn.userText) }; AttachmentList(turn.attachments, detail.conversation.config.workspace, vm) } } } }
                item(key = "run:${turn.id.value}") { ExecutionCard(turn, vm, read) }
                turn.messages.forEach { message -> item(key = "message:${turn.id.value}:${message.id}") {
                    Column {
                        ReplyContent(message.text, read)
                        Row {
                            val clipboard = LocalClipboardManager.current
                            ActionIcon("复制回复", { clipboard.setText(AnnotatedString(message.text)) }, Icons.Outlined.ContentCopy)
                            ActionIcon("分享回复", { hostActions.share(message.text) }, Icons.Outlined.Share)
                        }
                    }
                } }
                turn.skillProposals.forEach { candidate -> item(key = "artifact:${turn.id.value}:${candidate.ref}") {
                    OutlinedCard(onClick = { proposal(candidate) }) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("生成的技能草稿", style = MaterialTheme.typography.titleMedium)
                        Text("预览、校验并保存到 ${candidate.agent.label()}")
                    } }
                } }
                if (turn.creatingSkill && !turn.occupied && turn.skillProposals.isEmpty() && !turn.proposalsLoading) item(key = "creator:${turn.id.value}") {
                    Text("本轮没有可保存的技能草稿。请根据回复继续补充需求；执行完成不表示技能已添加。", style = MaterialTheme.typography.bodySmall)
                }
                if ("status:${turn.id.value}" in keys) item(key = "status:${turn.id.value}") {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(turn.failure ?: turn.phase.label())
                            if (!turn.occupied) TextButton(onClick = { vm.enqueue { vm.actions.restoreDraft(detail.conversation.id, turn) } }) { Text("放入草稿重试") }
                        }
                    }
                }
            }
        }
        if (!follow && detail.turns.isNotEmpty()) FilledTonalButton(onClick = { follow = true; scope.launch { if (keys.isNotEmpty()) list.animateScrollToItem(keys.lastIndex) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text("最新消息") }
    }
}
