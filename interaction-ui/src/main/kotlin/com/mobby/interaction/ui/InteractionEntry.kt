package com.mobby.interaction.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
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
@Composable fun InteractionEntry(actions: InteractionUseCases, hostActions: InteractionHostActions, conversationNavigation: String? = null) {
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
    var toolbarAnchor by remember { mutableStateOf(IntRect.Zero) }
    val skillProposal by vm.skillProposal.collectAsStateWithLifecycle()
    val skillProposalSaved by vm.skillProposalSaved.collectAsStateWithLifecycle()
    LaunchedEffect(skillProposalSaved?.operation) {
        skillProposalSaved?.let { route = "skills"; vm.consumeSkillProposalSaved(it.operation) }
    }
    var reading by remember { mutableStateOf<Pair<String, String>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var consumedNavigation by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(conversationNavigation) {
        if (conversationNavigation != null && conversationNavigation != consumedNavigation) {
            keyboard?.hide()
            focus.clearFocus()
            drawer = false
            dialog = null
            reading = null
            route = "conversation"
            consumedNavigation = conversationNavigation
        }
    }
    val dark = appearance == Appearance.DARK || appearance == Appearance.SYSTEM && isSystemInDarkTheme()
    LaunchedEffect(dark) { hostActions.appearance(dark) }
    val colors = if (dark) MobbyDarkScheme else MobbyLightScheme
    LaunchedEffect(vm) { for (message in vm.feedback) snackbar.showSnackbar(message) }
    fun navigate(next: String) { keyboard?.hide(); focus.clearFocus(); drawer = false; route = next }
    BackHandler(route != "conversation") { route = when (route) { "gateway", "history-limits", "diagnostic", "archived" -> "settings"; "skills", "plugins" -> "add"; else -> "conversation" } }
    MaterialTheme(colorScheme = colors) {
        val camera = rememberCameraCapture(actions, { captured ->
            actions.importAttachment(ConversationId(captured.conversation), captured.workspace, requireNotNull(captured.attachmentUri))
        }, { message -> vm.report(OperationResult.Failed(message)) })
        Surface(Modifier.fillMaxSize()) {
            InteractionViewport {
                val fullWidth = maxWidth
                val availableHeight = maxHeight
                val drawerWidth = minOf(360.dp, (fullWidth - 56.dp).coerceAtLeast(0.dp))
                val motion = rememberDrawerMotion(drawer) { open ->
                    if (open) { keyboard?.hide(); focus.clearFocus() }
                    drawer = open
                }
                val progress by motion
                val pixels = with(LocalDensity.current) { drawerWidth.toPx() }
                motion.width = pixels
                val swipe = Modifier.drawerSwipe(motion, with(LocalDensity.current) { DrawerSwipeEdge.toPx() })
                if (drawer || progress > 0f) ConversationDrawer(state, vm, onSelect = { c -> vm.enqueue { actions.select(c.id) }; drawer = false },
                    onNew = { drawer = false; dialog = "new" }, onSettings = { navigate("settings") }, onProjects = { navigate("projects") }, onClose = { drawer = false },
                    modifier = Modifier.width(drawerWidth).fillMaxHeight().offset { IntOffset(((progress - 1f) * pixels).roundToInt(), 0) }.then(swipe))
                Surface(Modifier.requiredWidth(fullWidth).fillMaxHeight().offset { IntOffset((pixels * progress).roundToInt(), 0) }.then(swipe)
                    .then(if (drawer) Modifier.clearAndSetSemantics {} else Modifier)) {
                    when (route) {
                        "projects" -> ProjectPage(vm) { route = "conversation" }
                        "settings" -> SettingsPage(system, appearance, { value -> vm.enqueue { vm.report(actions.setAppearance(value)) } }, { navigate(it) }, { route = "conversation" }, vm)
                        "gateway" -> GatewayPage(vm) { route = "settings" }
                        "history-limits" -> EventHistoryPage(actions::eventHistoryLimits, actions::saveEventHistoryLimits) { route = "settings" }
                        "diagnostic" -> DiagnosticPage(vm) { route = "settings" }
                        "archived" -> ArchivedPage(state, vm) { route = "settings" }
                        "skills" -> SkillsPage(vm, onBack = { route = "add" }, onConversation = { route = "conversation" })
                        "plugins" -> PluginPage(vm) { route = "add" }
                        else -> Box(Modifier.fillMaxSize()) {
                            when {
                                state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
                                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                                state.selected == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        EmptyPlaceholder("还没有对话", "新建一个对话，从具体任务开始")
                                        Button(onClick = { dialog = "new" }) { Text("新建对话") }
                                    }
                                }
                                else -> {
                                    val detail = state.selected!!
                                    key(detail.conversation.id) {
                                        Timeline(
                                            detail, vm, Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 88.dp, bottom = 148.dp),
                                            followPadding = PaddingValues(end = 16.dp, bottom = 148.dp),
                                            read = { title, text -> reading = title to text }, hostActions = hostActions, proposal = vm::openSkillProposal,
                                        )
                                    }
                                }
                            }
                            ConversationToolbar(
                                state.selected?.conversation, vm,
                                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                                onMenu = { keyboard?.hide(); focus.clearFocus(); drawer = true },
                                onNew = { dialog = "new" }, onMore = { dialog = it },
                                onAnchor = { toolbarAnchor = it },
                            )
                            if (!system.connected || !system.ready) Text(
                                system.message,
                                Modifier.align(Alignment.TopCenter).padding(top = 68.dp, start = 20.dp, end = 20.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (state.selected != null && state.error == null && !state.loading) {
                                Composer(state.selected!!, state, system, vm, modifier = Modifier.align(Alignment.BottomCenter), onAdd = { navigate("add") })
                            }
                        }
                    }
                }
                if (drawer) Box(Modifier.offset { IntOffset((pixels * progress).roundToInt(), 0) }.fillMaxSize().then(swipe).clickable { drawer = false })
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                if (route == "add") ModalBottomSheet(onDismissRequest = { route = "conversation" }, shape = RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp), containerColor = raisedColor()) {
                    val target = state.selected?.conversation
                    val canImport = target != null && !target.archived && !target.deleted && target.draft.pendingAttachment == null && target.draft.attachments.size < 4
                    val images = canImport && !camera.busy && agentOptions.any { it.agent == target?.config?.agent && it.images && it.unavailable == null }
                    val files = target != null && !target.archived && !target.deleted && target.draft.pendingAttachment == null && target.draft.attachments.size < 4 && agentOptions.any { it.agent == target.config.agent && it.resources && it.unavailable == null }
                    Column(Modifier.heightIn(max = (availableHeight - 48.dp).coerceAtLeast(120.dp)).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AttachmentTile("拍照", AppIcons.Camera, images, Modifier.weight(1f)) { if (target != null) { navigate("conversation"); camera.start(target) } }
                            AttachmentTile("照片", AppIcons.Photo, images, Modifier.weight(1f)) {
                                if (target != null) {
                                    fileTarget = target.id.value; fileWorkspace = target.config.workspace; route = "conversation"
                                    photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            }
                            AttachmentTile("本地文件", AppIcons.Upload, files, Modifier.weight(1f)) {
                                if (target != null) { fileTarget = target.id.value; fileWorkspace = target.config.workspace; route = "conversation"; filePicker.launch(arrayOf("*/*")) }
                            }
                        }
                        Text("文本 32 KiB、图片 2 MiB，每轮最多 4 个；PDF 暂不可用。", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CapabilityRow("插件", "接入本机能力，扩展任务范围", AppIcons.Plugin) { route = "plugins" }
                        CapabilityRow("技能", "复用专业能力，处理特定任务", AppIcons.Skill) { route = "skills" }
                        CapabilityRow("联网搜索", "由 Agent 当前可用工具决定", AppIcons.Globe, value = "自动", enabled = false) {}
                        Spacer(Modifier.height(16.dp))
                    }
                }
                skillProposal?.let { editor -> SkillProposalDialog(editor.proposal, vm, sourceAvailable = state.selected?.turns?.any { turn -> turn.skillProposals.any { it.ref == editor.proposal.ref } } == true, onDismiss = vm::dismissSkillProposal) }
                reading?.let { (title, text) ->
                    ModalBottomSheet(onDismissRequest = { reading = null }, containerColor = raisedColor()) {
                        Column(Modifier.fillMaxWidth().heightIn(max = (availableHeight - 48.dp).coerceAtLeast(120.dp))) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                ActionIcon("关闭阅读面板", { reading = null }, AppIcons.Close)
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
                if (dialog == "new") ConfigDialog(vm, c, onDismiss = { dialog = null }, onApply = { config, project -> vm.enqueue { actions.create(config, project) }; route = "conversation"; dialog = null }, anchor = toolbarAnchor)
                if (c != null) when (dialog) {
                    "rename" -> TextEditDialog("重命名", c.title, { dialog = null }) { value -> vm.enqueue { vm.report(actions.rename(c.id, value)) }; dialog = null }
                    "project" -> ProjectGroupDialog(c, state.projects, { dialog = null }) { project -> vm.enqueue { actions.project(c.id, project) }; dialog = null }
                    "delete" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text("删除对话？") }, text = { Text("对话将移入最近删除，可在设置中恢复；工作区文件不会删除。") },
                        confirmButton = { TextButton(onClick = { vm.enqueue { vm.report(actions.delete(c.id, true)) }; dialog = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
                    "attachments" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text("对话附件") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text("已发送附件")
                        val sent = full.turns.filter { it.execution != null }.flatMap { it.attachments }
                        if (sent.isEmpty()) EmptyPlaceholder("没有已发送附件") else AttachmentList(sent.distinct(), c.config.workspace, vm)
                        Text("本轮草稿附件")
                        if (c.draft.attachments.isEmpty()) EmptyPlaceholder("没有草稿附件") else AttachmentList(c.draft.attachments, c.config.workspace, vm)
                    } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } }) }
                    "find" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> FindDialog(full, onDismiss = { dialog = null }, onSelect = { hit -> vm.jumpTo(c.id, hit); dialog = null }) }
                    "share" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> ShareDialog(full, hostActions.share, onDismiss = { dialog = null }) }
                    "shortcut" -> { LaunchedEffect(c.id) { hostActions.shortcut(c.id.value, c.title); dialog = null } }
                }
            }
        }
    }
}

