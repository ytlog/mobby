package com.github.ytlog.mobby.android.programs.event

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Process-wide handle the worker uses; set once the platform has started. */
object ScheduleTarget {
    private val ready = CompletableDeferred<EventRouter>()
    fun publish(router: EventRouter) { ready.complete(router) }
    suspend fun await(timeoutMillis: Long): EventRouter? = withTimeoutOrNull(timeoutMillis) { ready.await() }
}

/**
 * WorkManager timers, one unique work per subscription. A signature per entry keeps unchanged
 * periodic work running; one-off and daily entries include their next time, so firing re-arms them.
 */
class WorkManagerSchedules(context: Context, private val clock: () -> Long = System::currentTimeMillis) : ScheduleBackend {
    private val work = WorkManager.getInstance(context)
    private val prefs = context.getSharedPreferences("program-schedules", Context.MODE_PRIVATE)

    @Synchronized override fun sync(schedules: List<ScheduleSpec>) {
        val now = clock()
        val wanted = schedules.associateBy { NAME + it.subscriptionId }
        prefs.all.keys.filter { it !in wanted }.forEach { name -> work.cancelUniqueWork(name); prefs.edit().remove(name).apply() }
        for ((name, spec) in wanted) {
            val next = if (spec.everyMillis == null) spec.nextAfter(now) else null
            if (spec.everyMillis == null && next == null) { work.cancelUniqueWork(name); prefs.edit().remove(name).apply(); continue }
            val signature = "$spec|$next"
            if (prefs.getString(name, null) == signature) continue
            val data = workDataOf(KEY_SUBSCRIPTION to spec.subscriptionId, KEY_PLANNED to (next ?: -1L))
            if (spec.everyMillis != null) {
                work.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<ScheduleWorker>(spec.everyMillis, TimeUnit.MILLISECONDS).setInputData(data).addTag(TAG).build())
            } else {
                work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ScheduleWorker>().setInitialDelay(next!! - now, TimeUnit.MILLISECONDS).setInputData(data).addTag(TAG).build())
            }
            prefs.edit().putString(name, signature).apply()
        }
    }

    companion object {
        const val NAME = "program-schedule:"
        const val TAG = "program-schedule"
        const val KEY_SUBSCRIPTION = "subscription"
        const val KEY_PLANNED = "planned"
    }
}

/** Delivers one timer to the event router; the router decides whether the subscription is still active. */
class ScheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val subscription = inputData.getString(WorkManagerSchedules.KEY_SUBSCRIPTION) ?: return Result.failure()
        val planned = inputData.getLong(WorkManagerSchedules.KEY_PLANNED, -1L).takeIf { it > 0 }
        // A cold start runs Application.onCreate first; wait for the platform rather than dropping the timer.
        val router = ScheduleTarget.await(30_000) ?: return Result.retry()
        router.fireSchedule(subscription, planned)
        return Result.success()
    }
}
