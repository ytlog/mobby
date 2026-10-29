package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RuntimeCapabilitiesStartupTest {
    /** On the tablet the first screen asked for capabilities while the runtime was still starting and showed a failure. */
    @Test fun `capabilities read while the runtime starts report not ready instead of failing`() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val ports = AndroidRuntimePorts(context, RuntimeEnvironment(context),
                MutableStateFlow(EnvironmentSnapshot(EnvironmentPhase.INITIALIZING, "")), ProcessRegistry(context), scope)
            val result = ports.capabilities() as CapabilityResult.Available
            assertEquals(AgentId.values().toSet(), result.capabilities.agents.map { it.agentId }.toSet())
            result.capabilities.agents.forEach { agent ->
                assertEquals(ErrorCode.NOT_READY, agent.unavailableReason?.code)
                assertTrue(agent.skillCapabilities.isEmpty())
            }
        } finally { scope.cancel() }
    }
}