@Composable private fun ConversationDrawer(state: InteractionState, vm: ConversationViewModel, onSelect: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onProjects: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    Surface(modifier, color = cardColor()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("mobby", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                ActionIcon("关闭会话抽屉", onClose, AppIcons.Close)
            }
            DrawerRow("新对话", AppIcons.New, onNew)
            DrawerRow("项目管理", AppIcons.Folder, onProjects)
            val visible = state.conversations.filter { !it.conversation.archived && !it.conversation.deleted && it.conversation.title.contains(query, true) }
            LazyColumn(Modifier.weight(1f)) {
                val groups = visible.groupBy { if (it.conversation.pinned) "置顶" else it.conversation.project?.let { name -> "项目 · $name" } ?: "历史会话" }
                groups.entries.sortedBy { if (it.key == "置顶") "" else it.key }.forEach { (group, entries) ->
                    item(key = "group:$group") { Text(group, Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium) }
                    items(entries, key = { it.conversation.id.value }) { item ->
                        Surface(color = if (state.selected?.conversation?.id == item.conversation.id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().clickable { onSelect(item.conversation) }.padding(12.dp)) {
                                Text(item.conversation.title, maxLines = 2)
                                Text("${item.conversation.config.agent.label()} · ${if (item.phase == null) "空对话" else item.phase.label()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (visible.isEmpty()) item { EmptyPlaceholder("没有匹配的会话", "换个关键词，或新建一个对话") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索会话") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent))
            DrawerRow("设置与运行环境", AppIcons.Settings, onSettings)
        }
    }
}

@Composable private fun ConversationToolbar(c: Conversation?, vm: ConversationViewModel, onMenu: () -> Unit, onNew: () -> Unit, onMore: (String) -> Unit, onAnchor: (IntRect) -> Unit = {}, modifier: Modifier = Modifier) {
    var config by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf(IntRect.Zero) }
    var actions by remember { mutableStateOf(IntRect.Zero) }
    Row(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = cardColor(), shadowElevation = floatingElevation(), tonalElevation = 0.dp) { ActionIcon("打开会话抽屉", onMenu, AppIcons.Menu) }
            Box(Modifier.padding(horizontal = 6.dp).onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                chip = IntRect(origin.x.roundToInt(), origin.y.roundToInt(), origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height)
            }) {
                Surface(shape = RoundedCornerShape(26.dp), color = cardColor(), shadowElevation = floatingElevation(), tonalElevation = 0.dp) {
                    TextButton(onClick = { config = true; vm.enqueue { vm.refresh() } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(c?.config?.agent?.label() ?: "选择 Agent"); Icon(AppIcons.ChevronDown, null, Modifier.size(16.dp)) }
                }
                if (c != null) AgentConfigMenu(config, { config = false }, c, vm, chip)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                val rect = IntRect(origin.x.roundToInt(), origin.y.roundToInt(), origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height)
                actions = rect
                onAnchor(rect)
            }) {
                Surface(shape = RoundedCornerShape(26.dp), color = cardColor(), shadowElevation = floatingElevation(), tonalElevation = 0.dp) {
                    Row {
                        ActionIcon("新建对话", onNew, AppIcons.New)
                        ActionIcon("更多会话操作", { more = true }, AppIcons.More, c != null)
                    }
                }
                if (c != null) FrostedMenu(more, { more = false }, actions) {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        MenuCaption(c.title)
                        listOf(
                            Triple("share", "分享", AppIcons.Share),
                            Triple("pin", if (c.pinned) "取消置顶" else "置顶", AppIcons.Pin),
                            Triple("project", "添加到项目", AppIcons.Folder),
                            Triple("attachments", "对话附件", AppIcons.File),
                            Triple("find", "在聊天中查找", AppIcons.Search),
                            Triple("shortcut", "添加到主屏幕", AppIcons.New),
                            Triple("rename", "重命名", AppIcons.Edit),
                            Triple("archive", "归档", AppIcons.Folder),
                            Triple("delete", "删除", AppIcons.Trash),
                        ).forEach { (action, label, icon) ->
                            MenuAction(label, danger = action == "delete", icon = icon) {
                                more = false
                                when (action) { "pin" -> vm.enqueue { vm.actions.pin(c.id) }; "archive" -> vm.enqueue { vm.report(vm.actions.archive(c.id, true)) }; else -> onMore(action) }
                            }
                        }
                    }
                }
            }
        }
}

