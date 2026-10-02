package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import com.github.ytlog.mobby.android.conversation.ui.gateway.GatewayPage

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlin.math.roundToInt

class ConversationHostActions(
    val share: (String) -> Unit, val appearance: (Boolean) -> Unit, val pet: (Boolean) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ConversationEntry(
    actions: ConversationUseCases, hostActions: ConversationHostActions, conversationNavigation: String? = null,
    petEnabled: Boolean = false, petPermitted: Boolean = false, gatewayRefresh: Int = 0,
) {
    val factory = remember(actions) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ConversationViewModel(actions) as T
    } }
    val vm: ConversationViewModel = viewModel(factory = factory)
    LaunchedEffect(gatewayRefresh) { if (gatewayRefresh > 0) vm.enqueue { vm.refresh() } }
    val state by vm.state.collectAsStateWithLifecycle()
    val system by vm.status.collectAsStateWithLifecycle()
    val agentOptions by vm.agents.collectAsStateWithLifecycle()
    val gateways by vm.gateways.collectAsStateWithLifecycle()
    val gatewaysLoaded by vm.gatewaysLoaded.collectAsStateWithLifecycle()
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
    var navigation by rememberSaveable(stateSaver = AppNavigationState.Saver) { mutableStateOf(AppNavigationState()) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var sidebarExpanded by rememberSaveable { mutableStateOf(true) }
    var conversationQuery by rememberSaveable { mutableStateOf("") }
    val conversationListScroll = rememberLazyListState()
    val appearance by actions.appearance.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var gatewayIntroSeen by rememberSaveable { mutableStateOf(false) }
    var gatewayStartAdding by rememberSaveable { mutableStateOf(false) }
    var gatewayFromIntro by rememberSaveable { mutableStateOf(false) }
    var toolbarAnchor by remember { mutableStateOf(IntRect.Zero) }
    val skillProposal by vm.skillProposal.collectAsStateWithLifecycle()
    val skillProposalSaved by vm.skillProposalSaved.collectAsStateWithLifecycle()
    LaunchedEffect(skillProposalSaved?.operation) {
        skillProposalSaved?.let { navigation = navigation.open(AppPage.SKILLS); vm.consumeSkillProposalSaved(it.operation) }
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
            navigation = navigation.open(AppPage.CONVERSATION)
            consumedNavigation = conversationNavigation
        }
    }
    LaunchedEffect(navigation.page, system.ready, gatewaysLoaded, gateways.isEmpty(), dialog) {
        if (!gatewayIntroSeen && navigation.page == AppPage.CONVERSATION && system.ready && gatewaysLoaded && gateways.isEmpty() && dialog == null) {
            gatewayIntroSeen = true
            dialog = "gateway-intro"
        }
    }
    val dark = appearance == Appearance.DARK || appearance == Appearance.SYSTEM && isSystemInDarkTheme()
    LaunchedEffect(dark) { hostActions.appearance(dark) }
    val colors = if (dark) MobbyDarkScheme else MobbyLightScheme
    val activity = LocalContext.current.findHostActivity()
    val windowInfo by produceState<WindowLayoutInfo?>(null, activity) {
        if (activity != null) WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { value = it }
    }
    var viewportOrigin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(vm) { for (message in vm.feedback) snackbar.showSnackbar(message) }
    fun navigate(next: AppPage) { keyboard?.hide(); focus.clearFocus(); drawer = false; navigation = navigation.open(next) }
    fun navigateProjects(from: AppPage) { keyboard?.hide(); focus.clearFocus(); drawer = false; navigation = navigation.openProjects(from) }
    fun showAddSheet() { keyboard?.hide(); focus.clearFocus(); drawer = false; navigation = navigation.openAddSheet() }
    BackHandler(navigation.page != AppPage.CONVERSATION || navigation.addSheetOpen) {
        navigation = navigation.back(gatewayFromIntro)
    }
    MaterialTheme(colorScheme = colors) {
        val selectPlugin = rememberDirectPluginSelector(vm, state.selected?.conversation)
        val camera = rememberCameraCapture(actions, { captured ->
            actions.importAttachment(ConversationId(captured.conversation), captured.workspace, requireNotNull(captured.attachmentUri))
        }, { message -> vm.report(OperationResult.Failed(message)) })
        val pageColor = if (navigation.page == AppPage.CONVERSATION || navigation.page == AppPage.PROJECT_DETAIL) conversationCanvas() else MaterialTheme.colorScheme.background
        Surface(Modifier.fillMaxSize(), color = pageColor) {
            ConversationViewport(onPosition = { viewportOrigin = it }) {
                val fullWidth = maxWidth
                val density = LocalDensity.current
                val fold = windowInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull {
                    it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
                }?.let { feature ->
                    with(density) {
                        FoldRegion(
                            (feature.bounds.left - viewportOrigin.x).toDp(),
                            (feature.bounds.top - viewportOrigin.y).toDp(),
                            (feature.bounds.right - viewportOrigin.x).toDp(),
                            (feature.bounds.bottom - viewportOrigin.y).toDp(),
                            feature.orientation == FoldingFeature.Orientation.VERTICAL,
                        )
                    }
                }
                val layout = conversationLayout(fullWidth, maxHeight, fold)
                val wide = layout is ConversationLayout.Dual
                val showSidebar = wide && sidebarExpanded
                val fullDetail = wide && !sidebarExpanded && fold == null
                val availableHeight = when (layout) { is ConversationLayout.Single -> layout.height; is ConversationLayout.Dual -> layout.height }
                val contentWidth = if (fullDetail) fullWidth else when (layout) { is ConversationLayout.Single -> layout.width; is ConversationLayout.Dual -> layout.detailWidth }
                val drawerWidth = minOf(360.dp, (contentWidth - 56.dp).coerceAtLeast(0.dp))
                LaunchedEffect(wide) { if (wide) drawer = false }
                val motion = rememberDrawerMotion(drawer && !wide) { open ->
                    if (open) { keyboard?.hide(); focus.clearFocus() }
                    drawer = open
                }
                val progress by motion
                val pixels = with(density) { drawerWidth.toPx() }
                motion.width = pixels
                val swipe = if (wide) Modifier else Modifier.drawerSwipe(motion, with(density) { DrawerSwipeEdge.toPx() })
                val contentLeft = if (fullDetail) 0.dp else when (layout) { is ConversationLayout.Single -> layout.left; is ConversationLayout.Dual -> layout.detailLeft }
                val contentTop = when (layout) { is ConversationLayout.Single -> layout.top; is ConversationLayout.Dual -> layout.top }
                val overlayWidth = minOf(contentWidth, 520.dp)
                val windowWidth = with(density) { LocalView.current.rootView.width.takeIf { it > 0 }?.toDp() } ?: fullWidth
                val sheetOffset = with(density) { viewportOrigin.x.toDp() } + contentLeft + contentWidth / 2 - windowWidth / 2
                val x = with(density) { contentLeft.roundToPx() }
                val y = with(density) { contentTop.roundToPx() }
                Surface(
                    Modifier.requiredWidth(contentWidth).height(availableHeight).offset { IntOffset(x + if (wide) 0 else (pixels * progress).roundToInt(), y) }
                        .then(if (drawer && !wide) Modifier.clearAndSetSemantics {} else Modifier),
                    color = pageColor,
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                ) {
                    when (navigation.page) {
                        AppPage.PROJECTS -> ProjectPage(vm, { navigation = navigation.open(navigation.projectsBackPage) }) { name -> navigation = navigation.openProject(name) }
                        AppPage.PROJECT_DETAIL -> navigation.projectName?.let { name -> ProjectDetailPage(name, vm,
                            back = { navigation = navigation.open(AppPage.CONVERSATION) }, moreProjects = { navigateProjects(AppPage.PROJECT_DETAIL) },
                            openConversation = { conversation -> vm.enqueue { actions.select(conversation.id) }; navigation = navigation.open(AppPage.CONVERSATION) },
                            openedNewConversation = { navigation = navigation.open(AppPage.CONVERSATION) }) }
                        AppPage.SETTINGS -> SettingsPage(system, appearance, { value -> vm.enqueue { vm.report(actions.setAppearance(value)) } }, { next ->
                            val page = AppPage.fromSettingsRoute(next)
                            if (page == AppPage.GATEWAY) { gatewayStartAdding = false; gatewayFromIntro = false }
                            navigate(page)
                        }, { navigation = navigation.open(AppPage.CONVERSATION) }, vm, petEnabled, petPermitted, hostActions.pet)
                        AppPage.GATEWAY -> GatewayPage(vm, startAdding = gatewayStartAdding) { navigation = navigation.open(if (gatewayFromIntro) AppPage.CONVERSATION else AppPage.SETTINGS) }
                        AppPage.HISTORY_LIMITS -> EventHistoryPage(actions::eventHistoryLimits, actions::saveEventHistoryLimits) { navigation = navigation.open(AppPage.SETTINGS) }
                        AppPage.DIAGNOSTIC -> DiagnosticPage(vm) { navigation = navigation.open(AppPage.SETTINGS) }
                        AppPage.LICENSES -> LicensePage { navigation = navigation.open(AppPage.SETTINGS) }
                        AppPage.ARCHIVED -> ArchivedPage(state, vm) { navigation = navigation.open(AppPage.SETTINGS) }
                        AppPage.SKILLS -> SkillsPage(vm, onBack = { navigation = navigation.openAddSheet() }, onConversation = { navigation = navigation.open(AppPage.CONVERSATION) })
                        AppPage.PLUGINS -> PluginPage(vm) { navigation = navigation.openAddSheet() }
                        AppPage.APP_FUNCTIONS -> AppFunctionPage(vm) { navigation = navigation.openAddSheet() }
                        AppPage.CONVERSATION -> ConversationPane(
                            state, system, vm, hostActions, selectPlugin, Modifier.fillMaxSize().then(swipe),
                            showMenu = !showSidebar,
                            menuLabel = if (wide) AppStrings.expandConversationSidebar else AppStrings.openConversationDrawer,
                            onMenu = { keyboard?.hide(); focus.clearFocus(); if (wide) sidebarExpanded = true else drawer = true },
                            onNew = { dialog = "new" }, onMore = { dialog = it },
                            onAnchor = { toolbarAnchor = it }, onAdd = { showAddSheet() },
                            onRead = { title, value -> reading = title to value },
                        )
                    }
                }
                if (!wide && drawer) Box(Modifier.offset { IntOffset(x + (pixels * progress).roundToInt(), y) }.requiredWidth(contentWidth).height(availableHeight).then(swipe).clickable { drawer = false })
                if (showSidebar || (!wide && (drawer || progress > 0f))) ConversationListPane(state, conversationQuery, { conversationQuery = it }, conversationListScroll,
                    onSelect = { c -> vm.enqueue { actions.select(c.id) }; drawer = false; navigation = navigation.open(AppPage.CONVERSATION) },
                    onNew = { drawer = false; dialog = "new" }, onSettings = { navigate(AppPage.SETTINGS) }, onProjects = { navigateProjects(AppPage.CONVERSATION) },
                    onProject = { name -> drawer = false; navigation = navigation.openProject(name) },
                    onCollapse = if (wide) ({ sidebarExpanded = false }) else null,
                    modifier = if (layout is ConversationLayout.Dual) Modifier.width(layout.listWidth).height(layout.height).offset {
                        IntOffset(with(density) { layout.listLeft.roundToPx() }, with(density) { layout.top.roundToPx() })
                    } else Modifier.width(drawerWidth).height(availableHeight).offset {
                        IntOffset(x + ((progress - 1f) * pixels).roundToInt(), y)
                    }.then(swipe))
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                if (navigation.addSheetOpen) ModalBottomSheet(onDismissRequest = { navigation = navigation.closeAddSheet() }, modifier = Modifier.offset(x = sheetOffset), sheetMaxWidth = overlayWidth, shape = RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp), containerColor = addSheetColor(), contentColor = addInkColor()) {
                    val target = state.selected?.conversation
                    val canImport = target != null && !target.archived && !target.deleted && target.draft.pendingAttachment == null && target.draft.attachments.size < 4
                    val images = canImport && !camera.busy && agentOptions.any { it.agent == target?.config?.agent && it.images && it.unavailable == null }
                    val files = target != null && !target.archived && !target.deleted && target.draft.pendingAttachment == null && target.draft.attachments.size < 4 && agentOptions.any { it.agent == target.config.agent && it.resources && it.unavailable == null }
                    Column(Modifier.fillMaxWidth().heightIn(max = (availableHeight - 48.dp).coerceAtLeast(120.dp)).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AttachmentTile(AppStrings.takePhoto, AppIcons.Camera, images, Modifier.weight(1f)) { if (target != null) { navigate(AppPage.CONVERSATION); camera.start(target) } }
                            AttachmentTile(AppStrings.photos, AppIcons.Photo, images, Modifier.weight(1f)) {
                                if (target != null) {
                                    fileTarget = target.id.value; fileWorkspace = target.config.workspace; navigation = navigation.open(AppPage.CONVERSATION)
                                    photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            }
                            AttachmentTile(AppStrings.localFile, AppIcons.Upload, files, Modifier.weight(1f)) {
                                if (target != null) { fileTarget = target.id.value; fileWorkspace = target.config.workspace; navigation = navigation.open(AppPage.CONVERSATION); filePicker.launch(arrayOf("*/*")) }
                            }
                        }
                        CapabilityRow(AppStrings.devicePlugins, AppStrings.connectDeviceCapabilitiesToExpandYourTasks, AppIcons.Plugin) { navigation = navigation.open(AppPage.PLUGINS) }
                        CapabilityRow(AppStrings.appFunctions, AppStrings.appFunctionsDescription, AppIcons.Plugin) { navigation = navigation.open(AppPage.APP_FUNCTIONS) }
                        CapabilityRow(AppStrings.skills, AppStrings.reuseExpertiseForSpecificTasks, AppIcons.Skill) { navigation = navigation.open(AppPage.SKILLS) }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                skillProposal?.let { editor -> SkillProposalDialog(editor.proposal, vm, sourceAvailable = state.selected?.turns?.any { turn -> turn.skillProposals.any { it.ref == editor.proposal.ref } } == true, onDismiss = vm::dismissSkillProposal) }
                reading?.let { (title, text) ->
                    val ink = readerInk()
                    val muted = readerMuted()
                    ModalBottomSheet(onDismissRequest = { reading = null }, modifier = Modifier.offset(x = sheetOffset), sheetMaxWidth = overlayWidth, containerColor = raisedColor(), contentColor = if (dark) ink else contentColorFor(raisedColor())) {
                        Column(Modifier.fillMaxWidth().heightIn(max = (availableHeight - 48.dp).coerceAtLeast(120.dp))) {
                            Text(title, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = if (dark) muted else Color.Unspecified, style = MaterialTheme.typography.titleMedium)
                            val clipboard = LocalClipboardManager.current
                            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }, colors = if (dark) textButtonColors(muted) else textButtonColors()) { Text(AppStrings.copyOriginalText) }
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    if (dark) readerBody(text, ink, muted) else AnnotatedString(text),
                                    Modifier.heightIn(max = (availableHeight - 180.dp).coerceAtLeast(100.dp)).verticalScroll(rememberScrollState()).padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                val c = state.selected?.conversation
                if (dialog == "gateway-intro") AlertDialog(
                    onDismissRequest = { dialog = null }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp),
                    title = { Text(AppStrings.configureAGatewayFirst) },
                    text = { Text(AppStrings.addAModelGatewayBeforeStartingAConversationThe) },
                    confirmButton = { TextButton(onClick = {
                        dialog = null; gatewayStartAdding = true; gatewayFromIntro = true; navigate(AppPage.GATEWAY)
                    }) { Text(AppStrings.setUpGateway) } },
                    dismissButton = { TextButton(onClick = { dialog = null }) { Text(AppStrings.later) } },
                )
                if (dialog == "new") ConfigDialog(vm, c, onDismiss = { dialog = null }, onApply = { config, project -> vm.enqueue { actions.create(config, project) }; navigation = navigation.open(AppPage.CONVERSATION); dialog = null }, anchor = toolbarAnchor)
                if (c != null) when (dialog) {
                    "rename" -> TextEditDialog(AppStrings.rename, c.title, { dialog = null }) { value -> vm.enqueue { vm.report(actions.rename(c.id, value)) }; dialog = null }
                    "project" -> ProjectGroupDialog(c, state.projects, { dialog = null }) { project -> vm.enqueue { vm.report(actions.project(c.id, project)) }; dialog = null }
                    "delete" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(AppStrings.deleteConversation) }, text = { Text(AppStrings.theConversationMovesToRecentlyDeletedAndCanBe) },
                        confirmButton = { TextButton(onClick = { vm.enqueue { vm.report(actions.delete(c.id, true)) }; dialog = null }) { Text(AppStrings.delete) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(AppStrings.cancel) } })
                    "attachments" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(AppStrings.conversationAttachments) }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(AppStrings.sentAttachments)
                        val sent = full.turns.filter { it.execution != null }.groupBy { it.workspace }
                        if (sent.values.all { turns -> turns.all { it.attachments.isEmpty() } }) EmptyPlaceholder(AppStrings.noSentAttachments)
                        else sent.forEach { (workspace, turns) ->
                            val refs = turns.flatMap { it.attachments }.distinct()
                            if (refs.isNotEmpty()) AttachmentList(refs, workspace, vm)
                        }
                        Text(AppStrings.draftAttachments)
                        if (c.draft.attachments.isEmpty()) EmptyPlaceholder(AppStrings.noDraftAttachments) else AttachmentList(c.draft.attachments, c.config.workspace, vm)
                    } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(AppStrings.close) } }) }
                    "find" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> FindDialog(full, onDismiss = { dialog = null }, onSelect = { hit -> vm.jumpTo(c.id, hit); dialog = null }) }
                    "share" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> ShareDialog(full, hostActions.share, onDismiss = { dialog = null }) }
                }
            }
        }
    }
}

