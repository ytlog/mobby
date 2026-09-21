package com.mobby.runtime.android

import com.mobby.runtime.api.*
import com.mobby.runtime.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RuntimeServiceAdmissionTest {
    private inline fun <reified T> unusedPort(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
        error("No native runtime operation expected")
    } as T

    @Test fun `environment recheck waits for admission and rejects a newly active task`() = runBlocking {
        val controller = Robolectric.buildService(RuntimeService::class.java)
        val service = controller.get()
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.deleteDatabase("runtime-journal.db")
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        RuntimeJournal(context).use { journal ->
            val coordinator = RunCoordinator(worker, unusedPort<EnvironmentPort>(), unusedPort<ProcessPort>(), journal, OutputStore(context))
            coordinator.recover()
            RuntimeService::class.java.getDeclaredField("coordinator").apply { isAccessible = true }.set(service, coordinator)
            val admission = RuntimeService::class.java.getDeclaredField("submission").apply { isAccessible = true }.get(service) as Mutex
            admission.lock()
            val recheck = async(start = CoroutineStart.UNDISPATCHED) { service.initialize() }
            try {
                val request = RunRequest(RequestId("fixture"), AgentId.CLAUDE_CODE, WorkspaceRef("fixture"), emptyList(), "model", GatewayProfileRef("CLAUDE", 0))
                assertEquals(SubmitResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true)),
                    withTimeout(1000) { service.client.submit(request) })
                assertEquals(CommandResult.Rejected(RuntimeError(ErrorCode.NOT_READY, true)),
                    withTimeout(1000) { service.executeShell("true") })
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertFalse("recheck must not run alongside admission or output cleanup", recheck.isCompleted)
                assertTrue(coordinator.acquireDiagnostic(RunId("fixture")))
                admission.unlock()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(AdminResult.Failed(RuntimeError(ErrorCode.BUSY, true)), withTimeout(5000) { recheck.await() })
            } finally {
                if (admission.isLocked) admission.unlock()
                recheck.cancel()
                coordinator.releaseDiagnostic(RunId("fixture"), true)
                controller.destroy()
                worker.cancel()
            }
        }
        context.deleteDatabase("runtime-journal.db")
        Unit
    }
}
