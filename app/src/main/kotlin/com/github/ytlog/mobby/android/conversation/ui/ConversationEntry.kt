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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.Density
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
    val gatewaySignIn: suspend () -> com.github.ytlog.mobby.android.conversation.domain.gateway.GatewaySignInResult = {
        com.github.ytlog.mobby.android.conversation.domain.gateway.GatewaySignInResult.Failed(AppStrings.gatewaySignInUnavailable)
    },
    /**
     * Hosted program development page; hosts without one hide the entry. `writeNew` attaches the plugin to the open
     * conversation; `program` opens that program's details.
     */
    val programCenter: (@Composable (back: () -> Unit, writeNew: (idea: String?) -> Unit, program: String?) -> Unit)? = null,
    /** Installed programs the drawer offers, and how the host opens a program's page. */
    val programShortcuts: Flow<List<ProgramShortcut>> = flowOf(emptyList()),
    val openProgram: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ConversationEntry(
    actions: ConversationUseCases, hostActions: ConversationHostActions, conversationNavigation: String? = null,
    petEnabled: Boolean = false, petPermitted: Boolean = false, gatewayRefresh: Int = 0,
    updates: AppUpdateManager? = null, programNavigation: ProgramNavigation? = null,
) {
    val factory = remember(actions) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ConversationViewModel(actions) as T
    } }
    val vm: ConversationViewModel = viewModel(factory = factory)
    LaunchedEffect(gatewayRefresh) { if (gatewayRefresh > 0) vm.enqueue { vm.refresh() } }
    val state by vm.state.collectAsStateWithLifecycle()
    val system by vm.status.collectAsStateWithLifecycle()
    val programShortcuts by remember(hostActions) { hostActions.programShortcuts }.collectAsStateWithLifecycle(emptyList())
    val agentOptions by vm.agents.collectAsStateWithLifecycle()
    val gateways by vm.connections.collectAsStateWithLifecycle()
    val gatewaysLoaded by vm.gatewaysLoaded.collectAsStateWithLifecycle()
    val connectionsRead by vm.connectionsRead.collectAsStateWithLifecycle()
    val configuredConnections by vm.configuredConnections.collectAsStateWithLifecycle()
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
    val context = LocalContext.current
    val setupProgress = remember(context) { SetupProgress(context) }
    var setupStep by rememberSaveable { mutableStateOf(setupProgress.read()) }
    var setupAgent by rememberSaveable { mutableStateOf(setupProgress.agent()) }
    var connectionFromSetup by rememberSaveable { mutableStateOf(false) }
    var navigation by rememberSaveable(stateSaver = AppNavigationState.Saver) {
        mutableStateOf(AppNavigationState(page = if (setupStep == SetupStep.DONE) AppPage.CONVERSATION else AppPage.SETUP))
    }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var sidebarExpanded by rememberSaveable { mutableStateOf(true) }
    var conversationQuery by rememberSaveable { mutableStateOf("") }
    val conversationListScroll = rememberLazyListState()
    val appearance by actions.appearance.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var gatewayStartAdding by rememberSaveable { mutableStateOf(false) }
    var gatewayFromIntro by rememberSaveable { mutableStateOf(false) }
    var gatewayFromConnections by rememberSaveable { mutableStateOf(false) }
    var loginAgent by rememberSaveable { mutableStateOf<AgentId?>(null) }
    var toolbarAnchor by remember { mutableStateOf(IntRect.Zero) }
    val skillProposal by vm.skillProposal.collectAsStateWithLifecycle()
    val skillProposalSaved by vm.skillProposalSaved.collectAsStateWithLifecycle()
    LaunchedEffect(skillProposalSaved?.operation) {
        skillProposalSaved?.let { navigation = navigation.open(AppPage.SKILLS); vm.consumeSkillProposalSaved(it.operation) }
    }
    var reading by remember { mutableStateOf<Pair<String, String>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var wizardFooterInset by remember { mutableStateOf(0.dp) }
    val reportWizardFooterInset: (Dp) -> Unit = remember { { wizardFooterInset = it } }
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
    var consumedProgramNavigation by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(programNavigation) {
        if (programNavigation != null && programNavigation.token != consumedProgramNavigation) {
            keyboard?.hide()
            focus.clearFocus()
            drawer = false
            dialog = null
            reading = null
            navigation = navigation.openPrograms(program = programNavigation.program, fromSheet = false)
            consumedProgramNavigation = programNavigation.token
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
    fun showSetup(step: SetupStep) {
        setupStep = step
        setupProgress.save(step)
        loginAgent = null
        navigate(AppPage.SETUP)
    }
    fun finishSetup() {
        setupStep = SetupStep.DONE
        setupProgress.save(SetupStep.DONE)
        connectionFromSetup = false
        navigation = navigation.open(AppPage.CONVERSATION)
    }
    fun previousSetup() {
        when (setupStep) {
            SetupStep.AGENTS, SetupStep.DONE -> finishSetup()
            SetupStep.SIGN_IN -> showSetup(SetupStep.AGENTS)
            SetupStep.SCREEN -> showSetup(SetupStep.SIGN_IN)
        }
    }
    fun connectionsBack() {
        loginAgent = null
        navigation = navigation.open(if (connectionFromSetup) AppPage.SETUP else if (gatewayFromIntro) AppPage.CONVERSATION else AppPage.SETTINGS)
    }
    fun gatewayBack() {
        if (gatewayFromConnections) navigation = navigation.open(AppPage.CONNECTIONS)
        else if (connectionFromSetup) {
            if (gateways.isNotEmpty()) showSetup(SetupStep.SCREEN) else navigation = navigation.open(AppPage.SETUP)
        } else navigation = navigation.open(if (gatewayFromIntro) AppPage.CONVERSATION else AppPage.SETTINGS)
    }
    BackHandler(navigation.page != AppPage.CONVERSATION || navigation.addSheetOpen) {
        when {
            navigation.page == AppPage.SETUP -> previousSetup()
            navigation.page == AppPage.GATEWAY -> gatewayBack()
            navigation.page == AppPage.CONNECTIONS -> connectionsBack()
            else -> navigation = navigation.back(gatewayFromIntro)
        }
    }
    MaterialTheme(colorScheme = colors, typography = MobbyTypography) {
        if (updates?.state?.phase == AppUpdateManager.Phase.READY && updates.dismissedVersion != updates.state.version) {
            AlertDialog(onDismissRequest = updates::dismissNotice, title = { Text(AppStrings.updateAvailable(updates.state.version.orEmpty())) },
                text = { Text(AppStrings.updateReadyToInstall) },
                confirmButton = { TextButton(onClick = { updates.dismissNotice(); updates.install() }) { Text(AppStrings.installUpdate) } },
                dismissButton = { TextButton(onClick = updates::dismissNotice) { Text(AppStrings.later) } })
        }
        val selectPlugin = rememberDirectPluginSelector(vm, state.selected?.conversation)
        val camera = rememberCameraCapture(actions, { captured ->
            actions.importAttachment(ConversationId(captured.conversation), captured.workspace, requireNotNull(captured.attachmentUri))
        }, { message -> vm.report(OperationResult.Failed(message)) })
        val pageColor = if (navigation.page == AppPage.CONVERSATION || navigation.page == AppPage.PROJECT_DETAIL) conversationCanvas() else MaterialTheme.colorScheme.background
        // Two layers: the drawer beneath, and above it the page together with the system bars around it as
        // one card. The card slides over the drawer, so nothing in the drawer can show above the page, and
        // the status bar always matches whichever layer lies under it.
        val drawerBackdrop = drawerColor()
        var compact by remember { mutableStateOf(true) }
        var slide by remember { mutableFloatStateOf(0f) }
        val motion = rememberDrawerMotion(drawer && compact) { open ->
            if (open) { keyboard?.hide(); focus.clearFocus() }
            drawer = open
        }
        val drawerShown by remember(motion) { derivedStateOf { motion.value > 0f } }
        fun foldIn(density: Density) = windowInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull {
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
        val listPane: @Composable (wide: Boolean, modifier: Modifier) -> Unit = { wide, modifier ->
            ConversationListPane(state, conversationQuery, { conversationQuery = it }, conversationListScroll,
                onSelect = { c -> vm.enqueue { actions.select(c.id) }; drawer = false; navigation = navigation.open(AppPage.CONVERSATION) },
                onNew = { drawer = false; dialog = newConversationDialog(connectionsRead, configuredConnections) }, onSettings = { navigate(AppPage.SETTINGS) }, onProjects = { navigateProjects(AppPage.CONVERSATION) },
                onProject = { name -> drawer = false; navigation = navigation.openProject(name) },
                programs = programShortcuts, onProgramPage = { id -> drawer = false; hostActions.openProgram(id) },
                onProgramDetails = { id -> drawer = false; navigation = navigation.openPrograms(program = id, fromSheet = false) },
                onAllPrograms = { drawer = false; navigation = navigation.openPrograms(fromSheet = false) },
                onCollapse = if (wide) ({ sidebarExpanded = false }) else null,
                modifier = modifier)
        }
        Surface(Modifier.fillMaxSize(), color = pageColor) {
            // The drawer layer. Its viewport never moves, so it also reports where the viewport sits.
            Box(Modifier.fillMaxSize().drawBehind { if (motion.value > 0f) drawRect(drawerBackdrop) })
            ConversationViewport(onPosition = { viewportOrigin = it }) {
                val density = LocalDensity.current
                val geometry = viewportGeometry(maxWidth, maxHeight, foldIn(density), sidebarExpanded)
                if (!geometry.wide && (drawer || drawerShown)) {
                    val pixels = with(density) { geometry.drawerWidth.toPx() }
                    val x = with(density) { geometry.contentLeft.roundToPx() }
                    val y = with(density) { geometry.contentTop.roundToPx() }
                    listPane(false, Modifier.width(geometry.drawerWidth).height(geometry.height).offset {
                        IntOffset(x + drawerLayers(motion.value, pixels).drawer.roundToInt(), y)
                    }.graphicsLayer {
                        val scale = drawerLayers(motion.value, pixels).drawerScale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = scale
                        scaleY = scale
                    }.drawerSwipe(motion, with(density) { DrawerSwipeEdge.toPx() }))
                }
            }
            // Dims the whole drawer layer, its system bars included, while the page still covers it.
            Box(Modifier.fillMaxSize().drawBehind {
                val dim = drawerLayers(if (compact) motion.value else 1f, slide).drawerDim
                if (motion.value > 0f && dim > 0f) drawRect(Color.Black.copy(alpha = dim))
            })
            // The page card slides at its full size.
            Box(Modifier.fillMaxSize().graphicsLayer {
                val layers = drawerLayers(if (compact) motion.value else 0f, slide)
                translationX = layers.page
                shadowElevation = PageShadow.toPx() * layers.shadow
            }.background(pageColor)) { ConversationViewport {
                val fullWidth = maxWidth
                val density = LocalDensity.current
                val geometry = viewportGeometry(fullWidth, maxHeight, foldIn(density), sidebarExpanded)
                val layout = geometry.layout
                val wide = geometry.wide
                val showSidebar = wide && sidebarExpanded
                val availableHeight = geometry.height
                val contentWidth = geometry.contentWidth
                LaunchedEffect(wide) { if (wide) drawer = false }
                val pixels = with(density) { geometry.drawerWidth.toPx() }
                motion.width = pixels
                SideEffect { compact = !wide; slide = pixels }
                val swipe = if (wide) Modifier else Modifier.drawerSwipe(motion, with(density) { DrawerSwipeEdge.toPx() })
                val contentLeft = geometry.contentLeft
                val overlayWidth = minOf(contentWidth, 520.dp)
                val windowWidth = with(density) { LocalView.current.rootView.width.takeIf { it > 0 }?.toDp() } ?: fullWidth
                val sheetOffset = with(density) { viewportOrigin.x.toDp() } + contentLeft + contentWidth / 2 - windowWidth / 2
                val x = with(density) { contentLeft.roundToPx() }
                val y = with(density) { geometry.contentTop.roundToPx() }
                if (showSidebar && layout is ConversationLayout.Dual) listPane(true, Modifier.width(layout.listWidth).height(layout.height).offset {
                    IntOffset(with(density) { layout.listLeft.roundToPx() }, with(density) { layout.top.roundToPx() })
                })
                Surface(
                    Modifier.requiredWidth(contentWidth).height(availableHeight).offset { IntOffset(x, y) }
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
                            if (page == AppPage.CONNECTIONS) { gatewayStartAdding = false; gatewayFromIntro = false; gatewayFromConnections = false; loginAgent = null; connectionFromSetup = false }
                            if (page == AppPage.SETUP) { setupStep = setupProgress.read().takeUnless { it == SetupStep.DONE } ?: SetupStep.AGENTS; connectionFromSetup = false }
                            navigate(page)
                        }, { navigation = navigation.open(AppPage.CONVERSATION) }, vm, petEnabled, petPermitted, hostActions.pet)
                        AppPage.SETUP -> CompositionLocalProvider(LocalWizardFooterInset provides reportWizardFooterInset) { SetupGuidePage(vm, setupStep, setupAgent,
                            selectAgent = { setupAgent = it; setupProgress.selectAgent(it) },
                            api = { gatewayStartAdding = true; connectionFromSetup = true; gatewayFromIntro = true; gatewayFromConnections = false; navigate(AppPage.GATEWAY) },
                            next = { showSetup(if (setupStep == SetupStep.AGENTS) SetupStep.SIGN_IN else SetupStep.SCREEN) },
                            previous = ::previousSetup, finish = ::finishSetup, review = ::showSetup) }
                        AppPage.CONNECTIONS -> AgentConnectionsPage(vm, initialAgent = loginAgent,
                            back = ::connectionsBack,
                            gateway = { gatewayStartAdding = false; gatewayFromConnections = true; navigate(AppPage.GATEWAY) },
                            connected = { loginAgent = null; if (connectionFromSetup) showSetup(SetupStep.SCREEN) else if (gatewayFromIntro) navigation = navigation.open(AppPage.CONVERSATION) },
                            loginDismissed = if (connectionFromSetup) ::connectionsBack else null)
                        AppPage.GATEWAY -> GatewayPage(vm, startAdding = gatewayStartAdding, signIn = hostActions.gatewaySignIn, back = ::gatewayBack)
                        AppPage.HISTORY_LIMITS -> EventHistoryPage(actions::eventHistoryLimits, actions::saveEventHistoryLimits) { navigation = navigation.open(AppPage.SETTINGS) }
                        AppPage.DIAGNOSTIC -> DiagnosticPage(vm) { navigation = navigation.open(AppPage.SETTINGS) }
                        AppPage.LICENSES -> LicensePage { navigation = navigation.open(AppPage.SETTINGS) }
                        AppPage.UPDATES -> updates?.let { AppUpdatePage(it) { navigation = navigation.open(AppPage.SETTINGS) } }
                        // A launcher request starts the program page afresh even when it is already showing.
                        AppPage.PROGRAMS -> key(consumedProgramNavigation) {
                            hostActions.programCenter?.invoke({ navigation = navigation.back(gatewayFromIntro) }, { idea ->
                                selectPlugin(PROGRAM_DEVELOPMENT)
                                if (idea != null) vm.prefill(idea)
                                navigation = navigation.open(AppPage.CONVERSATION)
                            }, navigation.program)
                        }
                        AppPage.ARCHIVED -> ArchivedPage(state, vm) { navigation = navigation.open(AppPage.SETTINGS) }
                        AppPage.SKILLS -> CompositionLocalProvider(LocalSheetPlacement provides SheetPlacement(sheetOffset, overlayWidth)) { SkillsPage(vm, onBack = { navigation = navigation.openAddSheet() }, onConversation = { navigation = navigation.open(AppPage.CONVERSATION) }) }
                        AppPage.PLUGINS -> PluginPage(vm) { navigation = navigation.openAddSheet() }
                        AppPage.APP_FUNCTIONS -> AppFunctionPage(vm) { navigation = navigation.openAddSheet() }
                        AppPage.CONVERSATION -> ConversationPane(
                            state, system, vm, hostActions, selectPlugin, Modifier.fillMaxSize().then(swipe),
                            programs = EmptyPagePrograms(programShortcuts, hostActions.openProgram,
                                openDetails = { id -> navigation = navigation.openPrograms(program = id, fromSheet = false) },
                                openAll = { navigation = navigation.openPrograms(fromSheet = false) }),
                            showMenu = !showSidebar,
                            menuLabel = if (wide) AppStrings.expandConversationSidebar else AppStrings.openConversationDrawer,
                            onMenu = { keyboard?.hide(); focus.clearFocus(); if (wide) sidebarExpanded = true else drawer = true },
                            onNew = { dialog = newConversationDialog(connectionsRead, configuredConnections) }, onMore = { if (it == "connections") { loginAgent = null; gatewayFromIntro = false; connectionFromSetup = false; navigate(AppPage.CONNECTIONS) } else dialog = it },
                            onAnchor = { toolbarAnchor = it }, onAdd = { showAddSheet() },
                            onRead = { title, value -> reading = title to value },
                            contextBubblesVisible = wide || (!drawer && !drawerShown),
                        )
                    }
                }
                if (!wide && drawer) Box(Modifier.offset { IntOffset(x, y) }.requiredWidth(contentWidth).height(availableHeight).then(swipe).clickable { drawer = false })
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (navigation.page == AppPage.SETUP) wizardFooterInset else 0.dp))
                val sheetPlacement = SheetPlacement(sheetOffset, overlayWidth)
                if (navigation.addSheetOpen) AppSheet(onDismissRequest = { navigation = navigation.closeAddSheet() }, placement = sheetPlacement) {
                    val target = state.selected?.conversation
                    val availability = attachmentAvailability(target, agentOptions, system.ready, camera.busy)
                    AddSheetBody(maxHeight = (availableHeight - 48.dp).coerceAtLeast(120.dp),
                        tiles = {
                            AttachmentTile(AppStrings.takePhoto, AppIcons.Camera, availability.photos, Modifier.weight(1f)) { if (target != null) { navigate(AppPage.CONVERSATION); camera.start(target) } }
                            AttachmentTile(AppStrings.photos, AppIcons.Photo, availability.photos, Modifier.weight(1f)) {
                                if (target != null) {
                                    fileTarget = target.id.value; fileWorkspace = target.config.workspace; navigation = navigation.open(AppPage.CONVERSATION)
                                    photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            }
                            AttachmentTile(AppStrings.localFile, AppIcons.Upload, availability.files, Modifier.weight(1f)) {
                                if (target != null) { fileTarget = target.id.value; fileWorkspace = target.config.workspace; navigation = navigation.open(AppPage.CONVERSATION); filePicker.launch(arrayOf("*/*")) }
                            }
                        },
                        capabilities = {
                            AddSheetCapabilities(programs = hostActions.programCenter != null) { page ->
                                navigation = if (page == AppPage.PROGRAMS) navigation.openPrograms(fromSheet = true) else navigation.open(page)
                            }
                        })
                }
                skillProposal?.let { editor -> SkillProposalDialog(editor.proposal, vm, sourceAvailable = state.selected?.turns?.any { turn -> turn.skillProposals.any { it.ref == editor.proposal.ref } } == true, onDismiss = vm::dismissSkillProposal) }
                reading?.let { (title, text) ->
                    val ink = readerInk()
                    val muted = readerMuted()
                    AppSheet(onDismissRequest = { reading = null }, placement = sheetPlacement) {
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
                // A new conversation opened before connections were read waits in its picker, and becomes
                // the connect guide only once the read finds none. Agents still being verified keep their
                // connections, so a runtime that is not ready yet never asks the user to connect again.
                LaunchedEffect(dialog, connectionsRead, configuredConnections) {
                    if (dialog == "new") dialog = newConversationDialog(connectionsRead, configuredConnections)
                }
                if (dialog == "gateway-intro") AlertDialog(
                    onDismissRequest = { dialog = null }, title = { Text(AppStrings.connectAgentsTitle) },
                    text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(AppStrings.setupAgentBody)
                        val accounts by vm.nativeAccounts.collectAsStateWithLifecycle()
                        AgentSetupChoices(accounts, system.ready,
                            signIn = { value -> dialog = null; loginAgent = value; connectionFromSetup = false; gatewayFromIntro = true; navigate(AppPage.CONNECTIONS) },
                            api = { dialog = null; gatewayStartAdding = true; connectionFromSetup = false; gatewayFromIntro = true; gatewayFromConnections = false; navigate(AppPage.GATEWAY) })
                    } },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { dialog = null }) { Text(AppStrings.later) } },
                )
                if (dialog == "new") ConfigDialog(vm, c, onDismiss = { dialog = null }, onApply = { config, project -> vm.enqueue { actions.create(config, project) }; navigation = navigation.open(AppPage.CONVERSATION); dialog = null }, anchor = toolbarAnchor)
                if (c != null) when (dialog) {
                    "rename" -> TextEditDialog(AppStrings.rename, c.title, { dialog = null }) { value -> vm.enqueue { vm.report(actions.rename(c.id, value)) }; dialog = null }
                    "project" -> ProjectGroupDialog(c, state.projects, { dialog = null }) { project -> vm.enqueue { vm.report(actions.project(c.id, project)) }; dialog = null }
                    "delete" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(AppStrings.deleteConversation) }, text = { Text(AppStrings.theConversationMovesToRecentlyDeletedAndCanBe) },
                        confirmButton = { TextButton(onClick = { vm.enqueue { vm.report(actions.delete(c.id, true)) }; dialog = null }) { Text(AppStrings.delete) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(AppStrings.cancel) } })
                    "attachments" -> HistoryDialog(c.id, vm, { dialog = null }) { full -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(AppStrings.conversationAttachments) }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
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
            } }
            VirtualScreenPreviewLayer()
        }
    }
}

