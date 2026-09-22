package com.github.ytlog.mobby.android.interaction.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PluginUseCasesTest {
    private val plugin = Plugin("plugin:PHONE:ACCESSIBILITY", "使用当前手机", "读取并操作当前屏幕", true, null)
    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T

    private fun useCases(available: Boolean, refs: MutableSet<String>): InteractionUseCases {
        val current = plugin.copy(available = available, unavailableReason = if (available) null else "请先开启无障碍")
        val repository = stub<InteractionRepository> { name -> when {
            name == "getState" -> MutableStateFlow(InteractionState())
            name.startsWith("setSkill") -> { refs += "written"; Unit }
            else -> error(name)
        } }
        val system = stub<SystemPort> { name -> when (name) {
            "getStatus" -> flowOf(SystemStatus())
            "getDiagnostic" -> emptyFlow<DiagnosticOutput>()
            "plugins" -> DataResult.Loaded(listOf(current))
            else -> error(name)
        } }
        return InteractionUseCases(repository, stub { error(it) }, system, { "id" }, CoroutineScope(SupervisorJob()), stub { error(it) })
    }

    @Test fun `unavailable plugin cannot join the draft`() = runTest {
        val refs = mutableSetOf<String>()
        val result = useCases(false, refs).setPlugin(ConversationId("c"), plugin.copy(available = false, unavailableReason = "请先开启无障碍"), true)
        assertEquals(OperationResult.Failed("请先开启无障碍"), result)
        assertTrue(refs.isEmpty())
    }

    @Test fun `available plugin writes the capability ref and can be removed without rechecking`() = runTest {
        val refs = mutableSetOf<String>()
        val actions = useCases(true, refs)
        assertEquals(OperationResult.Done, actions.setPlugin(ConversationId("c"), plugin, true))
        assertEquals(setOf("written"), refs)
        refs.clear()
        assertEquals(OperationResult.Done, actions.setPlugin(ConversationId("c"), plugin.copy(available = false), false))
        assertEquals(setOf("written"), refs)
    }
}
