package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionDecisionRetryTest {
    @get:Rule val compose = createComposeRule()
    private inline fun <reified T> stub(crossinline result: (String) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> result(method.name) } as T
    @Test fun `double tap sends once and uncertain retry reuses the original decision identity`() {
        val replies = Channel<OperationResult>(4)
        val decisions = mutableListOf<PermissionDecision>()
        val execution = object : ExecutionPort {
            override suspend fun submit(turn: TurnExecution): Submission = error("unused")
            override suspend fun insert(insertion: PreparedInsertion): Submission = error("unused")
            override suspend fun lookup(turnId: TurnId): Submission = error("unused")
            override suspend fun cancel(executionId: ExecutionId): StopResult = error("unused")
            override fun observe(executionId: ExecutionId): Flow<ExecutionFact> = emptyFlow()
            override suspend fun resolvePermission(decision: PermissionDecision): OperationResult { decisions += decision; return replies.receive() }
        }
        val repository = stub<InteractionRepository> { if (it == "getState") MutableStateFlow(InteractionState()) else error(it) }
        val system = stub<SystemPort> { when (it) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            else -> error(it)
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val actions = InteractionUseCases(repository, execution, system, { "id" }, scope, stub<PreferencePort> { error(it) })
        val store = ViewModelStore()
        lateinit var vm: ConversationViewModel
        val pending = PermissionRequest("permission", 7, PermissionSubject.FileWrite("/fixture", ""))
        val id = ExecutionId("run")
        compose.setContent { Text("fixture") }
        try {
            compose.runOnIdle { vm = ConversationViewModel(actions); store.put("vm", vm); vm.decidePermission(id, pending, true); vm.decidePermission(id, pending, true) }
            compose.waitUntil(5_000) { decisions.size == 1 }
            compose.runOnIdle { replies.trySend(OperationResult.Failed("连接中断，结果待确认")) }
            compose.waitUntil(5_000) { vm.permissionBusy.value.isEmpty() }
            compose.runOnIdle { vm.decidePermission(id, pending, false); vm.decidePermission(id, pending, true) }
            compose.waitUntil(5_000) { decisions.size == 2 }
            assertEquals(decisions.first(), decisions.last())
            compose.runOnIdle { replies.trySend(OperationResult.Done) }
            compose.waitUntil(5_000) { vm.permissionSubmitted.value.isNotEmpty() }
            compose.runOnIdle { vm.decidePermission(id, pending, true) }
            assertEquals(2, decisions.size)
        } finally { compose.runOnIdle { store.clear(); scope.cancel() } }
    }
}