@Composable private fun ConversationPane(
    state: ConversationState, system: SystemStatus, vm: ConversationViewModel,
    hostActions: ConversationHostActions, onSelectPlugin: (String) -> Unit, modifier: Modifier,
    showMenu: Boolean, menuLabel: String, onMenu: () -> Unit, onNew: () -> Unit, onMore: (String) -> Unit,
    onAnchor: (IntRect) -> Unit, onAdd: () -> Unit, onRead: (String, String) -> Unit,
) {
    Column(modifier) {
        ConversationToolbar(state.selected?.conversation, vm,
            modifier = Modifier.fillMaxWidth().testTag("conversation-toolbar"),
            onMenu = onMenu, onNew = onNew, onMore = onMore, onAnchor = onAnchor, showMenu = showMenu, menuLabel = menuLabel)
        if (!system.connected || !system.ready) Text(
            system.message,
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp).align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall,
        )
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("conversation-transcript")) {
            CompositionLocalProvider(LocalContentColor provides conversationInk()) {
                Box(Modifier.align(Alignment.Center).widthIn(max = 760.dp).fillMaxSize()) {
                    when {
                        state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
                        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.selected == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyPlaceholder(AppStrings.noConversationsYet, AppStrings.startAConversationWithASpecificTask)
                                Button(onClick = onNew, colors = filledButtonColors()) { Text(AppStrings.newConversation2) }
                            }
                        }
                        else -> {
                            val detail = state.selected!!
                            key(detail.conversation.id) {
                                Timeline(detail, vm, Modifier.fillMaxSize(), read = onRead,
                                    hostActions = hostActions, proposal = vm::openSkillProposal, onSelectPlugin = onSelectPlugin)
                            }
                        }
                    }
                }
            }
        }
        if (state.selected != null && state.error == null && !state.loading) {
            Composer(state.selected!!, state, system, vm,
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 760.dp).fillMaxWidth().testTag("conversation-composer"),
                onAdd = onAdd)
        }
    }
}

