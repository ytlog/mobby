package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.gateway.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspacePickerTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ViewModelStore()
    private val options = mutableListOf(WorkspaceOption("default", "默认本机工作区"), WorkspaceOption("second", "Project B"))
    private val conversations = MutableStateFlow(ConversationState(loading = false, projects = listOf(Project("Project A", "second"))))
    private val created = CompletableDeferred<WorkspaceOption>()
    private var creations = 0
    private var projectReply: CompletableDeferred<OperationResult>? = null
    private val savedProjects = mutableListOf<Project>()
    private var projectConfig: NextTurnConfig? = null
    private val appliedConfigs = mutableListOf<NextTurnConfig>()
    private var gatewayDefault: AgentId? = AgentId.CODEX
    private var gatewayReads = 0
    private var gatewayReadGate: CompletableDeferred<Unit>? = null
    private var agentOptions = listOf(AgentOption(AgentId.CLAUDE_CODE, mapOf("claude-fixture" to emptySet()), null, true, emptySet()))
    private var newSessionCreated = false
    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, a -> body(m.name.substringBefore("-"), a ?: emptyArray()) } as T
    private fun vm(): ConversationViewModel {
        val system = stub<SystemPort> { name, args -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> agentOptions
            "nativeAccounts", "connectionSettings" -> emptyList<Any>()
            "gateways" -> {
                gatewayReads++
                gatewayReadGate?.let { gate ->
                    @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
                    scope.launch { try { gate.await(); continuation.resume(gatewayProfiles()) } catch (e: Exception) { continuation.resumeWithException(e) } }
                    return@stub COROUTINE_SUSPENDED
                }
                gatewayProfiles()
            }
            "defaultGateway" -> gatewayDefault?.let { GatewayDefault(it, if (it == AgentId.CODEX) "CODEX" else if (it == AgentId.PI) "PI" else "CLAUDE", 1) }
            "selectDefaultGateway" -> { gatewayDefault = (args[0] as GatewayProfile).agent; OperationResult.Done }
            "workspaces" -> DataResult.Loaded(options.toList())
            "createWorkspace" -> {
                creations++
                @Suppress("UNCHECKED_CAST") val reply = args.last() as Continuation<Any?>
                scope.launch { try { val value = created.await(); options += value; reply.resume(DataResult.Loaded(value)) } catch (e: Exception) { reply.resumeWithException(e) } }
                COROUTINE_SUSPENDED
            }
            else -> error(name)
        } }
        val repository = stub<ConversationStore> { name, args -> when (name) {
            "getState" -> conversations
            "configure" -> { appliedConfigs += args[1] as NextTurnConfig; args[0] as String }
            "create" -> { newSessionCreated = true; ConversationId("new") }
            "createInProject" -> { projectConfig = args[0] as NextTurnConfig; "project-created" }
            "editDraft" -> Draft(text = args[1] as String)
            "prepareTurn" -> PrepareTurnResult.Queued(TurnId("queued"))
            "saveProject" -> {
                val project = args[0] as Project
                savedProjects += project
                fun apply(result: OperationResult): OperationResult {
                    if (result == OperationResult.Done) conversations.value = conversations.value.copy(projects = conversations.value.projects.filter { it.name != project.name } + project)
                    return result
                }
                val gate = projectReply
                if (gate == null) apply(OperationResult.Done) else {
                    @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
                    scope.launch { try { continuation.resume(apply(gate.await())) } catch (e: Exception) { continuation.resumeWithException(e) } }
                    COROUTINE_SUSPENDED
                }
            }
            else -> error(name)
        } }
        return ConversationViewModel(ConversationUseCases(repository, stub<ExecutionPort> { name, _ -> error(name) }, system, { "id" }, scope,
            stub<PreferencePort> { name, _ -> error(name) })).also { store.put("vm", it) }
    }
    private fun gatewayProfiles() = listOf(
        GatewayProfile(AgentId.PI, "PI", 1, "https://example.test/v1", "pi-model", "RESPONSES", true,
            listOf(GatewayModel("pi-model", "pi-model"))),
        GatewayProfile(AgentId.CODEX, "CODEX", 1, "https://example.test/v1", "model", "RESPONSES", true,
            listOf(GatewayModel("model", "model"))),
        GatewayProfile(AgentId.CLAUDE_CODE, "CLAUDE", 1, "https://example.test/v1", "claude-fixture", "MESSAGES", true,
            listOf(GatewayModel("claude-fixture", "claude-fixture"))))
    @After fun cleanup() { compose.runOnIdle { store.clear(); scope.cancel() } }
    @Test fun `opening a new session is not blocked by another gateway read`() {
        val vm = vm()
        compose.waitForIdle()
        Assert.assertTrue(vm.gatewaysLoaded.value)
        gatewayReadGate = CompletableDeferred()
        compose.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { config, _ -> vm.enqueue { vm.actions.create(config) } }) } }
        compose.onNodeWithText("创建").performClick()
        compose.waitForIdle()
        Assert.assertTrue(newSessionCreated)
        Assert.assertEquals(1, gatewayReads)
    }
    @Test fun `the connect guide is offered only after connections were read and none exist`() {
        val codex = GatewayProfile(AgentId.CODEX, "CODEX", 1, "https://example.test/v1", "model", "RESPONSES", true)
        Assert.assertEquals("new", newConversationDialog(connectionsRead = false, connections = emptyList()))
        Assert.assertEquals("gateway-intro", newConversationDialog(connectionsRead = true, connections = emptyList()))
        Assert.assertEquals("new", newConversationDialog(connectionsRead = true, connections = listOf(codex)))
    }
    @Test fun `agents still being verified keep their connections out of the connect guide`() {
        agentOptions = AgentId.entries.map { AgentOption(it, emptyMap(), "运行环境尚未就绪", false, emptySet()) }
        val vm = vm()
        compose.waitUntil { vm.connectionsRead.value }
        Assert.assertTrue(vm.connections.value.isEmpty())
        Assert.assertEquals("new", newConversationDialog(vm.connectionsRead.value, vm.configuredConnections.value))
        compose.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { _, _ -> }) } }
        compose.onNodeWithText("运行环境尚未就绪").assertExists()
        compose.onNodeWithText("这个 Agent 暂无可用连接", substring = true).assertDoesNotExist()
    }
    @Test fun `new conversation opened before connections are read waits instead of asking to connect`() {
        gatewayReadGate = CompletableDeferred()
        val vm = vm()
        compose.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { _, _ -> }) } }
        compose.onNodeWithText("正在读取连接…").assertExists()
        compose.onNodeWithText("这个 Agent 暂无可用连接", substring = true).assertDoesNotExist()
        compose.onNodeWithText("创建").assertIsNotEnabled()
        compose.runOnIdle { gatewayReadGate?.complete(Unit) }
        compose.waitUntil { vm.connectionsRead.value }
        compose.onNodeWithText("正在读取连接…").assertDoesNotExist()
        compose.onNodeWithText("Claude Code").assertExists()
        compose.onNodeWithText("创建").assertIsEnabled()
    }
    @Test fun `new conversation defaults to Pi even when the current conversation uses Codex`() {
        gatewayDefault = null
        val vm = vm()
        compose.waitForIdle()
        var submitted: NextTurnConfig? = null
        val current = Conversation(ConversationId("current"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        compose.setContent { MaterialTheme { ConfigDialog(vm, current, {}, { config, _ -> submitted = config }) } }
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals(AgentId.PI, submitted?.agent)
        Assert.assertEquals("PI", submitted?.gatewayProfile)
        Assert.assertEquals(AgentId.CODEX, current.config.agent)
    }
    @Test fun `new conversation uses the selected default gateway instead of current conversation`() {
        gatewayDefault = AgentId.CLAUDE_CODE
        val vm = vm()
        compose.waitForIdle()
        var submitted: NextTurnConfig? = null
        val current = Conversation(ConversationId("current"), NextTurnConfig(AgentId.CODEX, "old", null, "default", "CODEX"))
        compose.setContent { MaterialTheme { ConfigDialog(vm, current, {}, { config, _ -> submitted = config }) } }
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals(AgentId.CLAUDE_CODE, submitted?.agent)
        Assert.assertEquals("CLAUDE", submitted?.gatewayProfile)
        Assert.assertEquals("claude-fixture", submitted?.model)
        compose.onNodeWithText("AI 服务与 Agent").assertDoesNotExist()
        compose.onNodeWithText("模型").assertDoesNotExist()
    }
    @Test fun `applied agent selection is remembered for new conversations after reopening`() {
        gatewayDefault = AgentId.PI
        val vm = vm()
        val menuOpen = mutableStateOf(true)
        val showDialog = mutableStateOf(false)
        val current = Conversation(ConversationId("current"), NextTurnConfig(AgentId.PI, "pi-model", null, "default", "PI"))
        var dialogVm = vm
        var submitted: NextTurnConfig? = null
        compose.setContent { MaterialTheme {
            if (menuOpen.value) AgentConfigMenu(true, { menuOpen.value = false }, current, vm)
            if (showDialog.value) ConfigDialog(dialogVm, current, {}, { config, _ -> submitted = config })
        } }
        compose.onNodeWithText("Claude Code").performClick()
        compose.onNodeWithText("应用到下一轮").performClick()
        compose.waitForIdle()
        Assert.assertEquals(AgentId.CLAUDE_CODE, gatewayDefault)
        compose.runOnIdle { dialogVm = vm(); showDialog.value = true }
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals(AgentId.CLAUDE_CODE, submitted?.agent)
        dialogVm.sendInProject("Project A", "test project message", {})
        compose.waitForIdle()
        Assert.assertEquals(AgentId.CLAUDE_CODE, projectConfig?.agent)
        Assert.assertEquals("CLAUDE", projectConfig?.gatewayProfile)
    }
    @Test fun `agent chosen when creating a conversation is remembered for the next creation`() {
        gatewayDefault = AgentId.PI
        val vm = vm()
        val showDialog = mutableStateOf(true)
        var submitted: NextTurnConfig? = null
        compose.setContent { MaterialTheme {
            if (showDialog.value) ConfigDialog(vm, null, {}, { config, _ -> submitted = config; showDialog.value = false })
        } }
        compose.onNodeWithText("Codex").performScrollTo().performClick()
        compose.onNodeWithText("创建").performClick()
        compose.waitForIdle()
        Assert.assertEquals(AgentId.CODEX, gatewayDefault)
        compose.runOnIdle { submitted = null; showDialog.value = true }
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals(AgentId.CODEX, submitted?.agent)
    }
    @Test fun `unconfirmed agent selection does not replace the remembered choice`() {
        gatewayDefault = AgentId.PI
        val vm = vm()
        val showDialog = mutableStateOf(true)
        var submitted: NextTurnConfig? = null
        compose.setContent { MaterialTheme {
            if (showDialog.value) ConfigDialog(vm, null, {}, { config, _ -> submitted = config })
        } }
        compose.onNodeWithText("Codex").performScrollTo().performClick()
        compose.runOnIdle { showDialog.value = false }
        compose.waitForIdle()
        Assert.assertEquals(AgentId.PI, gatewayDefault)
        compose.runOnIdle { showDialog.value = true }
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals(AgentId.PI, submitted?.agent)
    }
    @Test fun `switching agents reuses that agents most recent gateway and model`() {
        val current = Conversation(ConversationId("current"), NextTurnConfig(AgentId.CODEX, "codex-old", null, "default", "CODEX"), updatedAt = 20)
        val recentClaude = Conversation(ConversationId("claude"), NextTurnConfig(AgentId.CLAUDE_CODE, "claude-last", "high", "second", "CLAUDE"), updatedAt = 30)
        val profiles = listOf(
            GatewayProfile(AgentId.CODEX, "CODEX", 1, "https://example.test/v1", "codex-default", "RESPONSES", true),
            GatewayProfile(AgentId.CLAUDE_CODE, "CLAUDE", 1, "https://example.test/v1", "claude-default", "MESSAGES", true,
                listOf(GatewayModel("claude-default", "Default"), GatewayModel("claude-last", "Last"))),
        )
        val previous = ConversationGatewayResolver.newConversation(AgentId.CLAUDE_CODE, current, listOf(ConversationSummary(recentClaude)), profiles, GatewayDefault(AgentId.CODEX, "CODEX", 1))
        Assert.assertEquals("CLAUDE", previous?.gatewayProfile)
        Assert.assertEquals("claude-last", previous?.model)
        Assert.assertEquals("high", previous?.reasoning)
        val firstUse = ConversationGatewayResolver.newConversation(AgentId.OPEN_CODE, current, emptyList(), profiles, GatewayDefault(AgentId.CODEX, "CODEX", 1))
        Assert.assertNull(firstUse)
    }
    @Test fun `new conversation falls back to selected gateway default when recent model is unavailable`() {
        val recent = Conversation(ConversationId("recent"), NextTurnConfig(AgentId.CODEX, "old-model", null, "default", "OLD"), updatedAt = 40)
        val profiles = listOf(
            GatewayProfile(AgentId.CODEX, "OLD", 1, "https://old.test/v1", "old-model", "RESPONSES", true),
            GatewayProfile(AgentId.CODEX, "NEW", 2, "https://new.test/v1", "new-default", "RESPONSES", true,
                listOf(GatewayModel("new-default", "Default"), GatewayModel("other", "Other"))),
        )
        val config = ConversationGatewayResolver.newConversation(AgentId.CODEX, recent, listOf(ConversationSummary(recent)), profiles,
            GatewayDefault(AgentId.CODEX, "NEW", 2))
        Assert.assertEquals("NEW", config?.gatewayProfile)
        Assert.assertEquals("new-default", config?.model)
        Assert.assertEquals(2L, config?.gatewayVersion)
    }
    @Test fun `switching conversations repairs missing local gateway and stale model but keeps valid choices`() {
        val remote = GatewayProfile(AgentId.CODEX, "REMOTE", 4, "https://remote.test/v1", "remote-default", "RESPONSES", true,
            listOf(GatewayModel("remote-default", "Default"), GatewayModel("remote-other", "Other")))
        val local = GatewayProfile(AgentId.CODEX, "LOCAL", 7, "http://127.0.0.1:11435/v1", "qwen", "RESPONSES", true,
            listOf(GatewayModel("qwen", "Qwen")), temporary = true)
        val selected = GatewayDefault(AgentId.CODEX, "REMOTE", 4)
        val missing = NextTurnConfig(AgentId.CODEX, "old-qwen", null, "default", "LOCAL", 6)
        Assert.assertEquals("remote-default", ConversationGatewayResolver.repair(missing, listOf(remote), selected)?.model)
        Assert.assertEquals("REMOTE", ConversationGatewayResolver.repair(missing, listOf(remote), selected)?.gatewayProfile)
        val stale = NextTurnConfig(AgentId.CODEX, "old-qwen", "high", "default", "LOCAL", 6)
        Assert.assertEquals(NextTurnConfig(AgentId.CODEX, "qwen", null, "default", "LOCAL", 7),
            ConversationGatewayResolver.repair(stale, listOf(local, remote), selected))
        val valid = NextTurnConfig(AgentId.CODEX, "remote-other", null, "default", "REMOTE", 4)
        Assert.assertNull(ConversationGatewayResolver.repair(valid, listOf(remote, local), selected))
    }
    @Test fun `switching agent keeps the conversation and explains separate sessions`() {
        val vm = vm()
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"), hasTurns = true, session = "codex-session")
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText("新建并使用此配置").assertDoesNotExist()
        compose.onNodeWithText("Codex").assertExists()
        compose.onNodeWithText("Claude Code").performClick()
        compose.onNodeWithText("仍在当前对话中继续", substring = true).assertExists()
        compose.onNodeWithText("应用到下一轮").assertExists()
    }
    @Test fun `agent menu shows model and reasoning directly and opens models in place`() {
        val vm = vm()
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText("模型与思考程度").assertDoesNotExist()
        compose.onNodeWithText("连接来源").assertDoesNotExist()
        compose.onNodeWithText("model").assertExists()
        compose.onNodeWithText("当前模型未开放调整").assertExists()
        compose.onNodeWithText("管理登录与 API").assertExists()
        compose.onNodeWithText("API · example.test").assertDoesNotExist()
        compose.onNodeWithText("模型").performClick()
        compose.onNodeWithText("选择模型").assertExists()
        compose.onNodeWithText("API · example.test").assertExists()
        compose.onNodeWithText("Codex").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("Codex").assertExists()
        compose.onNodeWithText("选择模型").assertDoesNotExist()
    }
    @Test fun `apply appears only while the selection differs from the conversation`() {
        val vm = vm()
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX", 1))
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText("应用到下一轮").assertDoesNotExist()
        compose.onNodeWithText("Claude Code").performClick()
        compose.onNodeWithText("应用到下一轮").assertExists()
        compose.onNodeWithText("Codex").performClick()
        compose.onNodeWithText("应用到下一轮").assertDoesNotExist()
    }
    @Test fun `models are grouped by connection and picking one also picks its connection`() {
        val vm = vm()
        compose.waitForIdle()
        val second = GatewayProfile(AgentId.CODEX, "SECOND", 2, "https://second.test/v1", "second-default", "RESPONSES", true,
            listOf(GatewayModel("second-default", "Second default"), GatewayModel("second-other", "Second other")))
        compose.runOnIdle { vm.connections.value = vm.connections.value + second }
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX", 1))
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText("模型").performClick()
        compose.onNodeWithText("API · example.test").assertExists()
        compose.onNodeWithText("API · second.test").assertExists()
        compose.onNodeWithText("model").assertIsSelected()
        compose.onNodeWithText("Second other").assertIsNotSelected().performClick()
        compose.onNodeWithText("选择模型").assertDoesNotExist()
        compose.onNodeWithText("Second other").assertExists()
        compose.onNodeWithText("应用到下一轮").performClick()
        compose.waitForIdle()
        Assert.assertEquals(listOf(NextTurnConfig(AgentId.CODEX, "second-other", null, "default", "SECOND", 2)), appliedConfigs)
    }
    @Test fun `a connection with an empty catalog can still use its default model`() {
        val vm = vm()
        compose.waitForIdle()
        val uncatalogued = GatewayProfile(AgentId.CODEX, "BARE", 5, "https://bare.test/v1", "bare-default", "RESPONSES", true)
        compose.runOnIdle { vm.connections.value = vm.connections.value + uncatalogued }
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX", 1))
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText("模型").performClick()
        compose.onNodeWithText("bare-default").performClick()
        compose.onNodeWithText("应用到下一轮").performClick()
        compose.waitForIdle()
        Assert.assertEquals(listOf(NextTurnConfig(AgentId.CODEX, "bare-default", null, "default", "BARE", 5)), appliedConfigs)
    }
    @Test fun `an agent without a connection offers only the way to connect`() {
        val vm = vm()
        compose.waitForIdle()
        compose.runOnIdle { vm.connections.value = vm.connections.value.filterNot { it.agent == AgentId.CODEX } }
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX", 1))
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText("这个 Agent 暂无可用连接", substring = true).assertExists()
        compose.onNodeWithText("管理登录与 API").assertExists()
        compose.onNodeWithText("模型").assertDoesNotExist()
        compose.onNodeWithText("思考程度").assertDoesNotExist()
    }
    @Test fun `a conversation whose connection is gone can pick a model from the remaining ones`() {
        val vm = vm()
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "old", null, "default", "REMOVED", 1))
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText("暂无可用连接").assertExists()
        compose.onNodeWithText("应用到下一轮").assertDoesNotExist()
        compose.onNodeWithText("模型").performClick()
        compose.onNodeWithText("model").performClick()
        compose.onNodeWithText("应用到下一轮").performClick()
        compose.waitForIdle()
        Assert.assertEquals(listOf(NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX", 1)), appliedConfigs)
    }
    @Test fun `agent tiles share one width set by the longest name`() {
        Assert.assertEquals(133, agentTileWidth(listOf(10, 40, 80), 400, 12))
        Assert.assertEquals(92, agentTileWidth(listOf(10, 40, 80), 220, 12))
    }
    @Test fun `agent tiles are equally wide whatever their names`() {
        compose.setContent { MaterialTheme { AgentTiles(AgentId.entries, AgentId.PI) {} } }
        val widths = AgentId.entries.map { compose.onNodeWithText(it.label()).fetchSemanticsNode().size.width }
        Assert.assertEquals(setOf(widths.first()), widths.toSet())
    }
    @Test fun `agent tiles scroll sideways instead of offering arrow buttons`() {
        compose.setContent { MaterialTheme { Box(Modifier.width(48.dp)) { AgentTiles(AgentId.entries, AgentId.PI) {} } } }
        compose.onNodeWithContentDescription("查看后面的 Agent").assertDoesNotExist()
        compose.onNodeWithText("OpenCode").assertIsNotDisplayed()
        repeat(6) { compose.onNode(hasScrollAction()).performTouchInput { swipeLeft() } }
        compose.onNodeWithText("OpenCode").assertIsDisplayed()
    }
    @Test fun `new conversation shows agents as tiles and picks a project in place`() {
        gatewayDefault = AgentId.PI
        val vm = vm()
        compose.waitForIdle()
        var submitted: Pair<NextTurnConfig, String?>? = null
        compose.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { config, project -> submitted = config to project }) } }
        compose.onNodeWithText("Pi").assertIsSelected()
        compose.onNodeWithText("Claude Code").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("默认工作区（无项目）").assertExists()
        compose.onNodeWithText("Project A").assertDoesNotExist()
        compose.onNodeWithText("项目").performClick()
        compose.onNodeWithText("选择项目").assertExists()
        compose.onNodeWithText("Pi").assertDoesNotExist()
        compose.onNodeWithText("Project A").performClick()
        compose.onNodeWithText("选择项目").assertDoesNotExist()
        compose.onNodeWithText("Project A").assertExists()
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals(AgentId.CLAUDE_CODE, submitted?.first?.agent)
        Assert.assertEquals("Project A", submitted?.second)
    }
    @Test fun `reasoning levels share the row equally`() {
        val vm = vm()
        compose.waitForIdle()
        val route = AgentLogin(AgentId.CODEX, "fixture", "Account", 3, listOf(GatewayModel("native", "Native")),
            reasoningByModel = mapOf("native" to setOf("low", "medium", "high"))).route()
        compose.runOnIdle { vm.connections.value = vm.connections.value.filterNot { it.agent == route.agent } + route }
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "native", null, "default", route.id, 3))
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText("默认").assertIsSelected()
        val widths = listOf("默认", "低", "中", "高").map { label ->
            compose.onNodeWithText(label).fetchSemanticsNode().size.width
        }
        Assert.assertEquals(setOf(widths.first()), widths.toSet())
        compose.onNodeWithText("高").performClick().assertIsSelected()
        compose.onNodeWithText("应用到下一轮").performClick()
        compose.waitForIdle()
        Assert.assertEquals(listOf(NextTurnConfig(AgentId.CODEX, "native", "high", "default", route.id, 3)), appliedConfigs)
    }
    @Test fun `reasoning levels too long for the row open as a list`() {
        val long = "a-reasoning-level-whose-raw-name-is-far-too-long-to-share-one-row-with-the-others"
        val vm = vm()
        compose.waitForIdle()
        val route = AgentLogin(AgentId.CODEX, "fixture", "Account", 3, listOf(GatewayModel("native", "Native")),
            reasoningByModel = mapOf("native" to setOf("low", long))).route()
        compose.runOnIdle { vm.connections.value = vm.connections.value.filterNot { it.agent == route.agent } + route }
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "native", null, "default", route.id, 3))
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText(long).assertDoesNotExist()
        compose.onNodeWithText("思考程度").performClick()
        compose.onNodeWithText(long).performClick()
        compose.onNodeWithText(long).assertExists()
        compose.onNodeWithText("Codex").assertExists()
        compose.onNodeWithText("应用到下一轮").performClick()
        compose.waitForIdle()
        Assert.assertEquals(listOf(NextTurnConfig(AgentId.CODEX, "native", long, "default", route.id, 3)), appliedConfigs)
    }

    @Test fun `icon selection and account model edits are saved only on apply`() {
        val vm = vm()
        compose.waitForIdle()
        val route = AgentLogin(
            AgentId.CLAUDE_CODE, "fixture", "Account", 3,
            listOf(GatewayModel("native-first", "First"), GatewayModel("native-other", "Other")),
            reasoningByModel = mapOf("native-other" to setOf("high")),
        ).route()
        compose.runOnIdle { vm.connections.value = vm.connections.value.filterNot { it.agent == route.agent } + route }
        val menuOpen = mutableStateOf(true)
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "second", "CODEX", 1))
        compose.setContent { MaterialTheme { AgentConfigMenu(menuOpen.value, { menuOpen.value = false }, conversation, vm) } }

        compose.onNodeWithText("Claude Code").performClick().assertIsSelected()
        compose.onNodeWithText("First").assertExists()
        compose.runOnIdle { Assert.assertTrue(appliedConfigs.isEmpty()) }
        compose.runOnIdle { menuOpen.value = false }
        compose.onNodeWithText("应用到下一轮").assertDoesNotExist()
        compose.runOnIdle { menuOpen.value = true }
        compose.onNodeWithText("Codex").assertIsSelected()
        compose.onNodeWithText("Claude Code").performClick()
        compose.onNodeWithText("模型").performClick()
        compose.onNodeWithText("claude-fixture").assertDoesNotExist()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText("高").performClick().assertIsSelected()
        compose.runOnIdle { Assert.assertTrue(appliedConfigs.isEmpty()) }
        compose.onNodeWithText("应用到下一轮").performClick()
        compose.waitForIdle()
        Assert.assertEquals(listOf(NextTurnConfig(AgentId.CLAUDE_CODE, "native-other", "high", "second", route.id, 3)), appliedConfigs)
    }
}