@Composable private fun ConversationPane(
    state: ConversationState, system: SystemStatus, vm: ConversationViewModel,
    hostActions: ConversationHostActions, onSelectPlugin: (String) -> Unit, modifier: Modifier, programs: EmptyPagePrograms?,
    showMenu: Boolean, menuLabel: String, onMenu: () -> Unit, onNew: () -> Unit, onMore: (String) -> Unit,
    onAnchor: (IntRect) -> Unit, onAdd: () -> Unit, onRead: (String, String) -> Unit,
    contextBubblesVisible: Boolean,
) {
    state.selected?.let { com.github.ytlog.mobby.android.deviceoperation.ConversationDevicePermissionDialog(it, vm) }
    val canvas = conversationCanvas()
    val backdrop = rememberCanvasBackdrop { drawRect(canvas) }
    CompositionLocalProvider(LocalGlassEnabled provides true, LocalConversationBackdrop provides backdrop) {
        Column(modifier.background(canvas)) {
            ConversationToolbarLayer(Modifier.fillMaxWidth().background(canvas).testTag("conversation-toolbar")) {
                ConversationToolbar(state.selected?.conversation, vm,
                    modifier = Modifier.fillMaxWidth(),
                    onMenu = onMenu, onNew = onNew, onMore = onMore, onAnchor = onAnchor, showMenu = showMenu, menuLabel = menuLabel)
                if (!system.connected || !system.ready) Text(
                    system.message,
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp).align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
                                    Timeline(detail, vm, Modifier.fillMaxSize(),
                                        bottomContentClearance = if (detail.conversation.draft.capabilities.isNotEmpty() || detail.conversation.creator != null) 56.dp else 0.dp,
                                        read = onRead,
                                        hostActions = hostActions, proposal = vm::openSkillProposal, onSelectPlugin = onSelectPlugin, programs = programs)
                                }
                            }
                        }
                    }
                }
            }
            if (state.selected != null && state.error == null && !state.loading) {
                Composer(state.selected!!, state, system, vm,
                    modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 760.dp).fillMaxWidth()
                        .testTag("conversation-composer"),
                    onAdd = onAdd, onConnect = { onMore("connections") }, contextBubblesVisible = contextBubblesVisible)
            }
        }
    }
}