@Composable private fun ConversationListPane(state: ConversationState, query: String, onQuery: (String) -> Unit, listScroll: LazyListState, onSelect: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onProjects: () -> Unit, onProject: (String) -> Unit, onCollapse: (() -> Unit)?, modifier: Modifier) {
    val control = drawerControlColor()
    Surface(modifier, color = drawerColor(), contentColor = conversationInk()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (onCollapse != null) 10.dp else 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = if (darkChrome()) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AppStrings.appName, Modifier.weight(1f), color = conversationInk(), style = MaterialTheme.typography.headlineSmall)
                DrawerPill(onNew, control, Modifier.testTag("drawer-new")) {
                    AppIcon(AppIcons.New, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(AppStrings.newConversation, style = MaterialTheme.typography.titleMedium)
                }
                if (onCollapse != null) {
                    Spacer(Modifier.width(8.dp))
                    DrawerCircle(AppStrings.collapseConversationSidebar, onCollapse, control, AppIcons.Back, Modifier.testTag("sidebar-collapse"))
                }
            }
            val visible = state.conversations.filter { !it.conversation.archived && !it.conversation.deleted && it.conversation.title.contains(query, true) }
            val pinned = visible.filter { it.conversation.pinned }
            val history = visible.filter { !it.conversation.pinned && it.conversation.project == null }
            val projectNames = state.projects.map { it.name }.filter { name ->
                query.isBlank() || name.contains(query, true) || visible.any { it.conversation.project == name }
            }.take(3)
            LazyColumn(Modifier.weight(1f), state = listScroll, horizontalAlignment = Alignment.Start) {
                if (pinned.isNotEmpty()) {
                    item(key = "section:pinned") { DrawerSection(AppStrings.pin) }
                    items(pinned, key = { it.conversation.id.value }) { DrawerConversation(it, state, onSelect) }
                }
                if (query.isBlank() || projectNames.isNotEmpty()) {
                    item(key = "section:projects") { DrawerSection(AppStrings.project) }
                    projectNames.forEach { name ->
                        item(key = "project:$name") { DrawerEntry(name) { onProject(name) } }
                    }
                    if (query.isBlank()) item(key = "more-projects") { DrawerEntry(AppStrings.moreProjects, onClick = onProjects) }
                }
                if (history.isNotEmpty() || visible.isEmpty()) {
                    item(key = "section:history") { DrawerSection(AppStrings.history) }
                    items(history, key = { it.conversation.id.value }) { DrawerConversation(it, state, onSelect) }
                    if (visible.isEmpty()) item(key = "empty") { EmptyPlaceholder(AppStrings.noMatchingConversations, AppStrings.tryAnotherSearchTermOrStartANewConversation) }
                }
            }
            val footerHeight = if (onCollapse != null) ToolbarControl + 8.dp else ToolbarControl
            Row(Modifier.fillMaxWidth().padding(top = if (darkChrome()) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DrawerSearch(query, onQuery, control, Modifier.weight(1f).testTag("drawer-search"), footerHeight)
                DrawerCircle(AppStrings.settings, onSettings, control, AppIcons.Settings, size = footerHeight)
            }
        }
    }
}

