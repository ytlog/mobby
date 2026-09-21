package com.mobby.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.mobby.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillProposalRetentionTest {
    @get:Rule val compose = createComposeRule()
    private inline fun <reified T> stub(crossinline result: (String) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> result(method.name) } as T
    @Test fun `open proposal keeps user edits but disables save when its source expires`() {
        val available = mutableStateOf(true)
        val repository = stub<InteractionRepository> { if (it == "getState") MutableStateFlow(InteractionState()) else error(it) }
        val system = stub<SystemPort> { when (it) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "previewSkill" -> DataResult.Loaded(SkillContent("fixture", "fixture", "body", "body", emptyList()))
            else -> error(it)
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "id" }, scope, stub<PreferencePort> { error(it) })
        val store = ViewModelStore()
        val vm = ConversationViewModel(actions)
        store.put("vm", vm)
        try {
            compose.setContent { MaterialTheme { SkillProposalDialog(SkillProposal("fixture/0", "original", AgentId.CODEX), vm, available.value, {}, {}) } }
            compose.onNode(hasSetTextAction()).performTextReplacement("my edited draft")
            compose.onNodeWithText("校验").performClick()
            compose.onNodeWithText("保存技能").assertIsEnabled()
            compose.runOnIdle { available.value = false }
            compose.onNodeWithText("保存技能").assertIsNotEnabled()
            compose.onNodeWithText("my edited draft").assertExists()
            compose.onNodeWithText("生成草稿已清理或来源失效，编辑内容仍保留").assertExists()
        } finally { compose.runOnIdle { store.clear(); scope.cancel() } }
    }
}
