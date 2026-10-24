package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.event.ProgramEvent
import com.github.ytlog.mobby.android.programs.run.*
import com.github.ytlog.mobby.android.programs.store.OperationRow
import com.github.ytlog.mobby.android.programs.store.ProgramsDatabase
import com.github.ytlog.mobby.android.programs.store.RunRow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RunSupervisorTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
    private val dao = database.dao()

    private class FakeProcess(override val pid: Int) : ProgramProcess {
        val exit = CompletableDeferred<Int?>()
        var terminated = false
        override suspend fun awaitExit() = exit.await()
        override fun terminate() { terminated = true; exit.complete(143) }
    }

    private class FakeLauncher : ProgramProcessLauncher {
        val specs = mutableListOf<ProcessSpec>()
        val processes = mutableListOf<FakeProcess>()
        var unavailable: String? = null
        var recovered = false
        override fun launch(spec: ProcessSpec): ProgramProcess {
            unavailable?.let { throw LaunchUnavailable(it) }
            specs += spec
            return FakeProcess(specs.size).also { processes += it }
        }
        override fun recoverOrphans() { recovered = true }
    }

    private val launcher = FakeLauncher()
    private var ids = 0
    private fun target(id: String = "a") = RunTarget(id, "1", File("/pkg/$id"), "main.js", File("/data/$id"))
    private fun event(n: Int, handler: String = "onTick") = ProgramEvent("e$n", "time.schedule", handler, "a/tick", n.toLong(), JsonObject(emptyMap()))
    // advanceUntilIdle ignores backgroundScope, so the supervisor gets its own scope on the test scheduler.
    private val scopes = mutableListOf<CoroutineScope>()
    private fun TestScope.supervisor(limits: RunLimits = RunLimits(maxConcurrent = 2, startTimeoutMillis = 1_000, maxRunMillis = 60_000,
        idleMillis = 5_000, graceMillis = 500, queueLength = 2, queueExpiryMillis = 10_000, eventQueue = 2)): RunSupervisor {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        return RunSupervisor(scope, dao, launcher, File("/sdk"), { 4321 }, limits, { testScheduler.currentTime }) { "run-${++ids}" }
    }
    /** Runs what is due now plus short delays such as the stop grace period. */
    private fun TestScope.settle(millis: Long = 600) { advanceTimeBy(millis); runCurrent() }
    private suspend fun run(id: String) = dao.run(id)!!

    @After fun close() { scopes.forEach { it.cancel() }; database.close() }

    @Test fun `a trigger starts one process per program and later triggers join it`() = runTest {
        val runs = supervisor()
        val first = runs.trigger(target(), event(1), "schedule")
        val spec = launcher.specs.single()
        assertEquals(listOf(4321, 32), listOf(spec.apiPort, java.util.Base64.getUrlDecoder().decode(spec.credential).size))
        assertEquals("a", runs.resolve(spec.credential)!!.programId)
        assertNull(runs.resolve("guess"))
        val stream = runs.attach(first)!!
        assertNull(runs.attach(first))
        assertNotNull(run(first).startedAt)
        assertEquals(first, runs.trigger(target(), event(2), "schedule"))
        assertEquals(1, launcher.specs.size)
        val received = async { stream.toList() }
        runs.stop("a", "user")
        settle()
        assertEquals(listOf("e1", "e2"), received.await().filterIsInstance<RunMessage.Event>().map { it.event.eventId })
        assertEquals(RunMessage.Stopping("user"), received.await().last())
        assertTrue(launcher.processes.single().terminated)
        with(run(first)) { assertEquals("killed", outcome); assertEquals(15, signal); assertEquals("user", reason) }
        assertNull(runs.resolve(spec.credential))
    }

    @Test fun `idle runs and runs that never connect are stopped`() = runTest {
        val runs = supervisor()
        val idle = runs.trigger(target("a"), event(1), "schedule")
        runs.attach(idle)
        val silent = runs.trigger(target("b"), null, "ui")
        advanceTimeBy(1_600)
        assertEquals("start_timeout", run(silent).reason)
        advanceTimeBy(5_000)
        assertEquals("idle", run(idle).reason)
    }

    @Test fun `exits are told apart from failures and system kills`() = runTest {
        val runs = supervisor()
        val early = runs.trigger(target("a"), null, "ui")
        launcher.processes[0].exit.complete(1)
        settle()
        with(run(early)) { assertEquals("failed", outcome); assertEquals("exited_before_connecting", reason); assertEquals(1, exitCode) }
        val killed = runs.trigger(target("b"), null, "ui")
        runs.attach(killed)
        launcher.processes[1].exit.complete(137)
        settle()
        assertEquals("killed_by_system", run(killed).outcome)
        val clean = runs.trigger(target("c"), null, "ui")
        runs.attach(clean)
        launcher.processes[2].exit.complete(0)
        settle()
        with(run(clean)) { assertEquals("exited", outcome); assertEquals(0, exitCode) }
    }

    @Test fun `concurrency is bounded and queued runs start, expire or are dropped`() = runTest {
        val runs = supervisor(RunLimits(maxConcurrent = 1, queueLength = 1, queueExpiryMillis = 10_000, idleMillis = 60_000, graceMillis = 0))
        val a = runs.trigger(target("a"), null, "ui")
        runs.attach(a)
        val b = runs.trigger(target("b"), null, "ui")
        val c = runs.trigger(target("c"), null, "ui")
        assertEquals("queue_full", run(c).reason)
        assertEquals(1, launcher.specs.size)
        launcher.processes[0].exit.complete(0)
        settle()
        assertEquals(listOf("a", "b"), launcher.specs.map { it.packageDir.name })
        runs.attach(b)
        val d = runs.trigger(target("d"), null, "ui")
        advanceTimeBy(11_000)
        launcher.processes[1].exit.complete(0)
        settle()
        assertEquals("queue_expired", run(d).reason)
        assertEquals(2, launcher.specs.size)
    }

    @Test fun `stop all clears the queue and stops live runs`() = runTest {
        val runs = supervisor(RunLimits(maxConcurrent = 1, graceMillis = 0))
        val a = runs.trigger(target("a"), null, "ui")
        val b = runs.trigger(target("b"), null, "ui")
        runs.stopAll("stop_all")
        settle()
        assertEquals(listOf("stop_all", "stop_all"), listOf(run(a).reason, run(b).reason))
        assertEquals("dropped", run(b).outcome)
        assertEquals(1, launcher.specs.size)
    }

    @Test fun `a full event queue drops and counts events`() = runTest {
        val runs = supervisor()
        val id = runs.trigger(target(), event(1), "schedule")
        (2..4).forEach { runs.trigger(target(), event(it), "schedule") }
        runs.attach(id)
        runs.stop("a", "user")
        settle()
        assertEquals(2, run(id).droppedEvents)
    }

    @Test fun `an unavailable runtime fails the run with its reason`() = runTest {
        launcher.unavailable = "runtime_not_ready"
        val runs = supervisor()
        val id = runs.trigger(target(), null, "ui")
        with(run(id)) { assertEquals("failed", outcome); assertEquals("runtime_not_ready", reason) }
        assertTrue(runs.active().isEmpty())
    }

    @Test fun `recovery never resumes earlier work`() = runTest {
        dao.insertRun(RunRow("old", "a", "1", "schedule", null, false, 1))
        dao.insertOperation(OperationRow("o1", "a", "run:old", "r1", "f", "sms.send", 1, 1, dispatchedAt = 2, status = "accepted", effect = "none"))
        dao.insertOperation(OperationRow("o2", "a", "run:old", "r2", "f", "sms.send", 1, 1, status = "accepted", effect = "none"))
        supervisor().recover()
        assertTrue(launcher.recovered)
        assertEquals("interrupted", run("old").outcome)
        assertEquals(listOf("unknown", "cancelled"), listOf(dao.operation("run:old", "r1")!!.status, dao.operation("run:old", "r2")!!.status))
        assertEquals("unknown", dao.operation("run:old", "r1")!!.effect)
    }
}
