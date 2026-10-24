package com.github.ytlog.mobby.android.programs.event

import com.github.ytlog.mobby.android.programs.permission.PermissionState
import com.github.ytlog.mobby.android.programs.program.ProgramAvailability
import com.github.ytlog.mobby.android.programs.program.ProgramLayout
import com.github.ytlog.mobby.android.programs.program.ProgramStatus
import com.github.ytlog.mobby.android.programs.run.RunSupervisor
import com.github.ytlog.mobby.android.programs.run.RunTarget
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import com.github.ytlog.mobby.android.programs.store.SubscriptionRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Keeps the platform's timers in line with the active schedule subscriptions. */
interface ScheduleBackend {
    /** Replaces the whole set; unchanged entries keep their timers. */
    fun sync(schedules: List<ScheduleSpec>)
}

enum class FireResult { DELIVERED, INACTIVE, SKIPPED_LATE }

/**
 * Delivers events only to subscriptions that are active now: program working, subscription not
 * paused, and the event capability granted. The index is rebuilt after every relevant change.
 */
class EventRouter(
    private val dao: ProgramsDao,
    private val status: ProgramStatus,
    private val runs: RunSupervisor,
    private val layout: ProgramLayout,
    private val schedules: ScheduleBackend,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    @Volatile private var active: Map<String, SubscriptionRow> = emptyMap()

    suspend fun rebuild() = lock.withLock {
        val next = mutableMapOf<String, SubscriptionRow>()
        for (row in dao.allSubscriptions()) {
            if (row.paused || !working(row.programId)) continue
            if (status.stateOf(row.programId, row.eventType) != PermissionState.GRANTED) continue
            next[row.subscriptionId] = row
        }
        active = next
        schedules.sync(next.values.filter { it.eventType == "time.schedule" }.mapNotNull { row ->
            ScheduleSpec.parse(row.subscriptionId, Json.parseToJsonElement(row.filterJson).jsonObject).first
        })
    }

    fun isActive(subscriptionId: String) = subscriptionId in active

    /** A timer fired; late one-off or daily runs are dropped when the subscription says skip. */
    suspend fun fireSchedule(subscriptionId: String, plannedAt: Long?): FireResult {
        val row = active[subscriptionId] ?: return FireResult.INACTIVE
        val spec = ScheduleSpec.parse(subscriptionId, Json.parseToJsonElement(row.filterJson).jsonObject).first ?: return FireResult.INACTIVE
        val now = clock()
        if (plannedAt != null && spec.missed == "skip" && now - plannedAt > ScheduleSpec.LATE_TOLERANCE_MILLIS) return FireResult.SKIPPED_LATE
        deliver(row, buildJsonObject { put("firedAt", now); plannedAt?.let { put("plannedAt", it) } }, "schedule")
        // One-off times end here; daily times move to the next day.
        if (spec.atMillis != null || spec.daily != null) rebuild()
        return FireResult.DELIVERED
    }

    /** A message from the program UI; starts a background run when none is live. */
    suspend fun uiMessage(programId: String, payload: JsonElement): Boolean {
        if (!working(programId)) return false
        val target = target(programId) ?: return false
        dao.noteTriggered("$programId/ui", clock())
        runs.trigger(target, ProgramEvent(newId(), "ui.message", "ui.message", null, clock(), buildJsonObject { put("message", payload) }), "ui")
        return true
    }

    /** Platform notices such as permission or config changes; only a live run receives them. */
    suspend fun notify(programId: String, type: String, payload: JsonObject) {
        runs.deliverIfLive(programId, ProgramEvent(newId(), type, type, null, clock(), payload))
    }

    private suspend fun deliver(row: SubscriptionRow, payload: JsonObject, trigger: String) {
        val target = target(row.programId) ?: return
        dao.noteTriggered(row.subscriptionId, clock())
        runs.trigger(target, ProgramEvent(newId(), row.eventType, row.handler, row.subscriptionId, clock(), payload), trigger)
    }

    private suspend fun working(programId: String) = status.availability(programId) == ProgramAvailability.WORKING

    private suspend fun target(programId: String): RunTarget? {
        val program = dao.program(programId) ?: return null
        val version = dao.version(programId, program.currentVersion) ?: return null
        val entry = version.backgroundEntry ?: return null
        return RunTarget(programId, version.version, File(version.packagePath), entry, layout.files(programId))
    }
}
