package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillsPageTest {
    @get:Rule val compose = createComposeRule()
    private val user = Skill("skill:user", AgentId.CODEX, "review-plan", "检查改动", "用户技能", true, null)
    private val builtin = Skill("skill:cli", AgentId.CODEX, "skill-creator", "创建技能", "CLI 内置", true, null)
    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args ?: emptyArray()) } as T

    @Test fun `catalog cards filter by source and 使用 joins the draft`() {
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        val interaction = MutableStateFlow(InteractionState(loading = false, selected = ConversationDetail(conversation, emptyList())))
        val written = mutableListOf<Triple<String, String, Boolean>>()
        val system = stub<SystemPort> { name, _ -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "skills" -> DataResult.Loaded(listOf(user, builtin))
            "readSkill" -> DataResult.Loaded(SkillContent(user.name, user.description, "body", "markdown", emptyList()))
            else -> error(name)
        } }
        val repository = stub<InteractionRepository> { name, args -> when {
            name == "getState" -> interaction
            name.startsWith("setSkill") -> {
                written += Triple(args[0].toString(), args[1] as String, args[2] as Boolean)
                val refs = if (args[2] as Boolean) conversation.draft.capabilities + (args[1] as String) else conversation.draft.capabilities - (args[1] as String)
                val updated = interaction.value.selected!!.conversation.copy(draft = conversation.draft.copy(capabilities = refs))
                interaction.value = interaction.value.copy(selected = ConversationDetail(updated, emptyList()))
                Unit
            }
            else -> error(name)
        } }
        val vm = ConversationViewModel(InteractionUseCases(repository, stub { name, _ -> error(name) }, system, { "id" }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), stub { name, _ -> error(name) }))
        compose.setContent { MaterialTheme { SkillsPage(vm, {}, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("review-plan").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已添加").assertIsDisplayed()
        compose.onNodeWithText("skill-creator").assertIsDisplayed()
        compose.onNodeWithText("CLI 内置").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("review-plan").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("skill-creator").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("用户技能").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("skill-creator").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("review-plan").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("使用").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { written.isNotEmpty() }
        assertEquals(listOf(Triple("c", user.ref, true)), written)
        compose.onNodeWithText("移除").assertIsDisplayed()
        compose.onNodeWithText("技能详情").assertDoesNotExist()
    }
}
