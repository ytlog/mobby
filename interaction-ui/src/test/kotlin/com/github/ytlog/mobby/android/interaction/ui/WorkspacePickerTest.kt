package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import com.github.ytlog.mobby.android.interaction.domain.*
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
    private val interaction = MutableStateFlow(InteractionState(loading = false, projects = listOf(Project("Project A", "second"))))
    private val created = CompletableDeferred<WorkspaceOption>()
    private var creations = 0
    private var projectReply: CompletableDeferred<OperationResult>? = null
    private val savedProjects = mutableListOf<Project>()
    private var gatewayDefault = AgentId.CODEX
    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, a -> body(m.name, a ?: emptyArray()) } as T
    private fun vm(): ConversationViewModel {
        val system = stub<SystemPort> { name, args -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> listOf(AgentOption(AgentId.CLAUDE_CODE, mapOf("claude-fixture" to emptySet()), null, true, emptySet()))
            "gateways" -> listOf(
                GatewayProfile(AgentId.CODEX, "CODEX", 1, "https://example.test/v1", "model", "RESPONSES", true),
                GatewayProfile(AgentId.CLAUDE_CODE, "CLAUDE", 1, "https://example.test/v1", "claude-fixture", "MESSAGES", true,
                    listOf(GatewayModel("claude-fixture", "claude-fixture"))))
            "defaultGateway" -> GatewayDefault(gatewayDefault, if (gatewayDefault == AgentId.CODEX) "CODEX" else "CLAUDE", 1)
            "workspaces" -> DataResult.Loaded(options.toList())
            "createWorkspace" -> {
                creations++
                @Suppress("UNCHECKED_CAST") val reply = args.last() as Continuation<Any?>
                scope.launch { try { val value = created.await(); options += value; reply.resume(DataResult.Loaded(value)) } catch (e: Exception) { reply.resumeWithException(e) } }
                COROUTINE_SUSPENDED
            }
            else -> error(name)
        } }
        val repository = stub<InteractionRepository> { name, args -> when (name) {
            "getState" -> interaction
            "saveProject" -> {
                val project = args[0] as Project
                savedProjects += project
                fun apply(result: OperationResult): OperationResult {
                    if (result == OperationResult.Done) interaction.value = interaction.value.copy(projects = interaction.value.projects.filter { it.name != project.name } + project)
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
        return ConversationViewModel(InteractionUseCases(repository, stub<ExecutionPort> { name, _ -> error(name) }, system, { "id" }, scope,
            stub<PreferencePort> { name, _ -> error(name) })).also { store.put("vm", it) }
    }
    @After fun cleanup() { compose.runOnIdle { store.clear(); scope.cancel() } }
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
        compose.onNodeWithText("网关与 Agent").assertDoesNotExist()
        compose.onNodeWithText("模型").assertDoesNotExist()
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
    @Test fun `new conversation uses selected default gateway and its default model before recent conversations`() {
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
    @Test fun `selected workspace survives page recreation and reaches new conversation config`() {
        val vm = vm()
        var submitted: NextTurnConfig? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { config, _ -> submitted = config }) } }
        compose.onNodeWithText("Claude Code").performScrollTo().performClick()
        compose.onNodeWithText("Project B").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals("second", submitted?.workspace)
        Assert.assertEquals(AgentId.CLAUDE_CODE, submitted?.agent)
        Assert.assertEquals("claude-fixture", submitted?.model)
    }
    @Test fun `workspace creation survives recreation without duplicate creation and selects its result`() {
        val vm = vm()
        var submitted: NextTurnConfig? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { config, _ -> submitted = config }) } }
        compose.onNodeWithText("新建工作区").performScrollTo().performClick()
        compose.onNodeWithText("工作区名称").performScrollTo().performTextReplacement("New workspace")
        compose.onNodeWithText("创建工作区").performScrollTo().performClick()
        compose.runOnIdle { vm.createWorkspace("duplicate", "duplicate-owner") }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("创建").assertIsNotEnabled()
        compose.runOnIdle { created.complete(WorkspaceOption("new-workspace", "New workspace")) }
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals("new-workspace", submitted?.workspace)
        Assert.assertEquals(1, creations)
    }
    @Test fun `selecting a project supplies its default and an explicit workspace choice overrides it`() {
        val vm = vm()
        var submitted: NextTurnConfig? = null
        var project: String? = null
        compose.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { config, group -> submitted = config; project = group }) } }
        compose.onNodeWithText("Project A").performScrollTo().performClick()
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals("second", submitted?.workspace)
        Assert.assertEquals("Project A", project)
        compose.onNodeWithText("默认本机工作区").performScrollTo().performClick()
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals("default", submitted?.workspace)
        Assert.assertEquals("Project A", project)
    }
    @Test fun `late workspace creation cannot change a different dialog selection`() {
        val vm = vm()
        val mounted = mutableStateOf(true)
        var submitted: NextTurnConfig? = null
        compose.setContent { if (mounted.value) MaterialTheme { ConfigDialog(vm, null, {}, { config, _ -> submitted = config }) } }
        compose.onNodeWithText("新建工作区").performScrollTo().performClick()
        compose.onNodeWithText("工作区名称").performScrollTo().performTextReplacement("New workspace")
        compose.onNodeWithText("创建工作区").performScrollTo().performClick()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        compose.runOnIdle { created.complete(WorkspaceOption("new-workspace", "New workspace")) }
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals("default", submitted?.workspace)
        Assert.assertEquals(1, creations)
    }

    @Test fun `switching agent keeps the conversation and explains separate sessions`() {
        val vm = vm()
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"), hasTurns = true, session = "codex-session")
        compose.setContent { MaterialTheme { AgentConfigMenu(true, {}, conversation, vm) } }
        compose.onNodeWithText("新建并使用此配置").assertDoesNotExist()
        compose.onNodeWithText("应用").assertExists()
        compose.onNodeWithText("Agent").assertExists()
        compose.onNodeWithText("Claude Code").performScrollTo().performClick()
        compose.onNodeWithText("仍在当前对话中继续", substring = true).assertExists()
        compose.onNodeWithText("应用").assertExists()
    }
    @Test fun `project default save survives recreation without duplicate requests`() {
        val vm = vm()
        projectReply = CompletableDeferred()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ProjectPage(vm, {}) } }
        compose.onNodeWithText("Project A").performClick()
        compose.onNodeWithText("默认本机工作区").performScrollTo().performClick()
        compose.onNodeWithText("保存项目").performClick()
        compose.runOnIdle { vm.saveProject() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("保存项目").assertIsNotEnabled()
        compose.runOnIdle { projectReply!!.complete(OperationResult.Done) }
        compose.onNodeWithText("保存项目").assertDoesNotExist()
        compose.onNodeWithText("默认工作区：默认本机工作区").assertExists()
        Assert.assertEquals(listOf(Project("Project A", "default")), savedProjects)
    }
    @Test fun `new project is saved with the selected workspace`() {
        val vm = vm()
        compose.setContent { MaterialTheme { ProjectPage(vm, {}) } }
        compose.onNodeWithText("新建项目").performClick()
        compose.onNodeWithText("项目名称").performTextReplacement("Project C")
        compose.onNodeWithText("Project B").performScrollTo().performClick()
        compose.onNodeWithText("保存项目").performClick()
        compose.onNodeWithText("Project C").assertExists()
        compose.onNodeWithText("保存项目").assertDoesNotExist()
        Assert.assertEquals(listOf(Project("Project C", "second")), savedProjects)
    }

}
