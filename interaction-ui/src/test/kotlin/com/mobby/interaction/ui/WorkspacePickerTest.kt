package com.mobby.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import com.mobby.interaction.domain.*
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
    private val created = CompletableDeferred<WorkspaceOption>()
    private var creations = 0
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
        val repository = stub<InteractionRepository> { name, _ -> if (name == "getState") MutableStateFlow(InteractionState()) else error(name) }
        return ConversationViewModel(InteractionUseCases(repository, stub<ExecutionPort> { name, _ -> error(name) }, system, { "id" }, scope,
            stub<PreferencePort> { name, _ -> error(name) })).also { store.put("vm", it) }
    }
    @After fun cleanup() { compose.runOnIdle { store.clear(); scope.cancel() } }
    @Test fun `selected workspace survives page recreation and reaches new conversation config`() {
        val vm = vm()
        var submitted: NextTurnConfig? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { submitted = it }) } }
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
        restoration.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { submitted = it }) } }
        compose.onNodeWithText("新建工作区").performScrollTo().performClick()
        compose.onNodeWithText("工作区名称").performScrollTo().performTextReplacement("New workspace")
        compose.onNodeWithText("创建工作区").performScrollTo().performClick()
        compose.runOnIdle { vm.createWorkspace("duplicate") }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("创建").assertIsNotEnabled()
        compose.runOnIdle { created.complete(WorkspaceOption("new-workspace", "New workspace")) }
        compose.onNodeWithText("创建").performClick()
        Assert.assertEquals("new-workspace", submitted?.workspace)
        Assert.assertEquals(1, creations)
    }
}
