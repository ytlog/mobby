package com.github.ytlog.mobby.android.programs.run

import com.github.ytlog.mobby.android.programs.api.CallerResolver
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.DryRunScope
import com.github.ytlog.mobby.android.programs.capability.Surface
import com.github.ytlog.mobby.android.programs.event.ProgramEvent
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import com.github.ytlog.mobby.android.programs.store.RunRow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class ProcessSpec(
    val runId: String,
    val packageDir: File,
    val entry: String,
    val workDir: File,
    val sdkDir: File,
    val apiPort: Int,
    /** Written to the child's stdin as its first line; never in argv, environment or files. */
    val credential: String,
)

interface ProgramProcess {
    val pid: Int
    /** Exit code, or 128 + signal when the process was ended by a signal; null when unknown. */
    suspend fun awaitExit(): Int?
    /** SIGTERM to the process group, then SIGKILL after the launcher's grace period. */
    fun terminate()
}

/** A script in a package that does not parse; [line] is 1-based when the runtime reports it. */
data class SyntaxProblem(val path: String, val line: Int?, val message: String)

interface ProgramProcessLauncher {
    fun launch(spec: ProcessSpec): ProgramProcess
    /** Ends background processes left by an earlier app process. */
    fun recoverOrphans() {}
    /**
     * Parses [scripts] (paths relative to [packageDir]) with the program runtime without running them.
     * Throws [LaunchUnavailable] when the runtime cannot check, so callers never mistake that for clean code.
     */
    fun checkSyntax(sdkDir: File, packageDir: File, scripts: List<String>): List<SyntaxProblem> = throw LaunchUnavailable("syntax_check_unsupported")
}

class LaunchUnavailable(val reason: String) : IllegalStateException(reason)

data class RunLimits(
    val maxConcurrent: Int = 4,
    val startTimeoutMillis: Long = 15_000,
    val maxRunMillis: Long = 120_000,
    val idleMillis: Long = 15_000,
    val graceMillis: Long = 3_000,
    val queueLength: Int = 32,
    val queueExpiryMillis: Long = 60_000,
    val eventQueue: Int = 64,
)

/** What the event stream delivers to a background process. */
sealed interface RunMessage {
    data class Event(val event: ProgramEvent) : RunMessage
    data class Stopping(val reason: String) : RunMessage
}

enum class RunOutcome(val wire: String) {
    EXITED("exited"), FAILED("failed"), KILLED("killed"), KILLED_BY_SYSTEM("killed_by_system"), DROPPED("dropped"), INTERRUPTED("interrupted")
}

/** Snapshot of one live run, for the UI and diagnostics. */
data class RunInfo(val runId: String, val programId: String, val state: String, val dryRun: Boolean)

/** Where a triggered run comes from and what it runs. */
data class RunTarget(val programId: String, val version: String, val packageDir: File, val entry: String, val workDir: File)

/**
 * One Node process per running program. Triggers for a program with a live run go into its
 * queue; otherwise a run is queued until a concurrency slot is free. All state changes are
 * serialized by [lock].
 */