@Composable private fun Composer(detail: ConversationDetail, state: InteractionState, system: SystemStatus, vm: ConversationViewModel, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val composer by vm.composer.collectAsStateWithLifecycle()
    val active = detail.turns.lastOrNull { it.occupied }
    val unavailable = detail.conversation.archived || detail.conversation.deleted
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val capture = rememberVoiceCapture()
    val snapshot = remember { mutableStateOf<ComposerState?>(null) }
    var voiceMode by rememberSaveable(detail.conversation.id.value) { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var cancelArmed by remember { mutableStateOf(false) }
    var hint by remember(detail.conversation.id.value) { mutableStateOf<String?>(null) }
    var openKeyboard by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val allowed = granted || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!allowed) capture.error = "麦克风权限未授予，请使用文字输入或重试授权"
        else if (capture.error == "麦克风权限未授予，请使用文字输入或重试授权") capture.error = null
    }
    SideEffect {
        capture.onTranscript = transcript@{ text ->
            val original = snapshot.value ?: return@transcript
            snapshot.value = null
            val problem = vm.sendVoice(original, text)
            if (problem != null) capture.error = problem else voiceMode = false
        }
    }
    DisposableEffect(detail.conversation.id) {
        onDispose {
            holding = false
            cancelArmed = false
            snapshot.value = null
            capture.cancel()
        }
    }
    LaunchedEffect(capture.phase) {
        if (holding && capture.phase == "idle") {
            holding = false
            cancelArmed = false
            snapshot.value = null
        }
    }
    LaunchedEffect(openKeyboard, voiceMode) {
        if (openKeyboard && !voiceMode) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
            openKeyboard = false
        }
    }
    fun beginHold() {
        if (unavailable) return
        hint = null
        capture.error = null
        cancelArmed = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        snapshot.value = vm.composer.value
        if (capture.start()) {
            holding = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            keyboard?.hide()
            focus.clearFocus()
        } else snapshot.value = null
    }
    val stop = active?.execution != null
    val micAvailable = !stop && composer.value.text.isEmpty() && detail.conversation.draft.attachments.isEmpty()
    LaunchedEffect(detail.conversation.id) { capture.error = null }
    LaunchedEffect(micAvailable, stop) { if (!micAvailable || stop) voiceMode = false }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            if (unavailable) Text("此对话已归档或删除，请先在设置中恢复", style = MaterialTheme.typography.bodySmall)
            if (state.occupied != null && active == null) Text("${state.occupied!!.conversation.title} 正在执行，本轮草稿可继续编辑", style = MaterialTheme.typography.bodySmall)
            if (system.diagnosticBusy) Text("Shell 诊断正在占用运行环境", style = MaterialTheme.typography.bodySmall)
            if (detail.conversation.creator != null) Text("Skill Creator 已绑定此创建会话", style = MaterialTheme.typography.labelSmall)
            if (detail.conversation.draft.capabilities.any { it != detail.conversation.creator }) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                detail.conversation.draft.capabilities.filter { it != detail.conversation.creator }.forEach { ref -> InputChip(selected = true,
                    onClick = { vm.enqueue { vm.actions.removeSkill(detail.conversation.id, ref) } }, label = { Text("${capabilityLabel(ref)} ×") }) }
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
            capture.transfer?.let { VoiceModelProgress(it, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) }
            val notice = capture.error ?: hint
            if (notice != null) Text(notice, color = if (capture.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (capture.phase == "transcribing" && !holding) Text("正在转写…", style = MaterialTheme.typography.bodySmall)
        }
        VoiceComposerBar(
            voiceMode = voiceMode,
            recording = holding && capture.phase == "recording",
            cancelArmed = cancelArmed,
            level = capture.level,
            enabled = !unavailable,
            micAvailable = micAvailable,
            stop = stop,
            stopEnabled = active?.phase != ExecutionPhase.CANCELLING,
            sendEnabled = !unavailable && system.ready && system.connected && !system.diagnosticBusy && state.occupied == null && detail.conversation.draft.pendingAttachment == null && (composer.value.text.isNotBlank() || detail.conversation.draft.attachments.isNotEmpty()),
            onAdd = onAdd,
            onStop = { active?.execution?.let(vm::stop) },
            onSend = vm::send,
            onEnterVoice = {
                hint = null
                capture.error = null
                voiceMode = true
                keyboard?.hide()
                focus.clearFocus()
            },
            onExitVoice = { hint = null; voiceMode = false; openKeyboard = true },
            onHoldTap = { hint = "请按住说话" },
            onHoldStart = ::beginHold,
            onHoldMove = { cancelArmed = it },
            onHoldEnd = { cancelled ->
                val activeHold = holding
                holding = false
                cancelArmed = false
                if (activeHold) {
                    if (cancelled) {
                        snapshot.value = null
                        capture.cancel()
                    } else capture.finish()
                }
            },
            textField = {
                androidx.compose.foundation.text.BasicTextField(
                    composer.value, vm::edit,
                    Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp).focusRequester(focusRequester),
                    enabled = !unavailable, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner -> Box { if (composer.value.text.isEmpty()) Text("描述任务，或添加上下文", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
                )
            },
        )
        if (active?.pending == true) TextButton(onClick = { vm.enqueue { vm.actions.reconcile(detail.conversation.id) } }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("查询待确认请求") }
    }
}