@Composable private fun DrawerSection(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun DrawerEntry(title: String, mark: AppGlyph? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(DrawerRowHeight).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) {
            AppIcon(mark, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current)
    }
}

@Composable private fun DrawerConversation(item: ConversationSummary, state: ConversationState, onSelect: (Conversation) -> Unit) {
    val selected = state.selected?.conversation?.id == item.conversation.id
    Surface(
        color = if (selected) buttonColor() else Color.Transparent,
        contentColor = if (selected) onButtonColor() else conversationInk(),
        shape = RoundedCornerShape(14.dp),
    ) {
        DrawerEntry(item.conversation.title, mark = item.conversation.config.agent.glyph()) { onSelect(item.conversation) }
    }
}

private val DrawerRowHeight = 40.dp

@Composable private fun DrawerPill(onClick: () -> Unit, color: Color, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(ToolbarControl).then(drawerControlShadow()).clip(CircleShape).background(color).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val row = this
        CompositionLocalProvider(LocalContentColor provides onButtonColor()) { row.content() }
    }
}

@Composable private fun DrawerCircle(label: String, onClick: () -> Unit, color: Color, icon: AppGlyph, modifier: Modifier = Modifier, size: Dp = ToolbarControl) {
    Box(modifier.size(size).then(drawerControlShadow()).clip(CircleShape).background(color).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        AppIcon(icon, null, Modifier.size(22.dp), tint = onButtonColor())
    }
}

