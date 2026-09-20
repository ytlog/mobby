package com.mobby.runtime.android

import com.mobby.runtime.engine.StopCause
import com.mobby.runtime.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RuntimeServiceTimeoutTest {
    internal class StalledRuntimeService : RuntimeService() {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val causes = java.util.concurrent.ConcurrentLinkedQueue<StopCause>()
        override suspend fun stopForHost(cause: StopCause) { causes += cause; started.complete(Unit); release.await() }
    }
    internal class BrokenRuntimeService : RuntimeService() {
        override suspend fun stopForHost(cause: StopCause) { throw java.io.IOException("fixture journal failure") }
    }
    @Test fun `cleanup failure remains visible after Android service has stopped`() = runBlocking {
        val controller = Robolectric.buildService(BrokenRuntimeService::class.java)
        val service = controller.get()
        try {
            service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertTrue(shadowOf(service).isStoppedBySelf)
            val failure = withTimeout(5_000) { service.environment.first { it.error?.code == ErrorCode.DISCONNECTED } }
            assertEquals(EnvironmentPhase.FAILED, failure.phase)
        } finally { controller.destroy() }
    }
    @Test fun `destroy after timeout preserves timeout cause instead of changing it to interruption`() = runBlocking {
        val controller = Robolectric.buildService(StalledRuntimeService::class.java)
        val service = controller.get()
        try {
            service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            withTimeout(5_000) { service.started.await() }
            controller.destroy()
        } finally { service.release.complete(Unit) }
        withTimeout(5_000) { while (service.causes.size < 2) kotlinx.coroutines.delay(10) }
        assertTrue(service.causes.all { it == StopCause.TIMEOUT })
    }
    @Test fun `system timeout stops service even while runtime cleanup is stalled`() {
        // Exercise the Android lifecycle callback with a stalled process host, without bootstrapping native CLIs.
        val controller = Robolectric.buildService(StalledRuntimeService::class.java)
        val service = controller.get()
        try {
            service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(EnvironmentPhase.FAILED, service.environment.value.phase)
            assertEquals(ErrorCode.TIMEOUT, service.environment.value.error?.code)
        } finally { service.release.complete(Unit); controller.destroy() }
    }
}