@OptIn(FlowPreview::class)
@Composable private fun Timeline(detail: ConversationDetail, vm: ConversationViewModel, modifier: Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp), followPadding: PaddingValues = PaddingValues(12.dp), read: (String, String) -> Unit, hostActions: InteractionHostActions, proposal: (SkillProposal) -> Unit) {
    val keys = buildList {
        if (detail.hasEarlier) add("earlier")
        detail.turns.forEach { t ->
            add("user:${t.id.value}")
            t.transcript().forEach { entry ->
                when (entry) {
                    is TranscriptEntry.Reply -> add("message:${t.id.value}:${entry.message.id}")
                    is TranscriptEntry.ToolRun -> add("tools:${t.id.value}:${entry.steps.first().id}")
                }
            }
            t.permissions.forEach { add("permission:${t.id.value}:${it.id}:${it.revision}") }
            t.skillProposals.forEach { add("artifact:${t.id.value}:${it.ref}") }
            if (t.creatingSkill && !t.occupied && t.skillProposals.isEmpty() && !t.proposalsLoading) add("creator:${t.id.value}")
            if (t.failure != null || t.phase in listOf(ExecutionPhase.CANCELLED, ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN, ExecutionPhase.AWAITING_APPROVAL)) add("status:${t.id.value}")
        }
    }
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
    val outputVersion = detail.turns.map { listOf(it.id, it.phase, it.permissions.map { p -> p.id to p.revision }, it.messages.map { m -> m.text.length }, it.steps.map { s -> s.output.length }) }
    LaunchedEffect(outputVersion) { if (follow && !list.isScrollInProgress && keys.isNotEmpty()) list.scrollToItem(keys.lastIndex) }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString() to list.firstVisibleItemScrollOffset }.debounce(250).collect { (key, offset) ->
            if (key != null && key != "earlier") vm.enqueue { vm.actions.anchor(detail.conversation.id, key, offset) }
        }
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (detail.hasEarlier) item(key = "earlier") {
                TextButton(onClick = { follow = false; vm.enqueue { vm.actions.loadEarlier(detail.conversation.id) } }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") }
            }
            if (detail.turns.isEmpty()) item(key = "empty") {
                Column(Modifier.fillParentMaxWidth().padding(top = 32.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("今天，做点什么？", style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("使用 ${detail.conversation.config.agent.label()}，从一个具体任务开始。", Modifier.padding(top = 12.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            detail.turns.forEach { turn ->
                val entries = turn.transcript()
                val lastReply = entries.filterIsInstance<TranscriptEntry.Reply>().lastOrNull()?.message?.id
                val lastTools = entries.filterIsInstance<TranscriptEntry.ToolRun>().lastOrNull()?.steps?.firstOrNull()?.id
                item(key = "user:${turn.id.value}") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Surface(shape = RoundedCornerShape(21.dp, 21.dp, 6.dp, 21.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 360.dp)) { Column(Modifier.padding(16.dp)) { androidx.compose.foundation.text.selection.SelectionContainer { Text(turn.userText) }; AttachmentList(turn.attachments, detail.conversation.config.workspace, vm) } } } }
                entries.forEach { entry ->
                    when (entry) {
                        is TranscriptEntry.ToolRun -> item(key = "tools:${turn.id.value}:${entry.steps.first().id}") {
                            ExecutionCard(turn, vm, entry.steps, showExtras = entry.steps.first().id == lastTools, read)
                        }
                        is TranscriptEntry.Reply -> item(key = "message:${turn.id.value}:${entry.message.id}") {
                            Column {
                                ReplyContent(entry.message.text, streaming = turn.occupied && entry.message.id == lastReply, read = read)
                                if (entry.message.id == lastReply) {
                                    val reply = turn.messages.joinToString("\n") { it.text }
                                    Row {
                                        val clipboard = LocalClipboardManager.current
                                        ActionIcon("复制回复", { clipboard.setText(AnnotatedString(reply)) }, AppIcons.Copy)
                                        ActionIcon("分享回复", { hostActions.share(reply) }, AppIcons.Share)
                                    }
                                }
                            }
                        }
                    }
                }
                turn.permissions.forEach { permission -> item(key = "permission:${turn.id.value}:${permission.id}:${permission.revision}") {
                    PermissionCard(turn, permission, detail.conversation.config.agent, vm)
                } }
                turn.skillProposals.forEach { candidate -> item(key = "artifact:${turn.id.value}:${candidate.ref}") {
                    OutlinedCard(onClick = { proposal(candidate) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.outlinedCardColors(containerColor = raisedColor()), border = BorderStroke(0.dp, Color.Transparent)) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
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
        if (!follow && detail.turns.isNotEmpty()) FilledTonalButton(onClick = { follow = true; scope.launch { if (keys.isNotEmpty()) list.animateScrollToItem(keys.lastIndex) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(followPadding), shape = RoundedCornerShape(26.dp)) { Icon(AppIcons.ArrowDown, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("最新消息") }
    }
}

internal fun capabilityLabel(ref: String) = when {
    ref.startsWith("plugin:PHONE") -> "使用当前手机"
    else -> ref.split(':').getOrNull(3) ?: "技能"
}

@Composable internal fun InteractionViewport(content: @Composable BoxWithConstraintsScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().clipToBounds(), content = content)
}

// Wide enough that a swipe can start inside the app, past the system back gesture inset.
internal val DrawerSwipeEdge = 96.dp
private val DrawerFlingThreshold = 125.dp

/** Shared open fraction of the drawer and the conversation it pushes aside. */
@Stable internal class DrawerMotion internal constructor(open: Boolean) : State<Float> {
    private val position = Animatable(if (open) 1f else 0f)
    internal var width = 0f
    internal var flingThreshold = 0f
    internal var commit: (Boolean) -> Unit = {}
    internal var gesturing by mutableStateOf(false)
        private set
    internal var gestures by mutableStateOf(0)
        private set
    override val value: Float get() = position.value

    internal fun begin() { gesturing = true }
    // A start and end can occur before a frame observes gesturing=true.
    internal fun end() { gesturing = false; gestures++ }
    internal suspend fun follow(fraction: Float) = position.snapTo(fraction.coerceIn(0f, 1f))
    internal suspend fun animateTo(open: Boolean) = position.animateTo(if (open) 1f else 0f, tween(240))
    internal suspend fun settle(velocity: Float) {
        val open = when {
            velocity >= flingThreshold -> true
            velocity <= -flingThreshold -> false
            else -> position.value >= 0.5f
        }
        commit(open)
        animateTo(open)
    }
}

@Composable internal fun rememberDrawerMotion(open: Boolean, onChange: (Boolean) -> Unit): DrawerMotion {
    val motion = remember { DrawerMotion(open) }
    motion.commit = onChange
    motion.flingThreshold = with(LocalDensity.current) { DrawerFlingThreshold.toPx() }
    LaunchedEffect(open, motion.gesturing, motion.gestures) {
        if (!motion.gesturing) motion.animateTo(open)
    }
    PredictiveBackHandler(open) { events ->
        motion.begin()
        val start = motion.value
        try {
            events.collect { motion.follow(start * (1f - it.progress.coerceIn(0f, 1f))) }
            motion.commit(false)
        } finally {
            motion.end()
        }
    }
    return motion
}

/**
 * Drags the drawer with the finger: rightwards from the left [edge] band opens it, leftwards
 * closes it. Children keep priority, so scrollable content still wins its own drags.
 */
internal fun Modifier.drawerSwipe(motion: DrawerMotion, edge: Float): Modifier = pointerInput(motion, edge) {
    coroutineScope {
        while (isActive) {
            var overSlop = 0f
            val drag = awaitPointerEventScope {
                val down = awaitFirstDown(requireUnconsumed = false)
                val opening = motion.value <= 0f
                if (opening && down.position.x > edge) return@awaitPointerEventScope null
                awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop ->
                    if (if (opening) slop > 0f else slop < 0f) { overSlop = slop; change.consume() }
                }
            }
            if (drag == null || overSlop == 0f || motion.width <= 0f) continue
            val velocity = VelocityTracker()
            var travelled = (motion.value * motion.width + overSlop).coerceIn(0f, motion.width)
            motion.begin()
            try {
                launch { motion.follow(travelled / motion.width) }
                awaitPointerEventScope {
                    horizontalDrag(drag.id) { change ->
                        velocity.addPointerInputChange(change)
                        travelled = (travelled + change.positionChange().x).coerceIn(0f, motion.width)
                        val fraction = travelled / motion.width
                        launch { motion.follow(fraction) }
                        change.consume()
                    }
                }
                motion.settle(velocity.calculateVelocity().x)
            } finally {
                motion.end()
            }
        }
    }
}