@Composable private fun ConversationListPane(state: ConversationState, query: String, onQuery: (String) -> Unit, listScroll: LazyListState, onSelect: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onProjects: () -> Unit, onProject: (String) -> Unit,
    programs: List<ProgramShortcut>, onProgramPage: (String) -> Unit, onProgramDetails: (String) -> Unit, onAllPrograms: () -> Unit,
    onCollapse: (() -> Unit)?, modifier: Modifier) {
    GlassControlScope(background = drawerColor()) {
        Surface(modifier, color = drawerColor(), contentColor = conversationInk()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = GlassShadowClearance)) {
                Row(Modifier.fillMaxWidth().padding(bottom = if (darkChrome()) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DrawerBrand(Modifier.weight(1f))
                    DrawerPill(onNew, Modifier.testTag("drawer-new")) {
                        AppIcon(AppIcons.New, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(AppStrings.newConversation, style = MaterialTheme.typography.titleMedium)
                    }
                    if (onCollapse != null) {
                        Spacer(Modifier.width(8.dp))
                        DrawerCircle(AppStrings.collapseConversationSidebar, onCollapse, AppIcons.Back, Modifier.testTag("sidebar-collapse"))
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
                    programSection(programs, query, onProgramPage, onProgramDetails, onAllPrograms)
                    if (history.isNotEmpty() || visible.isEmpty()) {
                        item(key = "section:history") { DrawerSection(AppStrings.history) }
                        items(history, key = { it.conversation.id.value }) { DrawerConversation(it, state, onSelect) }
                        // Before the store first answers there is nothing to report; "no matches" is only for a search.
                        if (visible.isEmpty() && !state.loading) item(key = "empty") {
                            if (query.isBlank()) EmptyPlaceholder(AppStrings.noConversationsYet, AppStrings.startAConversationWithASpecificTask)
                            else EmptyPlaceholder(AppStrings.noMatchingConversations, AppStrings.tryAnotherSearchTermOrStartANewConversation)
                        }
                    }
                }
                val footerHeight = if (onCollapse != null) ToolbarControl + 8.dp else ToolbarControl
                Row(Modifier.fillMaxWidth().padding(top = if (darkChrome()) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DrawerSearch(query, onQuery, Modifier.weight(1f).testTag("drawer-search"), footerHeight)
                    DrawerCircle(AppStrings.settings, onSettings, AppIcons.Settings, size = footerHeight)
                }
            }
        }
    }
}

/** The app's mark in the current ink beside its name, sized to sit level with the buttons around it. */
@Composable internal fun DrawerBrand(modifier: Modifier = Modifier) {
    val ink = conversationInk()
    // The launcher mark has empty space around it; the offset lines its left edge up with the rows below.
    Row(modifier.offset(x = (-6).dp).testTag("drawer-brand"), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(com.github.ytlog.mobby.android.R.drawable.ic_launcher_monochrome), null, Modifier.size(34.dp), tint = ink)
        Text(AppStrings.appName, color = ink, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable private fun DrawerSection(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun DrawerEntry(title: String, mark: AppGlyph? = null, glass: Boolean = false, onClick: () -> Unit) {
    val row: @Composable () -> Unit = {
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
    if (glass) GlassSurface(Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(14.dp)) { row() }
    else row()
}

@Composable private fun DrawerConversation(item: ConversationSummary, state: ConversationState, onSelect: (Conversation) -> Unit) {
    val selected = state.selected?.conversation?.id == item.conversation.id
    Surface(
        modifier = Modifier.semantics { this.selected = selected },
        color = if (selected) buttonColor() else Color.Transparent,
        contentColor = if (selected) onButtonColor() else conversationInk(),
        shape = RoundedCornerShape(14.dp),
    ) {
        DrawerEntry(item.conversation.title, mark = item.conversation.config.agent.glyph()) { onSelect(item.conversation) }
    }
}

private val DrawerRowHeight = 40.dp

/** A program the drawer offers: a working program with a page opens it; anything else opens its details. */
data class ProgramShortcut(val id: String, val name: String, val opensPage: Boolean, val note: String? = null)

/** A request from outside, such as a launcher shortcut, to show the program page on [program]'s details or the list; [token] makes each request distinct. */
data class ProgramNavigation(val program: String?, val token: String)

/** The drawer's programs, filtered by the search; hidden when none are installed or none match. */
internal fun LazyListScope.programSection(shortcuts: List<ProgramShortcut>, query: String, openPage: (String) -> Unit,
    openDetails: (String) -> Unit, openAll: () -> Unit) {
    val visible = shortcuts.filter { it.name.contains(query, true) }
    if (visible.isEmpty()) return
    item(key = "section:programs") { DrawerSection(AppStrings.programs) }
    visible.forEach { shortcut ->
        item(key = "program:${shortcut.id}") {
            DrawerEntry(listOfNotNull(shortcut.name, shortcut.note).joinToString(" · "), mark = AppIcons.Terminal, glass = true) {
                if (shortcut.opensPage) openPage(shortcut.id) else openDetails(shortcut.id)
            }
        }
    }
    if (query.isBlank()) item(key = "all-programs") { DrawerEntry(AppStrings.allPrograms, glass = true, onClick = openAll) }
}

@Composable private fun DrawerPill(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    GlassSurface(modifier.height(ToolbarControl), shape = CircleShape) {
        Row(Modifier.height(ToolbarControl).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable private fun DrawerCircle(label: String, onClick: () -> Unit, icon: AppGlyph, modifier: Modifier = Modifier, size: Dp = ToolbarControl) {
    GlassSurface(modifier.size(size).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, shape = CircleShape) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppIcon(icon, null, Modifier.size(22.dp), tint = onButtonColor())
        }
    }
}

@Composable private fun DrawerSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier, height: Dp = ToolbarControl) {
    GlassSurface(modifier.height(height), shape = CircleShape) {
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
    Row(modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = if (darkChrome()) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showMenu) GlassSurface(Modifier.size(ToolbarControl), shape = CircleShape, tint = buttonColor()) { ActionIcon(menuLabel, onMenu, AppIcons.Menu) }
            Box(Modifier.padding(horizontal = 6.dp).onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                chip = IntRect(origin.x.roundToInt(), origin.y.roundToInt(), origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height)
            }) {
                GlassSurface(shape = RoundedCornerShape(26.dp), tint = buttonColor()) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                    TextButton(
                        onClick = { config = true; vm.enqueue { vm.refresh() } },
                        modifier = Modifier.height(ToolbarControl),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        colors = textButtonColors(onButtonColor()),
                    ) {
                        val agent = c?.config?.agent
                        if (agent != null) {
                            AppIcon(agent.glyph(), AppStrings.changeCurrentAgent(agent.label()), Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            AppIcon(if (config) AppIcons.ChevronUp else AppIcons.ChevronDown, null, Modifier.size(14.dp))
                        } else {
                            Text(AppStrings.selectAgent)
                        }
                    }
                    }
                }
                if (c != null) AgentConfigMenu(config, { config = false }, c, vm, chip, manageConnections = { onMore("connections") })
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                val rect = IntRect(origin.x.roundToInt(), origin.y.roundToInt(), origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height)
                actions = rect
                onAnchor(rect)
            }) {
                GlassSurface(Modifier.height(ToolbarControl), shape = RoundedCornerShape(26.dp), tint = buttonColor()) {
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

@Composable private fun Composer(detail: ConversationDetail, state: ConversationState, system: SystemStatus, vm: ConversationViewModel, onAdd: () -> Unit, modifier: Modifier = Modifier, onConnect: () -> Unit = {}, contextBubblesVisible: Boolean = true) {
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
    fun endHold(cancelled: Boolean) {
        val activeHold = holding
        holding = false
        cancelArmed = false
        if (activeHold) {
            if (cancelled) {
                snapshot.value = null
                capture.cancel()
            } else capture.finish()
        }
    }
    fun sendAndDismiss(mode: MessageDeliveryMode = MessageDeliveryMode.QUEUE) {
        if (vm.connections.value.none { it.agent == detail.conversation.config.agent }) { onConnect(); return }
        vm.send(mode)
        keyboard?.hide()
        focus.clearFocus()
    }
    val stop = active?.execution != null
    val hasDraftContent = composer.value.text.isNotBlank() || detail.conversation.draft.attachments.isNotEmpty()
    val micAvailable = !stop && composer.value.text.isEmpty() && detail.conversation.draft.attachments.isEmpty()
    LaunchedEffect(detail.conversation.id) { capture.error = null }
    LaunchedEffect(stop, unavailable) { if (stop || unavailable) voiceMode = false }
    fun enterVoice() {
        hint = null
        capture.error = null
        voiceMode = true
        keyboard?.hide()
        focus.clearFocus()
    }
    val selectedCapabilities = detail.conversation.draft.capabilities + listOfNotNull(detail.conversation.creator)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                if (unavailable) Text(AppStrings.thisConversationIsArchivedOrDeletedRestoreItIn, style = MaterialTheme.typography.bodySmall)
                if (state.occupied != null && active == null) Text(AppStrings.isRunningYouCanKeepEditingThisDraft(state.occupied!!.conversation.title), style = MaterialTheme.typography.bodySmall)
                if (system.diagnosticBusy) Text(AppStrings.shellDiagnosticsIsUsingTheRuntime, style = MaterialTheme.typography.bodySmall)
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
                hasDraftContent = hasDraftContent,
                sendEnabled = !unavailable && system.ready && system.connected && !system.diagnosticBusy && detail.conversation.draft.pendingAttachment == null && hasDraftContent,
                onAdd = onAdd,
                onStop = { active?.execution?.let(vm::stop) },
                onSend = { sendAndDismiss() },
                onInsert = { sendAndDismiss(MessageDeliveryMode.INSERT) },
                onEnterVoice = ::enterVoice,
                onExitVoice = { hint = null; voiceMode = false; openKeyboard = true },
                onHoldTap = { hint = AppStrings.holdToSpeak },
                onHoldStart = ::beginHold,
                onHoldMove = { cancelArmed = it },
                onHoldEnd = ::endHold,
                textField = {
                    androidx.compose.foundation.text.BasicTextField(
                        composer.value, vm::edit,
                        Modifier.weight(1f).heightIn(min = ToolbarControl).padding(vertical = 10.dp)
                            .voiceHoldOnLongPress(!unavailable && !stop, ::beginHold, { cancelArmed = it }, ::endHold, ::enterVoice).focusRequester(focusRequester),
                        // Disabled during a hold so the field's own long press cannot open text selection.
                        enabled = !unavailable && !holding, maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = onButtonColor()),
                        cursorBrush = SolidColor(onButtonColor()),
                        decorationBox = { inner -> Box { if (composer.value.text.isEmpty()) Text(AppStrings.describeATaskOrAddContext, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
                    )
                },
            )
            if (active?.pending == true && !active.reportsOutcome()) {
                TextButton(
                    onClick = { vm.enqueue { vm.actions.reconcile(detail.conversation.id) } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { Text(AppStrings.checkPendingRequest) }
            }
        }
        if (selectedCapabilities.isNotEmpty()) ComposerContextBubbles(maxWidth = maxWidth, visible = contextBubblesVisible) {
            selectedCapabilities.forEach { ref -> InputChip(selected = true,
                onClick = { vm.enqueue { vm.actions.removeSkill(detail.conversation.id, ref) } },
                label = { Text("${if (ref == detail.conversation.creator) AppStrings.skillCreatorChip else capabilityLabel(ref)} ×") }) }
        }
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

/**
 * The turn's outcome, closing its content as one more line of the run: no card, starting at the text edge. Color says
 * how much it needs a look, and an action waits at the far edge until the message needs that room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TurnStatus(
    turn: Turn,
    onCancelQueued: () -> Unit,
    onRestore: () -> Unit,
    onCheckPending: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null,
) {
    val status = turn.failure ?: if (turn.queued && !turn.pending) AppStrings.queuedMessageWaiting else turn.phase.label()
    val action = when {
        turn.pending && turn.occupied && onCheckPending != null -> AppStrings.checkPendingRequest to onCheckPending
        turn.queued && !turn.pending -> AppStrings.cancelQueuedMessage to onCancelQueued
        !turn.occupied -> AppStrings.restoreToInput to onRestore
        else -> null
    }
    val diagnostics = onDiagnostics?.takeIf { turn.diagnosticsActionVisible() }
    val ink = when {
        turn.phase == ExecutionPhase.FAILED -> MaterialTheme.colorScheme.error
        turn.failure == null && (turn.queued || turn.phase == ExecutionPhase.CANCELLED) -> toolCallInk()
        else -> statusAccentColor()
    }
    FlowRow(
        Modifier.fillMaxWidth().testTag("turn-status"),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val open = if (diagnostics == null) Modifier else Modifier.clip(RoundedCornerShape(8.dp)).clickable(
            onClickLabel = AppStrings.viewDiagnostics(turn.diagnostics.size),
            role = Role.Button,
            onClick = diagnostics,
        )
        // Top-aligned so that a long message keeps the chevron beside its first line.
        Row(open.heightIn(min = ProcessRowHeight).padding(vertical = 7.dp)) {
            Text(status, Modifier.weight(1f, fill = false), style = processTextStyle(), color = ink)
            if (diagnostics != null) {
                Spacer(Modifier.width(2.dp))
                AppIcon(AppIcons.ChevronRight, null, Modifier.padding(top = 3.dp).size(12.dp), tint = ink)
            }
        }
        if (action != null) Box(
            Modifier.padding(start = 12.dp).clip(CircleShape).background(userBubbleColor())
                .clickable(role = Role.Button, onClick = action.second)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) { Text(action.first, style = processTextStyle(), color = userBubbleInk(), maxLines = 1) }
    }
}

/** What an item is for the reader; it decides the air around the item. */
internal enum class TimelineBlock { USER, PROSE, PROCESS, CARD, ACTIONS }

/**
 * Steps stay with the sentence that announced them and the next reply opens a new group, so a long run reads as groups
 * rather than as evenly spaced lines. Process rows carry their own air, which these gaps add to.
 */
internal fun timelineGap(previous: TimelineBlock?, next: TimelineBlock): Dp = when {
    previous == null || next == TimelineBlock.ACTIONS -> 0.dp
    // The action row already separates the turns, so it replaces most of the usual gap.
    previous == TimelineBlock.ACTIONS && next == TimelineBlock.USER -> 14.dp
    next == TimelineBlock.USER -> 28.dp
    previous == TimelineBlock.PROCESS && next == TimelineBlock.PROCESS -> 0.dp
    previous == TimelineBlock.PROSE && next == TimelineBlock.PROCESS -> 2.dp
    previous == TimelineBlock.PROCESS && next == TimelineBlock.PROSE -> 12.dp
    previous == TimelineBlock.USER && next == TimelineBlock.PROCESS -> 8.dp
    previous == TimelineBlock.PROCESS || next == TimelineBlock.PROCESS -> 4.dp
    previous == TimelineBlock.USER -> 16.dp
    else -> 12.dp
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

@Composable internal fun Timeline(detail: ConversationDetail, vm: ConversationViewModel, modifier: Modifier, followPadding: PaddingValues? = null, read: (String, String) -> Unit, hostActions: ConversationHostActions, proposal: (SkillProposal) -> Unit, onSelectPlugin: (String) -> Unit, initialFollow: Boolean? = null, onFollowChanged: (Boolean) -> Unit = {}, programs: EmptyPagePrograms? = null, bottomContentClearance: Dp = 0.dp) {
    val topFade = ConversationTopFade
    // Context chips float just above the input, so text passing behind them fades out instead of colliding.
    val bottomFade = if (bottomContentClearance > 0.dp) maxOf(ContextChipFade, bottomContentClearance) else if (darkChrome()) ConversationEdgeFade else LightConversationBottomFade
    val contentPadding = PaddingValues(start = 16.dp, top = topFade, end = 16.dp, bottom = maxOf(bottomFade, bottomContentClearance))
    val activityTurn = detail.turns.lastOrNull { it.occupied } ?: detail.turns.lastOrNull { it.showsSeparateActivity() }
    val entries = detail.turns.associate { it.id to it.timelineEntries() }
    val keys = buildList {
        if (detail.hasEarlier) add("earlier")
        detail.turns.forEach { t ->
            add("user:${t.id.value}")
            entries.getValue(t.id).forEach { add(it.itemKey(t)) }
            t.permissions.forEach { add("permission:${t.id.value}:${it.id}:${it.revision}") }
            t.skillProposals.forEach { add("artifact:${t.id.value}:${it.ref}") }
            if (t.progress != null) add("notice:${t.id.value}")
            if (t.creatingSkill && !t.occupied && t.skillProposals.isEmpty() && !t.proposalsLoading) add("creator:${t.id.value}")
            if (t.reportsOutcome()) add("status:${t.id.value}")
            if (t.replyActionsVisible() && entries.getValue(t.id).any { it is TimelineEntry.Reply }) add("reply-actions:${t.id.value}")
        }
        activityTurn?.let { add("activity:${it.id.value}") }
    }
    val aliases = detail.turns.fold(emptyMap<String, String>()) { all, turn -> all + turn.timelineKeyAliases() }
    fun indexOfKey(key: String?) = keys.indexOf(aliases[key] ?: key)
    val initial = indexOfKey(detail.conversation.anchor).coerceAtLeast(0)
    val list = rememberLazyListState(initial, detail.conversation.anchorOffset.coerceAtLeast(0))
    var follow by remember { mutableStateOf(initialFollow ?: (detail.conversation.anchor == null)) }
    SideEffect { onFollowChanged(follow) }
    val target by vm.readingTarget.collectAsStateWithLifecycle()
    LaunchedEffect(target, keys) {
        val jump = target?.takeIf { it.conversation == detail.conversation.id } ?: return@LaunchedEffect
        val index = indexOfKey(jump.key)
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
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().conversationEdgeFade(conversationCanvas(), topFade, bottomFade), contentPadding = contentPadding) {
            var previous: TimelineBlock? = null
            fun block(key: String, kind: TimelineBlock, content: @Composable LazyItemScope.() -> Unit) {
                val gap = timelineGap(previous, kind)
                previous = kind
                item(key = key) { Box(Modifier.fillMaxWidth().padding(top = gap)) { content() } }
            }
            if (detail.hasEarlier) item(key = "earlier") {
                TextButton(onClick = { follow = false; vm.enqueue { vm.actions.loadEarlier(detail.conversation.id) } }, modifier = Modifier.fillMaxWidth()) { Text(AppStrings.loadEarlierMessages) }
            }
            if (detail.turns.isEmpty()) item(key = "empty") {
                Column(Modifier.fillParentMaxWidth().padding(top = 32.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(AppStrings.whatWouldYouLikeToDoToday, style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(AppStrings.startASpecificTaskWith(detail.conversation.config.agent.label()), Modifier.padding(top = 12.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    EmptyConversationPlugins(detail.conversation.draft.capabilities, programs, onSelectPlugin)
                }
            }
            detail.turns.forEach { turn ->
                val turnEntries = entries.getValue(turn.id)
                val lastReply = turnEntries.filterIsInstance<TimelineEntry.Reply>().lastOrNull()?.message
                block("user:${turn.id.value}", TimelineBlock.USER) { Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) { UserMessageBubble { androidx.compose.foundation.text.selection.SelectionContainer { Text(turn.userText) }; AttachmentList(turn.attachments, turn.workspace, vm) } } }
                turnEntries.forEach { entry ->
                    when (entry) {
                        is TimelineEntry.Device -> block(entry.itemKey(turn), TimelineBlock.PROCESS) {
                            com.github.ytlog.mobby.android.deviceoperation.ConversationDeviceCard(entry.record.operation, turn, detail.conversation, vm)
                        }
                        is TimelineEntry.Process -> block(entry.itemKey(turn), TimelineBlock.PROCESS) {
                            ExecutionFlow(turn, vm, entry.steps, devices = entry.devices,
                                deviceRow = { com.github.ytlog.mobby.android.deviceoperation.ConversationDeviceCard(it.operation, turn, detail.conversation, vm) }, read = read)
                        }
                        is TimelineEntry.Reply -> block(entry.itemKey(turn), TimelineBlock.PROSE) {
                            ReplyContent(
                                entry.message.text,
                                streaming = turn.occupied && turn.phase != ExecutionPhase.CANCELLING && entry.message.id == lastReply?.id,
                                images = { source, expanded -> vm.actions.previewReplyImage(turn.workspace, source, expanded) },
                                read = read,
                            )
                        }
                    }
                }
                turn.permissions.forEach { permission -> block("permission:${turn.id.value}:${permission.id}:${permission.revision}", TimelineBlock.CARD) {
                    PermissionCard(turn, permission, detail.conversation.config.agent, vm)
                } }
                turn.skillProposals.forEach { candidate -> block("artifact:${turn.id.value}:${candidate.ref}", TimelineBlock.CARD) {
                    OutlinedCard(onClick = { proposal(candidate) }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.outlinedCardColors(containerColor = conversationCardColor()), border = BorderStroke(0.dp, Color.Transparent)) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(AppStrings.generatedSkillDraft, style = MaterialTheme.typography.titleMedium)
                        Text(AppStrings.previewValidateAndSaveTo(candidate.agent.label()))
                    } }
                } }
                turn.progress?.let { notice -> block("notice:${turn.id.value}", TimelineBlock.CARD) { TurnNotice(notice) } }
                if (turn.creatingSkill && !turn.occupied && turn.skillProposals.isEmpty() && !turn.proposalsLoading) block("creator:${turn.id.value}", TimelineBlock.CARD) {
                    Text(AppStrings.noSkillDraftToSaveThisTurnContinueDescribing, style = MaterialTheme.typography.bodySmall)
                }
                if (turn.reportsOutcome()) block("status:${turn.id.value}", TimelineBlock.PROCESS) {
                    val checkPending: (() -> Unit)? = if (turn.id == activityTurn?.id && turn.occupied && turn.pending) {
                        { vm.enqueue { vm.actions.reconcile(detail.conversation.id) } }
                    } else null
                    TurnStatus(turn,
                        onCancelQueued = { vm.enqueue { vm.actions.cancelQueued(turn.id) } },
                        onRestore = { vm.enqueue { vm.actions.restoreDraft(detail.conversation.id, turn) } },
                        onCheckPending = checkPending,
                        onDiagnostics = { read(AppStrings.runtimeDiagnostics, turn.diagnostics.joinToString("\n\n") { it.text }) })
                }
                if (lastReply != null && turn.replyActionsVisible()) block("reply-actions:${turn.id.value}", TimelineBlock.ACTIONS) {
                    // Pulled left by the button's own inset so the glyph lines up with the text. The buttons are small
                    // to keep turns close together; Compose still accepts touches within the minimum touch target.
                    Row(Modifier.offset(x = (-6).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val clipboard = LocalClipboardManager.current
                        ActionIcon(AppStrings.copyReply, { clipboard.setText(AnnotatedString(lastReply.text)) }, AppIcons.Copy, tint = replyActionColor(), buttonSize = 28.dp, glyphSize = 16.dp)
                        ActionIcon(AppStrings.shareReply, { hostActions.share(lastReply.text) }, AppIcons.Share, tint = replyActionColor(), buttonSize = 28.dp, glyphSize = 16.dp)
                    }
                }
            }
            activityTurn?.let { turn ->
                block("activity:${turn.id.value}", TimelineBlock.CARD) {
                    Box(Modifier.testTag("turn-loading")) { ReplyActivity(turn.phase) }
                }
            }
        }
        if (!follow && list.canScrollForward && detail.turns.isNotEmpty()) GlassSurface(
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(followPadding ?: if (darkChrome()) PaddingValues(12.dp) else PaddingValues(20.dp))
                .size(48.dp),
            shape = CircleShape,
        ) {
            FilledTonalButton(
                onClick = { follow = true },
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.Transparent),
                elevation = null,
                contentPadding = PaddingValues(0.dp),
            ) { AppIcon(AppIcons.ArrowDown, AppStrings.latestMessages, Modifier.size(22.dp), tint = onButtonColor()) }
        }
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
private val ConversationTopFade = 16.dp
private val LightConversationBottomFade = 32.dp
private val ContextChipFade = 72.dp

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

@Composable internal fun AddSheetBody(maxHeight: Dp, tiles: @Composable RowScope.() -> Unit, capabilities: @Composable ColumnScope.() -> Unit) {
    SheetBody(maxHeight) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = tiles)
        capabilities()
    }
}

internal const val PROGRAM_DEVELOPMENT = "plugin:device:programs"

/**
 * App functions are hidden from the add sheet for now; their page and runtime support are kept.
 * Program development is the single entry for writing, installing, running and granting programs.
 */
@Composable internal fun AddSheetCapabilities(programs: Boolean, open: (AppPage) -> Unit) {
    SheetRow(AppStrings.devicePlugins, AppStrings.connectDeviceCapabilitiesToExpandYourTasks, AppIcons.Plugin) { open(AppPage.PLUGINS) }
    if (programs) SheetRow(AppStrings.programDevelopment, AppStrings.programDevelopmentEntrySummary, AppIcons.Terminal) { open(AppPage.PROGRAMS) }
    SheetRow(AppStrings.skills, AppStrings.reuseExpertiseForSpecificTasks, AppIcons.Skill) { open(AppPage.SKILLS) }
}

internal fun capabilityLabel(ref: String): String {
    if (ref.startsWith("plugin:appfunction:")) return runCatching {
        val identity = String(java.util.Base64.getUrlDecoder().decode(ref.removePrefix("plugin:appfunction:")), Charsets.UTF_8)
        AppStrings.appFunctions + " · " + identity.substringAfter('\n').substringAfterLast('#').substringAfterLast('.')
    }.getOrDefault(AppStrings.appFunctions)
    if (ref.startsWith("plugin:program:")) return AppStrings.programChip + " · " + ref.removePrefix("plugin:program:")
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
    "programs" -> AppStrings.programDevelopment
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

/**
 * Where the page and the drawer beneath it sit at an open fraction. The page slides the drawer's full
 * width at its full size with a shadow on its edge. Beneath it the drawer only drifts, and rises from a
 * smaller, dimmer layer ([drawerDim] is the black scrim over it) to full size and brightness, so opening
 * lifts the page off a layer below instead of pushing a panel in beside it.
 */
internal data class DrawerLayers(val page: Float, val drawer: Float, val shadow: Float, val drawerScale: Float, val drawerDim: Float)
private const val DrawerDrift = 0.3f
private const val DrawerRestScale = 0.94f
private const val DrawerRestDim = 0.25f
private val PageShadow = 24.dp
internal fun drawerLayers(progress: Float, width: Float) = DrawerLayers(
    page = width * progress, drawer = (progress - 1f) * width * DrawerDrift, shadow = progress,
    drawerScale = DrawerRestScale + (1f - DrawerRestScale) * progress, drawerDim = DrawerRestDim * (1f - progress),
)

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
