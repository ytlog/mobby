package com.github.ytlog.mobby.android.programcenter

import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.programs.ConfigView
import com.github.ytlog.mobby.android.programs.PendingInstall
import com.github.ytlog.mobby.android.programs.ProgramCandidate
import com.github.ytlog.mobby.android.programs.ProgramView
import com.github.ytlog.mobby.android.programs.SubscriptionView
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.program.*
import com.github.ytlog.mobby.android.programs.store.RunRow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProgramListModelTest {
    private fun every(value: String) = buildJsonObject { put("every", value) }
    private val schedule = SubscriptionView("hw/every15m", "time.schedule", every("PT15M"), "onSchedule", "manifest", false, null, 0, true)
    private fun program(state: UserState = UserState.ENABLED, availability: ProgramAvailability = ProgramAvailability.WORKING,
        subscriptions: List<SubscriptionView> = listOf(schedule), ui: Boolean = true, config: List<ConfigView> = emptyList()) =
        ProgramView("hw", "Header Watch", "1.0.0", state, availability, true, ui, emptyList(), config, subscriptions, emptyList())
    private fun run(outcome: String?, exitCode: Int? = null, reason: String? = null) =
        RunRow("r", "hw", "1.0.0", "schedule", null, false, 100, 110, 120, outcome, exitCode, null, reason)
    private val time: (Long) -> String = { "t$it" }

    @Test fun `schedules read as plain intervals`() {
        assertEquals(AppStrings.everyMinutes(15), scheduleLabel(every("PT15M")))
        assertEquals(AppStrings.everyHours(2), scheduleLabel(every("PT2H")))
        assertEquals(AppStrings.everyDays(1), scheduleLabel(every("P1D")))
        assertEquals(AppStrings.dailyAt("09:00"), scheduleLabel(buildJsonObject { put("daily", "09:00") }))
        assertNull(scheduleLabel(every("soon")))
    }

    @Test fun `a working program says what it does and how its last run went`() {
        assertEquals(StatusLine("${AppStrings.everyMinutes(15)} · ${AppStrings.lastRunOk("t120")}"), programStatus(program(), run("exited", 0), time))
        assertEquals(StatusLine("${AppStrings.everyMinutes(15)} · ${AppStrings.lastRunFailed("t120")}", problem = true),
            programStatus(program(), run("exited", 2), time))
        assertEquals(StatusLine("${AppStrings.everyMinutes(15)} · ${AppStrings.lastRunStopped("t120")}"),
            programStatus(program(), run("killed", reason = "stopped_by_user"), time))
        assertEquals(StatusLine(AppStrings.statusOpenToUse), programStatus(program(subscriptions = emptyList()), null, time))
    }

    /** On the tablet an app update restarted mobby mid-run; every call had succeeded, yet the list said the run failed. */
    @Test fun `a run cut short by mobby restarting is interrupted, not failed`() {
        assertEquals(StatusLine("${AppStrings.everyMinutes(15)} · ${AppStrings.lastRunInterrupted("t120")}"),
            programStatus(program(), run("interrupted", reason = "process_restarted"), time))
    }

    @Test fun `paused and blocked programs say so before anything else`() {
        assertEquals(StatusLine(AppStrings.availabilityPaused), programStatus(program(UserState.PAUSED, ProgramAvailability.PAUSED), run("exited", 2), time))
        assertEquals(StatusLine(AppStrings.availabilityNeedsConfig, problem = true),
            programStatus(program(availability = ProgramAvailability.NEEDS_CONFIG), null, time))
    }

    @Test fun `capabilities are described by what they let the program do`() {
        assertEquals(AppStrings.purposeNet("a.com, b.com"), purposeLabel("net.http", Scope(domains = setOf("b.com", "a.com")), emptyList()))
        assertEquals(AppStrings.purposeSchedule(AppStrings.everyMinutes(15)), purposeLabel("time.schedule", Scope(), listOf(every("PT15M"))))
        assertEquals("screen.click", purposeLabel("screen.click", Scope(), emptyList()))
    }

    @Test fun `pending programs say whether they are new, updated or broken`() {
        assertEquals(StatusLine(AppStrings.pendingNew), readyStatus(null, 3))
        assertEquals(StatusLine(AppStrings.pendingUpdateAsks(1)), readyStatus("1.0.0", 1))
        assertEquals(StatusLine(AppStrings.pendingUpdate), readyStatus("1.0.0", 0))
        val rejected = PendingInstall.Rejected(ProgramCandidate(File("x"), "x"), listOf(PackageProblem("manifest.json", "bad")))
        assertEquals(StatusLine(AppStrings.pendingRejected(1), problem = true), pendingStatus(rejected))
    }

    @Test fun `install grants required ones, and optional ones start ticked only when first asked for`() {
        val requests = listOf(RequestedPermission("net.http", true, Scope(domains = setOf("a.com"))), RequestedPermission("notify.show", false, Scope()),
            RequestedPermission("storage.files", false, Scope()), RequestedPermission("storage.sql", true, Scope()))
        // storage.sql is already granted; notify.show was turned off by the user; storage.files is new.
        val choices = GrantChoices(requests, needsNewGrant = listOf("net.http", "notify.show", "storage.files"), newlyRequested = listOf("storage.files"))
        assertEquals(setOf("notify.show", "storage.files"), choices.tickable)
        assertEquals(setOf("storage.files"), choices.initiallyTicked)
        assertEquals(setOf("net.http", "storage.files"), choices.toApply(choices.initiallyTicked))
        assertEquals(setOf("net.http"), choices.toApply(emptySet()))
    }

    @Test fun `only required settings without a kept value or default are asked for`() {
        val token = ConfigField("token", "secret", true, "API token")
        val label = ConfigField("label", "string", false, "Title")
        val rows = ConfigField("rows", "number", true, "Rows", default = JsonPrimitive(20))
        val threshold = ConfigField("threshold", "number", true, "Threshold")
        assertEquals(listOf(token, threshold), fieldsToFill(listOf(token, label, rows, threshold), installed = null))
        val kept = program(config = listOf(ConfigView(token, null, secretSet = true), ConfigView(threshold, JsonPrimitive(3), false)))
        assertEquals(emptyList<ConfigField>(), fieldsToFill(listOf(token, label, rows, threshold), kept))
    }

    @Test fun `typed settings become values of their type`() {
        assertEquals(JsonPrimitive(3L), fieldValue(ConfigField("n", "number", true, "N"), " 3 "))
        assertNull(fieldValue(ConfigField("n", "number", true, "N"), "three"))
        assertEquals(JsonPrimitive(true), fieldValue(ConfigField("b", "boolean", true, "B"), "true"))
        assertNull(fieldValue(ConfigField("s", "string", true, "S"), "  "))
    }
}