@Composable private fun drawerControlShadow() = if (darkChrome()) Modifier else Modifier.shadow(floatingElevation(), CircleShape)

@Composable private fun DrawerSearch(query: String, onQuery: (String) -> Unit, color: Color, modifier: Modifier = Modifier, height: Dp = ToolbarControl) {
    Surface(modifier.height(height).then(if (darkChrome()) Modifier else Modifier.lightInputShadow(height / 2)),
        shape = CircleShape, color = color, contentColor = onButtonColor(), shadowElevation = 0.dp, tonalElevation = 0.dp) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcons.Search, null, Modifier.size(22.dp), tint = onButtonColor())
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f).semantics { contentDescription = AppStrings.searchConversations },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = onButtonColor()),
                cursorBrush = SolidColor(onButtonColor()),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text(AppStrings.search, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        inner()
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ConversationToolbar(c: Conversation?, vm: ConversationViewModel, onMenu: () -> Unit, onNew: () -> Unit, onMore: (String) -> Unit, onAnchor: (IntRect) -> Unit = {}, modifier: Modifier = Modifier, showMenu: Boolean = true, menuLabel: String = AppStrings.openConversationDrawer) {
    var config by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf(IntRect.Zero) }
    var actions by remember { mutableStateOf(IntRect.Zero) }
    Row(modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = if (darkChrome()) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showMenu) Surface(Modifier.size(ToolbarControl), shape = CircleShape, color = buttonColor(), contentColor = onButtonColor(), shadowElevation = floatingElevation(), tonalElevation = 0.dp) { ActionIcon(menuLabel, onMenu, AppIcons.Menu) }
            Box(Modifier.padding(horizontal = 6.dp).onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                chip = IntRect(origin.x.roundToInt(), origin.y.roundToInt(), origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height)
            }) {
                Surface(shape = RoundedCornerShape(26.dp), color = buttonColor(), contentColor = onButtonColor(), shadowElevation = floatingElevation(), tonalElevation = 0.dp) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                    TextButton(
                        onClick = { config = true; vm.enqueue { vm.refresh() } },
                        modifier = Modifier.height(ToolbarControl),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        colors = textButtonColors(onButtonColor()),
                    ) {
                        val agent = c?.config?.agent
                        if (agent != null) {
                            AppIcon(agent.glyph(), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(agent?.label() ?: AppStrings.selectAgent)
                    }
                    }
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
                Surface(Modifier.height(ToolbarControl), shape = RoundedCornerShape(26.dp), color = buttonColor(), contentColor = onButtonColor(), shadowElevation = floatingElevation(), tonalElevation = 0.dp) {
                    Row(Modifier.height(ToolbarControl)) {
                        ActionIcon(AppStrings.newConversation2, onNew, AppIcons.New)
                        ActionIcon(AppStrings.moreConversationActions, { more = true }, AppIcons.More, c != null)
                    }
                }
                if (c != null) FrostedMenu(more, { more = false }, actions) {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        MenuCaption(c.title)
                        listOf(
                            Triple("share", AppStrings.share, AppIcons.Share),
                            Triple("pin", if (c.pinned) AppStrings.unpin else AppStrings.pin, AppIcons.Pin),
                            Triple("project", AppStrings.addToProject, AppIcons.Folder),
                            Triple("attachments", AppStrings.conversationAttachments, AppIcons.File),
                            Triple("find", AppStrings.findInChat, AppIcons.Search),
                            Triple("rename", AppStrings.rename, AppIcons.Edit),
                            Triple("archive", AppStrings.archive, AppIcons.Folder),
                            Triple("delete", AppStrings.delete, AppIcons.Trash),
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

@Composable private fun Composer(detail: ConversationDetail, state: ConversationState, system: SystemStatus, vm: ConversationViewModel, onAdd: () -> Unit, modifier: Modifier = Modifier) {
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
        if (!allowed) capture.error = AppStrings.microphonePermissionNotGrantedTypeInsteadOrGrantPermission
        else capture.error = null
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
    fun sendAndDismiss(mode: MessageDeliveryMode = MessageDeliveryMode.QUEUE) {
        vm.send(mode)
        keyboard?.hide()
        focus.clearFocus()
    }
    val stop = active?.execution != null
    val micAvailable = !stop && composer.value.text.isEmpty() && detail.conversation.draft.attachments.isEmpty()
    LaunchedEffect(detail.conversation.id) { capture.error = null }
    LaunchedEffect(micAvailable, stop) { if (!micAvailable || stop) voiceMode = false }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            if (unavailable) Text(AppStrings.thisConversationIsArchivedOrDeletedRestoreItIn, style = MaterialTheme.typography.bodySmall)
            if (state.occupied != null && active == null) Text(AppStrings.isRunningYouCanKeepEditingThisDraft(state.occupied!!.conversation.title), style = MaterialTheme.typography.bodySmall)
            if (system.diagnosticBusy) Text(AppStrings.shellDiagnosticsIsUsingTheRuntime, style = MaterialTheme.typography.bodySmall)
            val selectedCapabilities = detail.conversation.draft.capabilities + listOfNotNull(detail.conversation.creator)
            if (selectedCapabilities.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                selectedCapabilities.forEach { ref -> InputChip(selected = true,
                    onClick = { vm.enqueue { vm.actions.removeSkill(detail.conversation.id, ref) } },
                    label = { Text("${if (ref == detail.conversation.creator) AppStrings.skillCreatorChip else capabilityLabel(ref)} ×") }) }
            }
            Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                AttachmentList(detail.conversation.draft.attachments, detail.conversation.config.workspace, vm) { ref -> vm.enqueue { vm.actions.removeAttachment(detail.conversation.id, ref) } }
            }
            detail.conversation.draft.pendingAttachment?.let { pending ->
                Text(pending.error ?: AppStrings.importingAttachmentsYouCanSendWhenFinished, style = MaterialTheme.typography.bodySmall)
                if (pending.error != null) Row {
                    TextButton(onClick = { vm.importAttachment(detail.conversation.id, pending.workspace, pending.location) }) { Text(AppStrings.retry) }
                    TextButton(onClick = { vm.enqueue { vm.actions.discardAttachment(detail.conversation.id, pending.id) } }) { Text(AppStrings.removePendingAttachment) }
                }
            }
            capture.transfer?.let { VoiceModelProgress(it, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) }
            val notice = capture.error ?: hint
            if (notice != null) Text(notice, color = if (capture.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (capture.phase == "transcribing" && !holding) Text(AppStrings.transcribing, style = MaterialTheme.typography.bodySmall)
        }
        VoiceComposerBar(
            voiceMode = voiceMode,
            recording = holding && capture.phase == "recording",
            cancelArmed = cancelArmed,
            level = capture.level,
            transcript = capture.liveTranscript,
            enabled = !unavailable,
            micAvailable = micAvailable,
            stop = stop,
            stopEnabled = active?.phase != ExecutionPhase.CANCELLING,
            sendEnabled = !unavailable && system.ready && system.connected && !system.diagnosticBusy && detail.conversation.draft.pendingAttachment == null && (composer.value.text.isNotBlank() || detail.conversation.draft.attachments.isNotEmpty()),
            onAdd = onAdd,
            onStop = { active?.execution?.let(vm::stop) },
            onSend = { sendAndDismiss() },
            onInsert = { sendAndDismiss(MessageDeliveryMode.INSERT) },
            onEnterVoice = {
                hint = null
                capture.error = null
                voiceMode = true
                keyboard?.hide()
                focus.clearFocus()
            },
            onExitVoice = { hint = null; voiceMode = false; openKeyboard = true },
            onHoldTap = { hint = AppStrings.holdToSpeak },
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
                    Modifier.weight(1f).heightIn(min = ToolbarControl).padding(vertical = 10.dp).focusRequester(focusRequester),
                    enabled = !unavailable, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = onButtonColor()),
                    cursorBrush = SolidColor(onButtonColor()),
                    decorationBox = { inner -> Box { if (composer.value.text.isEmpty()) Text(AppStrings.describeATaskOrAddContext, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
                )
            },
        )
        if (active?.pending == true) TextButton(onClick = { vm.enqueue { vm.actions.reconcile(detail.conversation.id) } }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(AppStrings.checkPendingRequest) }
    }
}

@OptIn(FlowPreview::class)
internal fun Turn.replyActionsVisible() = !occupied && !pending

/** Activity belongs to one independent item after this turn's content, never to text or cards. */
internal fun Turn.showsSeparateActivity(): Boolean = occupied || pending

@Composable internal fun ReplyActivity(phase: ExecutionPhase?) {
    if (phase == ExecutionPhase.CANCELLING) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StreamingCursor()
            Text(AppStrings.stopping, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else StreamingCursor(description = AppStrings.replying)
}

@Composable internal fun UserMessageBubble(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(21.dp, 21.dp, 6.dp, 21.dp),
        color = userBubbleColor(),
        contentColor = userBubbleInk(),
        modifier = Modifier.widthIn(max = 360.dp).testTag("user-bubble"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), content = content)
    }
}

@Composable internal fun Timeline(detail: ConversationDetail, vm: ConversationViewModel, modifier: Modifier, followPadding: PaddingValues? = null, read: (String, String) -> Unit, hostActions: ConversationHostActions, proposal: (SkillProposal) -> Unit, onSelectPlugin: (String) -> Unit, initialFollow: Boolean? = null, onFollowChanged: (Boolean) -> Unit = {}) {
    val topFade = if (darkChrome()) ConversationEdgeFade else LightConversationTopFade
    val bottomFade = if (darkChrome()) ConversationEdgeFade else LightConversationBottomFade
    val contentPadding = PaddingValues(start = 16.dp, top = topFade, end = 16.dp, bottom = bottomFade)
    val activityTurn = detail.turns.lastOrNull { it.occupied } ?: detail.turns.lastOrNull { it.showsSeparateActivity() }
    val keys = buildList {
        if (detail.hasEarlier) add("earlier")
        detail.turns.forEach { t ->
            add("user:${t.id.value}")
            t.transcript().forEach { entry ->
                when (entry) {
                    is TranscriptEntry.Device -> add("device:${t.id.value}:${entry.record.operation.operationId}")
                    is TranscriptEntry.Reply -> add("message:${t.id.value}:${entry.message.id}")
                    is TranscriptEntry.ToolRun -> add("tools:${t.id.value}:${entry.steps.first().id}")
                }
            }
            t.permissions.forEach { add("permission:${t.id.value}:${it.id}:${it.revision}") }
            t.skillProposals.forEach { add("artifact:${t.id.value}:${it.ref}") }
            if (t.creatingSkill && !t.occupied && t.skillProposals.isEmpty() && !t.proposalsLoading) add("creator:${t.id.value}")
            if (t.queued || t.failure != null || t.phase in listOf(ExecutionPhase.CANCELLED, ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN, ExecutionPhase.AWAITING_APPROVAL)) add("status:${t.id.value}")
        }
        activityTurn?.let { add("activity:${it.id.value}") }
    }
    val initial = keys.indexOf(detail.conversation.anchor).coerceAtLeast(0)
    val list = rememberLazyListState(initial, detail.conversation.anchorOffset.coerceAtLeast(0))
    var follow by remember { mutableStateOf(initialFollow ?: (detail.conversation.anchor == null)) }
    SideEffect { onFollowChanged(follow) }
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
    LaunchedEffect(list, follow, dragging) {
        if (follow && !dragging) list.followConversationTail()
    }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString() to list.firstVisibleItemScrollOffset }.debounce(250).collect { (key, offset) ->
            if (key != null && key != "earlier") vm.enqueue { vm.actions.anchor(detail.conversation.id, key, offset) }
        }
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().conversationEdgeFade(conversationCanvas(), topFade, bottomFade), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (detail.hasEarlier) item(key = "earlier") {
                TextButton(onClick = { follow = false; vm.enqueue { vm.actions.loadEarlier(detail.conversation.id) } }, modifier = Modifier.fillMaxWidth()) { Text(AppStrings.loadEarlierMessages) }
            }
            if (detail.turns.isEmpty()) item(key = "empty") {
                Column(Modifier.fillParentMaxWidth().padding(top = 32.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(AppStrings.whatWouldYouLikeToDoToday, style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(AppStrings.startASpecificTaskWith(detail.conversation.config.agent.label()), Modifier.padding(top = 12.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    EmptyConversationPlugins(detail.conversation.draft.capabilities, onSelectPlugin)
                }
            }
            detail.turns.forEach { turn ->
                val entries = turn.transcript()
                val lastReply = entries.filterIsInstance<TranscriptEntry.Reply>().lastOrNull()?.message?.id
                val lastTools = entries.filterIsInstance<TranscriptEntry.ToolRun>().lastOrNull()?.steps?.firstOrNull()?.id
                item(key = "user:${turn.id.value}") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { UserMessageBubble { androidx.compose.foundation.text.selection.SelectionContainer { Text(turn.userText) }; AttachmentList(turn.attachments, turn.workspace, vm) } } }
                entries.forEach { entry ->
                    when (entry) {
                        is TranscriptEntry.Device -> item(key = "device:${turn.id.value}:${entry.record.operation.operationId}") {
                            com.github.ytlog.mobby.android.deviceoperation.ConversationDeviceCard(entry.record.operation, turn, detail.conversation, vm)
                        }
                        is TranscriptEntry.ToolRun -> item(key = "tools:${turn.id.value}:${entry.steps.first().id}") {
                            ExecutionFlow(turn, vm, entry.steps, showExtras = entry.steps.first().id == lastTools, read)
                        }
                        is TranscriptEntry.Reply -> item(key = "message:${turn.id.value}:${entry.message.id}") {
                            Column {
                                ReplyContent(entry.message.text, streaming = turn.occupied && turn.phase != ExecutionPhase.CANCELLING && entry.message.id == lastReply, read = read)
                                if (entry.message.id == lastReply && turn.replyActionsVisible()) {
                                    val reply = entry.message.text
                                    Row {
                                        val clipboard = LocalClipboardManager.current
                                        ActionIcon(AppStrings.copyReply, { clipboard.setText(AnnotatedString(reply)) }, AppIcons.Copy, tint = replyActionColor())
                                        ActionIcon(AppStrings.shareReply, { hostActions.share(reply) }, AppIcons.Share, tint = replyActionColor())
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
                        Text(AppStrings.generatedSkillDraft, style = MaterialTheme.typography.titleMedium)
                        Text(AppStrings.previewValidateAndSaveTo(candidate.agent.label()))
                    } }
                } }
                if (turn.creatingSkill && !turn.occupied && turn.skillProposals.isEmpty() && !turn.proposalsLoading) item(key = "creator:${turn.id.value}") {
                    Text(AppStrings.noSkillDraftToSaveThisTurnContinueDescribing, style = MaterialTheme.typography.bodySmall)
                }
                if ("status:${turn.id.value}" in keys) item(key = "status:${turn.id.value}") {
                    val statusInk = if (turn.queued) MaterialTheme.colorScheme.primary
                        else if (darkChrome()) MobbyColors.Dark.statusAccent else MobbyColors.Light.statusAccent
                    Surface(
                        color = if (darkChrome()) MobbyColors.Dark.statusCard else MobbyColors.Light.statusCard,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, statusInk.copy(alpha = 0.16f)),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                AppIcon(if (turn.queued) AppIcons.Send else AppIcons.Error, null, Modifier.padding(top = 1.dp).size(20.dp), tint = statusInk)
                                Text(turn.failure ?: if (turn.queued) AppStrings.queuedMessageWaiting else turn.phase.label(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                            if (turn.queued) OutlinedButton(
                                onClick = { vm.enqueue { vm.actions.cancelQueued(turn.id) } },
                                modifier = Modifier.align(Alignment.End),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text(AppStrings.cancelQueuedMessage, style = MaterialTheme.typography.labelLarge) }
                            else if (!turn.occupied) OutlinedButton(
                                onClick = { vm.enqueue { vm.actions.restoreDraft(detail.conversation.id, turn) } },
                                modifier = Modifier.align(Alignment.End),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, statusInk.copy(alpha = 0.3f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = statusInk),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            ) { Text(AppStrings.restoreToInput, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }
            activityTurn?.let { turn ->
                item(key = "activity:${turn.id.value}") {
                    Box(Modifier.testTag("turn-loading")) { ReplyActivity(turn.phase) }
                }
            }
        }
        if (!follow && list.canScrollForward && detail.turns.isNotEmpty()) FilledTonalButton(
            onClick = { follow = true },
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(followPadding ?: if (darkChrome()) PaddingValues(12.dp) else PaddingValues(20.dp))
                .shadow(if (darkChrome()) 0.dp else floatingElevation(), CircleShape)
                .size(48.dp),
            shape = CircleShape,
            colors = tonalButtonColors(),
            contentPadding = PaddingValues(0.dp),
        ) { AppIcon(AppIcons.ArrowDown, AppStrings.latestMessages, Modifier.size(22.dp), tint = onButtonColor()) }
    }
}

/** One collector follows measured layout changes. New tokens coalesce while an animation runs. */
internal suspend fun LazyListState.followConversationTail() {
    snapshotFlow {
        val layout = layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull()
        listOf(layout.totalItemsCount, last?.index, last?.offset, last?.size, layout.viewportEndOffset)
    }.conflate().collect {
        val lastIndex = layoutInfo.totalItemsCount - 1
        if (lastIndex < 0 || !canScrollForward) return@collect
        if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
            animateScrollToItem(lastIndex)
        }
        val layout = layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return@collect
        val distance = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
        if (last.index == lastIndex && distance > 0) {
            animateScrollBy(distance.toFloat(), tween(durationMillis = 180))
        }
    }
}

private val ConversationEdgeFade = 28.dp
private val LightConversationTopFade = 48.dp
private val LightConversationBottomFade = 64.dp

/** Softens the list where it meets the toolbar buttons and the input, without covering the jump button. */
private fun Modifier.conversationEdgeFade(color: Color, topFade: Dp, bottomFade: Dp) = drawWithContent {
    drawContent()
    val topHeight = topFade.toPx()
    val bottomHeight = bottomFade.toPx()
    val clear = color.copy(alpha = 0f)
    drawRect(
        brush = Brush.verticalGradient(
            0f to color,
            0.22f to color.copy(alpha = 0.72f),
            0.5f to color.copy(alpha = 0.28f),
            0.78f to color.copy(alpha = 0.06f),
            1f to clear,
            startY = 0f,
            endY = topHeight,
        ),
        size = Size(size.width, topHeight),
    )
    val top = size.height - bottomHeight
    drawRect(
        brush = Brush.verticalGradient(
            0f to clear,
            0.22f to color.copy(alpha = 0.06f),
            0.5f to color.copy(alpha = 0.28f),
            0.78f to color.copy(alpha = 0.72f),
            1f to color,
            startY = top,
            endY = size.height,
        ),
        topLeft = Offset(0f, top),
        size = Size(size.width, bottomHeight),
    )
}

internal fun capabilityLabel(ref: String): String {
    if (ref.startsWith("plugin:appfunction:")) return runCatching {
        val identity = String(java.util.Base64.getUrlDecoder().decode(ref.removePrefix("plugin:appfunction:")), Charsets.UTF_8)
        AppStrings.appFunctions + " · " + identity.substringAfter('\n').substringAfterLast('#').substringAfterLast('.')
    }.getOrDefault(AppStrings.appFunctions)
    return when (ref.removePrefix("plugin:device:")) {
    "screen" -> AppStrings.screen
    "sms" -> AppStrings.sms
    "sms:send" -> AppStrings.sendSms
    "contacts" -> AppStrings.contacts
    "contacts:write" -> AppStrings.editContacts
    "calendar" -> AppStrings.calendar
    "calendar:write" -> AppStrings.editCalendar
    "media" -> AppStrings.media
    "storage" -> AppStrings.storage
    "camera" -> AppStrings.camera
    "microphone" -> AppStrings.microphone
    "location" -> AppStrings.location
    "sensors" -> AppStrings.sensors
    "clipboard" -> AppStrings.clipboard
    "clipboard:write" -> AppStrings.writeToClipboard
    "office" -> AppStrings.officeDocuments
    else -> ref.split(':').getOrNull(3) ?: AppStrings.skills
}
}

@Composable internal fun ConversationViewport(onPosition: (Offset) -> Unit = {}, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { onPosition(it.positionInWindow()) }.clipToBounds(), content = content)
    }
}

private tailrec fun Context.findHostActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
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

internal fun readerBody(text: String, primary: Color, secondary: Color): AnnotatedString {
    val lines = text.split('\n')
    return buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            withStyle(SpanStyle(color = if (ToolPresentation.readerSectionLabel(lines, index)) secondary else primary)) {
                append(line)
            }
            if (index != lines.lastIndex) append('\n')
        }
    }
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
