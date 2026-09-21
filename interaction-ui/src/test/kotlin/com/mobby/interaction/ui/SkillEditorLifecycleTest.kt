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
class SkillEditorLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ViewModelStore()
    private var stops = 0
    private var saves = 0
    private var failPreview = false
    private var previewReply: CompletableDeferred<DataResult<SkillContent>>? = null
    private val importReply = CompletableDeferred<DataResult<SkillContent>>()
    private val saveReply = CompletableDeferred<DataResult<Skill>>()
    private fun content() = SkillContent("fixture", "description", "body", "markdown", emptyList())
    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args ?: emptyArray()) } as T
    private fun deferred(args: Array<out Any?>, block: suspend () -> Any?): Any {
        @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
        scope.launch { try { continuation.resume(block()) } catch (e: Exception) { continuation.resumeWithException(e) } }
        return COROUTINE_SUSPENDED
    }
    private fun vm(): ConversationViewModel {
        val repo = stub<InteractionRepository> { name, _ -> if (name == "getState") MutableStateFlow(InteractionState()) else error(name) }
        val system = stub<SystemPort> { name, args -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "skills" -> DataResult.Loaded(emptyList<Skill>())
            "previewManualSkill" -> {
                if (failPreview) throw java.io.IOException("fixture failure")
                previewReply?.let { reply -> deferred(args) { reply.await() } } ?: DataResult.Loaded(content())
            }
            "readSkillImport" -> deferred(args) { importReply.await() }
            "saveManualSkill" -> { saves++; deferred(args) { saveReply.await() } }
            else -> error(name)
        } }
        val execution = object : ExecutionPort {
            override suspend fun submit(turn: TurnExecution): Submission = error("unused")
            override suspend fun lookup(turnId: TurnId): Submission = error("unused")
            override suspend fun cancel(executionId: ExecutionId): StopResult { stops++; return StopResult.Accepted }
            override fun observe(executionId: ExecutionId): Flow<ExecutionFact> = emptyFlow()
        }
        return ConversationViewModel(InteractionUseCases(repo, execution, system, { "id" }, scope, stub<PreferencePort> { name, _ -> error(name) })).also { store.put("vm", it) }
    }
    private fun manual(name: String) {
        compose.onNodeWithContentDescription("添加技能").performClick()
        compose.onNodeWithText("手动创建").performClick()
        compose.onNodeWithText("名称（小写英文、数字、连字符）").performTextReplacement(name)
        compose.onNodeWithText("用途与使用场景").performTextReplacement("description")
        compose.onNodeWithText("执行步骤与要求").performTextReplacement("body")
    }
    @After fun cleanup() { compose.runOnIdle { store.clear(); scope.cancel() } }
    @Test fun `pending save does not block stop and recreated page returns to list on success`() {
        val vm = vm()
        val restore = StateRestorationTester(compose)
        restore.setContent { MaterialTheme { SkillsPage(vm, {}, {}) } }
        manual("fixture")
        compose.onNodeWithText("校验并预览").performScrollTo().performClick()
        compose.onNodeWithText("保存技能").performScrollTo().performClick()
        compose.runOnIdle { vm.stop(ExecutionId("running")) }
        compose.waitUntil(3000) { saves == 1 && stops == 1 }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("保存技能").assertIsNotEnabled()
        compose.runOnIdle { saveReply.complete(DataResult.Loaded(Skill("saved", AgentId.CODEX, "fixture", "description", "用户技能", true, null))) }
        compose.onNodeWithText("搜索技能").assertExists()
        compose.onNodeWithText("保存技能").assertDoesNotExist()
        Assert.assertEquals(1, saves)
    }
    @Test fun `late validation cannot replace a newer manual draft`() {
        previewReply = CompletableDeferred()
        val vm = vm()
        compose.setContent { MaterialTheme { SkillsPage(vm, {}, {}) } }
        manual("old-draft")
        compose.onNodeWithText("校验并预览").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        manual("new-draft")
        compose.runOnIdle { previewReply!!.complete(DataResult.Loaded(content())) }
        compose.onNodeWithText("new-draft").assertExists()
        compose.onNodeWithText("保存前预览").assertDoesNotExist()
    }
    @Test fun `slow file import neither blocks stop nor replaces a newer draft`() {
        val vm = vm()
        compose.setContent { MaterialTheme { SkillsPage(vm, {}, {}) } }
        compose.runOnIdle { vm.importSkillFile(AgentId.CODEX, "content://fixture/slow.md"); vm.stop(ExecutionId("running")) }
        compose.waitUntil(3000) { stops == 1 }
        manual("new-draft")
        compose.runOnIdle { importReply.complete(DataResult.Loaded(content())) }
        compose.onNodeWithText("new-draft").assertExists()
        compose.onNodeWithText("保存前预览").assertDoesNotExist()
    }
    @Test fun `late save success leaves the new editor and draft intact`() {
        val vm = vm()
        compose.setContent { MaterialTheme { SkillsPage(vm, {}, {}) } }
        manual("old-draft")
        compose.onNodeWithText("校验并预览").performScrollTo().performClick()
        compose.onNodeWithText("保存技能").performScrollTo().performClick()
        compose.runOnIdle { vm.saveSkillEditor() }
        compose.onNodeWithContentDescription("返回").performClick()
        manual("new-draft")
        compose.runOnIdle { saveReply.complete(DataResult.Loaded(Skill("saved", AgentId.CODEX, "old-draft", "description", "用户技能", true, null))) }
        compose.onNodeWithText("new-draft").assertExists()
        Assert.assertNull(vm.skillEditorSaved.value)
        Assert.assertEquals(1, saves)
    }

    @Test fun `unexpected validation failure preserves input and permits retry`() {
        failPreview = true
        val vm = vm()
        compose.setContent { MaterialTheme { SkillsPage(vm, {}, {}) } }
        manual("my-draft")
        compose.onNodeWithText("校验并预览").performScrollTo().performClick()
        compose.onNodeWithText("校验未完成，编辑内容已保留，请重试").assertExists()
        compose.onNodeWithText("my-draft").assertExists()
        compose.onNodeWithText("校验并预览").assertIsEnabled()
        compose.runOnIdle { failPreview = false }
        compose.onNodeWithText("校验并预览").performClick()
        compose.onNodeWithText("保存技能").assertIsEnabled()
    }

    @Test fun `restored editor route with a new view model explains missing state and returns to catalog`() {
        var current = vm()
        val restore = StateRestorationTester(compose)
        restore.setContent { MaterialTheme { SkillsPage(current, {}, {}) } }
        manual("unfinished-draft")
        compose.runOnIdle { current = vm() }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("编辑状态未恢复。若刚才执行过保存，请先到技能目录核对。").assertIsDisplayed()
        compose.onNodeWithText("返回技能目录").performClick()
        compose.onNodeWithText("搜索技能").assertIsDisplayed()
        manual("new-draft")
        compose.onNodeWithText("new-draft").assertExists()
        Assert.assertEquals(0, saves)
    }

}
