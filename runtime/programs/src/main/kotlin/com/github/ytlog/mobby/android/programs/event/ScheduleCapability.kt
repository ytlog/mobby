package com.github.ytlog.mobby.android.programs.event

import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.Scope
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeParseException

/** A parsed `time.schedule` filter. Exactly one of [everyMillis], [atMillis] or [daily] is set. */
data class ScheduleSpec(
    val subscriptionId: String,
    val everyMillis: Long? = null,
    val atMillis: Long? = null,
    val daily: LocalTime? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** "skip" drops a run that fires much later than planned; "once" still delivers it once. */
    val missed: String = "skip",
) {
    /** Next planned time strictly after [now], or null when a one-off time has passed. */
    fun nextAfter(now: Long): Long? = when {
        everyMillis != null -> now + everyMillis
        atMillis != null -> atMillis.takeIf { it > now }
        else -> {
            val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            var next = current.with(daily!!).withSecond(0).withNano(0)
            if (!next.isAfter(current)) next = next.plusDays(1)
            next.toInstant().toEpochMilli()
        }
    }

    companion object {
        const val MIN_EVERY_MILLIS = 15 * 60 * 1000L
        const val LATE_TOLERANCE_MILLIS = 15 * 60 * 1000L

        fun parse(subscriptionId: String, filter: JsonObject): Pair<ScheduleSpec?, List<String>> {
            val problems = mutableListOf<String>()
            (filter.keys - setOf("every", "at", "daily", "timezone", "missed")).forEach { problems += "unknown filter field $it" }
            val kinds = listOf("every", "at", "daily").filter { it in filter }
            if (kinds.size != 1) return null to (problems + "exactly one of every, at or daily is required")
            fun text(key: String) = (filter[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val zone = text("timezone")?.let { runCatching { ZoneId.of(it) }.getOrNull() ?: null.also { _ -> problems += "unknown timezone $it" } } ?: ZoneId.systemDefault()
            val missed = text("missed") ?: "skip"
            if (missed !in setOf("skip", "once")) problems += "missed must be skip or once"
            val spec = when (kinds.single()) {
                "every" -> {
                    val millis = text("every")?.let { runCatching { Duration.parse(it).toMillis() }.getOrNull() }
                    if (millis == null || millis < MIN_EVERY_MILLIS || millis > Duration.ofDays(30).toMillis()) problems += "every must be an ISO duration from PT15M to P30D"
                    ScheduleSpec(subscriptionId, everyMillis = millis, zone = zone, missed = missed)
                }
                "at" -> {
                    val at = text("at")?.let { try { Instant.parse(it).toEpochMilli() } catch (_: DateTimeParseException) { null } }
                    if (at == null) problems += "at must be an ISO instant such as 2026-10-24T09:00:00Z"
                    ScheduleSpec(subscriptionId, atMillis = at, zone = zone, missed = missed)
                }
                else -> {
                    val daily = text("daily")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    if (daily == null) problems += "daily must be HH:mm"
                    ScheduleSpec(subscriptionId, daily = daily, zone = zone, missed = missed)
                }
            }
            return (if (problems.isEmpty()) spec else null) to problems
        }
    }
}

/** `time.schedule`: an event source only; it cannot be called. */
class ScheduleCapability : CapabilityHandler, EventCapability {
    override val descriptor = CapabilityDescriptor("time.schedule", 1, CapabilityKind.EVENT,
        buildJsonObject { put("type", "object") }, buildJsonObject { put("type", "object") }, ScopeKind.NONE, emptySet(), emptyList(),
        setOf(Surface.BACKGROUND), 1_000, false, CapabilityLimits(1024, 60))

    override fun availability(): Availability = Availability.Available

    override fun validateFilter(filter: JsonObject, requested: Scope): List<String> = ScheduleSpec.parse("check", filter).second

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome =
        throw CapabilityException(ProgramErrorCode.UNSUPPORTED, "event_capabilities_are_subscribed_not_called")
}
