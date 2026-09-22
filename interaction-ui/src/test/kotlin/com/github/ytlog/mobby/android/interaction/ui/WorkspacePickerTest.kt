package com.github.ytlog.mobby.android.interaction.ui

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
    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, a -> body(m.name, a ?: emptyArray()) } as T
    private fun vm(): ConversationViewModel {
        val system = stub<SystemPort> { name, args -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> listOf(AgentOption(AgentId.CLAUDE_CODE, mapOf("claude-fixture" to emptySet()), null, true, emptySet()))
            "gateways" -> emptyList<GatewayProfile>()
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
    @Test fun `selected workspace survives page recreation and reaches new conversation config`() {
        val vm = vm()
        var submitted: NextTurnConfig? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { config, _ -> submitted = config }) } }
        compose.onNodeWithText("Claude Code").performScrollTo().performClick()
        compose.onNodeWithText("claude-fixture").performScrollTo().performClick()
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
