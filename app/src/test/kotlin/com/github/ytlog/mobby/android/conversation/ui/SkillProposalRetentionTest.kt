package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.gateway.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillProposalRetentionTest {
    @get:Rule val compose = createComposeRule()
    private inline fun <reified T> stub(crossinline result: (String) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> result(method.name) } as T
    @Test fun `open proposal keeps user edits but disables save when its source expires`() {
        val available = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        val repository = stub<ConversationStore> { if (it == "getState") MutableStateFlow(ConversationState()) else error(it) }
        val system = stub<SystemPort> { when (it) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "previewSkill" -> DataResult.Loaded(SkillContent("fixture", "fixture", "body", "body", emptyList()))
            else -> error(it)
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val actions = ConversationUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "id" }, scope, stub<PreferencePort> { error(it) })
        val store = ViewModelStore()
        val vm = ConversationViewModel(actions)
        store.put("vm", vm)
        try {
            compose.setContent { MaterialTheme { if (mounted.value) SkillProposalDialog(SkillProposal("fixture/0", "original", AgentId.CODEX), vm, available.value, vm::dismissSkillProposal) } }
            compose.onNode(hasSetTextAction()).performTextReplacement("my edited draft")
            compose.onNodeWithText("校验").performClick()
            compose.onNodeWithText("保存技能").assertIsEnabled()
            compose.runOnIdle { mounted.value = false }
            compose.onNodeWithText("my edited draft").assertDoesNotExist()
            compose.runOnIdle { mounted.value = true }
            compose.onNodeWithText("my edited draft").assertExists()
            compose.onNode(hasSetTextAction()).performTextInputSelection(androidx.compose.ui.text.TextRange(3, 7))
            compose.onNodeWithText("返回，稍后处理").performClick()
            compose.onNodeWithText("my edited draft").assertDoesNotExist()
            compose.runOnIdle { vm.openSkillProposal(SkillProposal("fixture/0", "original", AgentId.CODEX)) }
            compose.onNodeWithText("my edited draft").assertExists()
            Assert.assertEquals(androidx.compose.ui.text.TextRange(3, 7), vm.skillProposal.value!!.value.selection)
            compose.runOnIdle { available.value = false }
            compose.onNodeWithText("保存技能").assertIsNotEnabled()
            compose.onNodeWithText("my edited draft").assertExists()
            compose.onNodeWithText("草稿来源暂不可用，编辑内容仍保留").assertExists()
        } finally { compose.runOnIdle { store.clear(); scope.cancel() } }
    }
    @Test fun `validation and save survive recreation without duplicate requests or blocking stop`() {
        val mounted = mutableStateOf(true)
        val consumeSuccess = mutableStateOf(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val previewReply = CompletableDeferred<DataResult<SkillContent>>()
        val saveReplies = Channel<DataResult<Skill>>(2)
        val submitted = mutableListOf<String>()
        val navigation = mutableListOf<Long>()
        var previews = 0
        var stops = 0
        fun suspendReply(args: Array<out Any?>, reply: suspend () -> Any?): Any {
            @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
            scope.launch { try { continuation.resume(reply()) } catch (e: Exception) { continuation.resumeWithException(e) } }
            return COROUTINE_SUSPENDED
        }
        val repository = Proxy.newProxyInstance(ConversationStore::class.java.classLoader, arrayOf(ConversationStore::class.java)) { _, method, args -> when (method.name) {
            "getState" -> MutableStateFlow(ConversationState())
            "saveSkillProposal" -> { submitted += args[1] as String; suspendReply(args) { saveReplies.receive() } }
            else -> error(method.name)
        } } as ConversationStore
        val system = Proxy.newProxyInstance(SystemPort::class.java.classLoader, arrayOf(SystemPort::class.java)) { _, method, args -> when (method.name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "previewSkill" -> {
                previews++
                if (previews == 1) suspendReply(args) { previewReply.await() }
                else DataResult.Loaded(SkillContent("fixture", "fixture", "body", args[0] as String, emptyList()))
            }
            else -> error(method.name)
        } } as SystemPort
        val execution = object : ExecutionPort {
            override suspend fun submit(turn: TurnExecution): Submission = error("unused")
            override suspend fun insert(insertion: PreparedInsertion): Submission = error("unused")
            override suspend fun lookup(turnId: TurnId): Submission = error("unused")
            override suspend fun cancel(executionId: ExecutionId): StopResult { stops++; return StopResult.Accepted }
            override fun observe(executionId: ExecutionId): Flow<ExecutionFact> = emptyFlow()
        }
        val actions = ConversationUseCases(repository, execution, system, { "id" }, scope, stub<PreferencePort> { error(it) })
        val store = ViewModelStore()
        val vm = ConversationViewModel(actions).also { store.put("vm", it) }
        try {
            compose.setContent {
                val editor by vm.skillProposal.collectAsState()
                val saved by vm.skillProposalSaved.collectAsState()
                if (mounted.value) MaterialTheme {
                    editor?.let { SkillProposalDialog(it.proposal, vm, true, vm::dismissSkillProposal) }
                    LaunchedEffect(saved?.operation, consumeSuccess.value) {
                        if (consumeSuccess.value) saved?.let { navigation += it.operation; vm.consumeSkillProposalSaved(it.operation) }
                    }
                }
            }
            compose.runOnIdle { vm.openSkillProposal(SkillProposal("fixture/0", "original", AgentId.CODEX)) }
            compose.onNodeWithText("校验").assertIsNotEnabled()
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnIdle { mounted.value = true }
            compose.onNodeWithText("校验").assertIsNotEnabled()
            Assert.assertEquals(1, previews)
            compose.runOnIdle { previewReply.complete(DataResult.Loaded(SkillContent("fixture", "fixture", "body", "original", emptyList()))) }
            compose.onNode(hasSetTextAction()).performTextReplacement("edited draft")
            compose.onNodeWithText("校验").performClick()
            compose.onNodeWithText("保存技能").performClick()
            compose.runOnIdle { vm.saveSkillProposal(); vm.stop(ExecutionId("running")) }
            compose.waitUntil(5000) { submitted.size == 1 && stops == 1 }
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnIdle { mounted.value = true }
            compose.onNodeWithText("保存技能").assertIsNotEnabled()
            compose.onNodeWithText("edited draft").assertExists()
            compose.runOnIdle { saveReplies.trySend(DataResult.Failed("保存失败")) }
            compose.onNodeWithText("保存失败").assertExists()
            compose.onNodeWithText("保存技能").performClick()
            compose.waitUntil(5000) { submitted.size == 2 }
            compose.runOnIdle {
                mounted.value = false
                saveReplies.trySend(DataResult.Loaded(Skill("saved", AgentId.CODEX, "fixture", "fixture", "local", true, null)))
            }
            compose.waitUntil(5000) { vm.skillProposalSaved.value != null }
            Assert.assertNull(vm.skillProposal.value)
            compose.runOnIdle { consumeSuccess.value = true; mounted.value = true }
            compose.waitForIdle()
            compose.waitUntil(5000) { navigation.size == 1 }
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnIdle { mounted.value = true }
            compose.waitForIdle()
            Assert.assertEquals(1, navigation.size)
            Assert.assertEquals(listOf("edited draft", "edited draft"), submitted)
        } finally { compose.runOnIdle { store.clear(); scope.cancel() } }
    }

}