class RunSupervisor(
    private val scope: CoroutineScope,
    private val dao: ProgramsDao,
    private val launcher: ProgramProcessLauncher,
    private val sdkDir: File,
    private val apiPort: () -> Int,
    private val limits: RunLimits = RunLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Frees what a run held for its whole life, such as open sockets; called once its credential stops working. */
    private val released: (caller: String) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : CallerResolver {
    private enum class State { STARTING, RUNNING, STOPPING }

    private inner class Live(val runId: String, val key: String, val target: RunTarget, val context: CallContext, val credential: String) {
        var state = State.STARTING
        var process: ProgramProcess? = null
        // One slot beyond the event limit is reserved so run.stopping always gets through.
        val messages = Channel<RunMessage>(limits.eventQueue + 1)
        val queued = java.util.concurrent.atomic.AtomicInteger()
        var stopReason: String? = null
        var platformStopped = false
        var startTimer: Job? = null
        var deadline: Job? = null
        var idle: Job? = null
        var connected = false
        var dropped = 0
    }

    private data class Pending(val runId: String, val target: RunTarget, val first: List<ProgramEvent>, val dryRun: DryRunScope?, val queuedAt: Long) {
        /** Dry runs never share a slot with the program's real run. */
        val key get() = if (dryRun == null) target.programId else "${target.programId}#dry:$runId"
    }

    private val lock = Mutex()
    private val live = mutableMapOf<String, Live>()            // by programId, or a dry-run key
    private val credentials = java.util.concurrent.ConcurrentHashMap<String, Live>()  // read by HTTP threads
    private val pending = ArrayDeque<Pending>()
    private val random = SecureRandom()

    /** Delivers [event] to the program's live run, or queues a new run that starts with it. Returns the run ID. */
    suspend fun trigger(target: RunTarget, event: ProgramEvent?, trigger: String, dryRun: DryRunScope? = null): String =
        trigger(target, listOfNotNull(event), trigger, dryRun)

    suspend fun trigger(target: RunTarget, events: List<ProgramEvent>, trigger: String, dryRun: DryRunScope?): String = lock.withLock {
        val event = events.firstOrNull()
        live[target.programId]?.takeIf { it.state != State.STOPPING && it.target.version == target.version && dryRun == null }?.let { run ->
            events.forEach { deliver(run, it) }
            return@withLock run.runId
        }
        pending.firstOrNull { it.target.programId == target.programId && it.dryRun == null && dryRun == null }?.let { return@withLock it.runId }
        val runId = newId()
        dao.insertRun(RunRow(runId, target.programId, target.version, trigger, event?.subscriptionId, dryRun != null, clock()))
        if (pending.size >= limits.queueLength) {
            finishRow(runId, RunOutcome.DROPPED, null, "queue_full")
            return@withLock runId
        }
        pending.addLast(Pending(runId, target, events, dryRun, clock()))
        startPending()
        runId
    }

    /** Delivers to a live run only; platform events never start a process. */
    suspend fun deliverIfLive(programId: String, event: ProgramEvent): Boolean = lock.withLock {
        val run = live[programId]?.takeIf { it.state != State.STOPPING && it.context.dryRun == null } ?: return@withLock false
        deliver(run, event); true
    }

    override fun resolve(credential: String): CallContext? = credentials[credential]?.let { run ->
        if (run.state == State.STOPPING) null else { touch(run); run.context }
    }

    /** The event stream of a run; connecting marks the run as running. Null for unknown or ended runs. */
    suspend fun attach(runId: String): Flow<RunMessage>? = lock.withLock {
        val run = live.values.firstOrNull { it.runId == runId } ?: return@withLock null
        if (run.connected) return@withLock null
        run.connected = true
        if (run.state == State.STARTING) {
            run.state = State.RUNNING
            run.startTimer?.cancel()
            dao.run(runId)?.let { dao.updateRun(it.copy(startedAt = clock())) }
        }
        touch(run)
        run.messages.consumeAsFlow().onEach { if (it is RunMessage.Event) run.queued.decrementAndGet() }
    }

    suspend fun stop(programId: String, reason: String) = lock.withLock { live[programId]?.let { requestStop(it, reason) } }

    /** Stops one run by ID, including dry runs that never share the program's slot. */
    suspend fun stopRun(runId: String, reason: String) = lock.withLock {
        live.values.firstOrNull { it.runId == runId }?.let { requestStop(it, reason) }
        pending.firstOrNull { it.runId == runId }?.let { pending.remove(it); finishRow(it.runId, RunOutcome.DROPPED, null, reason) }
    }

    suspend fun stopAll(reason: String) = lock.withLock {
        pending.toList().forEach { finishRow(it.runId, RunOutcome.DROPPED, null, reason) }
        pending.clear()
        live.values.toList().forEach { requestStop(it, reason) }
    }

    suspend fun active(): List<RunInfo> = lock.withLock { live.values.map { RunInfo(it.runId, it.target.programId, it.state.name.lowercase(), it.context.dryRun != null) } }

    /** Marks what a previous app process left unfinished; nothing is resumed or replayed. */
    suspend fun recover() {
        launcher.recoverOrphans()
        dao.unfinishedRuns().forEach { finishRow(it.runId, RunOutcome.INTERRUPTED, null, "process_restarted") }
        dao.unfinishedOperations().forEach { op ->
            dao.updateOperation(op.copy(completedAt = clock(), status = if (op.dispatchedAt != null) "unknown" else "cancelled",
                effect = if (op.dispatchedAt != null) "unknown" else op.effect, errorCode = if (op.dispatchedAt != null) "EFFECT_UNKNOWN" else "CANCELLED",
                errorReason = "process_restarted"))
        }
    }

    private suspend fun startPending() {
        while (live.size < limits.maxConcurrent && pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (clock() - next.queuedAt > limits.queueExpiryMillis) { finishRow(next.runId, RunOutcome.DROPPED, null, "queue_expired"); continue }
            if (live.containsKey(next.key)) { pending.addLast(next); break }
            start(next)
        }
    }

    private suspend fun start(next: Pending) {
        val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val context = CallContext(next.target.programId, next.target.version, Surface.BACKGROUND, runId = next.runId, dryRun = next.dryRun)
        val run = Live(next.runId, next.key, next.target, context, credential)
        // Registered before launch: a child may call the API before launch() returns.
        live[next.key] = run
        credentials[credential] = run
        next.first.forEach { deliver(run, it) }
        val process = try {
            launcher.launch(ProcessSpec(next.runId, next.target.packageDir, next.target.entry, next.target.workDir, sdkDir, apiPort(), credential))
        } catch (error: RuntimeException) {
            live.remove(next.key); credentials.remove(credential); run.messages.close(); run.idle?.cancel()
            finishRow(next.runId, RunOutcome.FAILED, null, (error as? LaunchUnavailable)?.reason ?: "launch_failed"); return
        }
        run.process = process
        run.startTimer = scope.launch { delay(limits.startTimeoutMillis); lock.withLock { if (run.state == State.STARTING) requestStop(run, "start_timeout") } }
        run.deadline = scope.launch { delay(limits.maxRunMillis); lock.withLock { requestStop(run, "run_deadline") } }
        scope.launch { val exit = process.awaitExit(); lock.withLock { ended(run, exit) } }
    }

    private fun deliver(run: Live, event: ProgramEvent) {
        // A full queue drops the newest event; the count is kept in the run record.
        if (run.queued.get() >= limits.eventQueue || run.messages.trySend(RunMessage.Event(event)).isFailure) run.dropped++
        else run.queued.incrementAndGet()
        touch(run)
    }

    private fun touch(run: Live) {
        run.idle?.cancel()
        run.idle = scope.launch { delay(limits.idleMillis); lock.withLock { if (run.state == State.RUNNING) requestStop(run, "idle") } }
    }

    private fun requestStop(run: Live, reason: String) {
        if (run.state == State.STOPPING) return
        run.state = State.STOPPING
        run.stopReason = reason
        run.platformStopped = true
        credentials.remove(run.credential)
        released(run.context.caller)
        run.messages.trySend(RunMessage.Stopping(reason))
        run.messages.close()
        scope.launch { delay(limits.graceMillis); run.process?.terminate() }
    }

    private suspend fun ended(run: Live, exit: Int?) {
        if (live[run.key] !== run) return
        live.remove(run.key)
        credentials.remove(run.credential)
        released(run.context.caller)
        listOf(run.startTimer, run.deadline, run.idle).forEach { it?.cancel() }
        run.messages.close()
        val outcome = when {
            !run.connected && !run.platformStopped -> RunOutcome.FAILED
            exit == SIGKILL_EXIT && !run.platformStopped -> RunOutcome.KILLED_BY_SYSTEM
            run.platformStopped && exit != null && exit > 128 -> RunOutcome.KILLED
            else -> RunOutcome.EXITED
        }
        val reason = run.stopReason ?: if (!run.connected) "exited_before_connecting" else null
        finishRow(run.runId, outcome, exit, reason, run.dropped)
        startPending()
    }

    private suspend fun finishRow(runId: String, outcome: RunOutcome, exit: Int?, reason: String?, dropped: Int = 0) {
        val row = dao.run(runId) ?: return
        dao.updateRun(row.copy(endedAt = clock(), outcome = outcome.wire,
            exitCode = exit?.takeIf { it <= 128 }, signal = exit?.takeIf { it > 128 }?.minus(128), reason = reason, droppedEvents = dropped))
    }

    companion object { const val SIGKILL_EXIT = 128 + 9 }
}
