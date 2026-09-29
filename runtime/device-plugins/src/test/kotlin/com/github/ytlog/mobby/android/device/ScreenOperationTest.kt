package com.github.ytlog.mobby.android.device

import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScreenOperationTest {
    private val worker = Executors.newSingleThreadExecutor()
    @After fun cleanup() {
        ScreenOperation.hideOverlay = { AutoCloseable {} }
        worker.shutdownNow()
    }

    @Test fun `overlay is absent during action and restored on success failure and timeout`() {
        for (outcome in listOf("success", "failure", "timeout")) {
            var visible = true
            val events = mutableListOf<String>()
            ScreenOperation.hideOverlay = {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                visible = false
                events += "detached"
                AutoCloseable { visible = true; events += "restored" }
            }
            val result = execute {
                ScreenOperation.run({}) {
                    assertFalse(visible)
                    events += "action"
                    when (outcome) {
                        "failure" -> error("fixture action failed")
                        "timeout" -> withTimeout(1) { delay(1_000) }
                    }
                    "done"
                }
            }
            assertEquals(outcome == "success", result.isSuccess)
            assertEquals(listOf("detached", "action", "restored"), events)
            assertTrue(visible)
        }
    }

    @Test fun `timed out main queue request never executes later`() {
        var actions = 0
        var hides = 0
        ScreenOperation.hideOverlay = { hides++; AutoCloseable {} }
        val started = java.util.concurrent.CountDownLatch(1)
        val future = worker.submit<Result<Any?>> {
            started.countDown()
            runCatching { ScreenOperation.run({}) { actions++; "done" } }
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        // Deliberately leave the main looper unserviced beyond the operation deadline.
        Thread.sleep(8_300)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(future.get(2, TimeUnit.SECONDS).isFailure)
        assertEquals(0, hides)
        assertEquals(0, actions)
    }

    @Test fun `stop during detach prevents action and still restores overlay`() {
        val gate = DeviceActionGate()
        var restored = false
        ScreenOperation.hideOverlay = {
            gate.close()
            AutoCloseable { restored = true }
        }
        val result = execute { ScreenOperation.run(gate::checkActive) { fail("Stopped action dispatched") } }
        assertTrue(result.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertTrue(restored)
    }

    @Test fun `stopped request never touches overlay or screen`() {
        val gate = DeviceActionGate { true }
        ScreenOperation.hideOverlay = { error("Must not hide") }
        val result = execute { ScreenOperation.run(gate::checkActive) { fail("Must not act") } }
        assertTrue(result.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    @Test fun `detach failure aborts without dispatching action`() {
        ScreenOperation.hideOverlay = { error("Unable to detach") }
        val result = execute { ScreenOperation.run({}) { fail("Must not act") } }
        assertEquals("Unable to detach", result.exceptionOrNull()?.message)
    }

    private fun execute(block: () -> Any?): Result<Any?> {
        val future = worker.submit<Result<Any?>> { runCatching(block) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return future.get(1, TimeUnit.SECONDS)
    }
}
