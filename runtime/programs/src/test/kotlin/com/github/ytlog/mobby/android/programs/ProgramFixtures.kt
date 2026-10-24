package com.github.ytlog.mobby.android.programs

import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.Scope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Minimal handlers with each scope shape, for tests that do not exercise the capability itself. */
internal open class FakeHandler(id: String, kind: CapabilityKind = CapabilityKind.COMMAND, scope: ScopeKind = ScopeKind.NONE) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor(id, 1, kind, JsonObject(emptyMap()), JsonObject(emptyMap()), scope, emptySet(), emptyList(),
        setOf(Surface.BACKGROUND, Surface.UI), 1_000, true, CapabilityLimits(1024, 60))
    override fun availability(): Availability = Availability.Available
    val calls = mutableListOf<CapabilityCall>()
    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome { calls += call; return CapabilityOutcome(Effect.NONE, JsonPrimitive("ok")) }
}

internal class FakeSchedule : FakeHandler("time.schedule", CapabilityKind.EVENT), EventCapability {
    override fun validateFilter(filter: JsonObject, requested: Scope) =
        if (filter.keys == setOf("every")) emptyList() else listOf("filter needs every")
}

internal fun fixtureRegistry() = CapabilityRegistry(listOf(
    FakeHandler("net.http", scope = ScopeKind.DOMAINS),
    FakeHandler("storage.sql"),
    FakeHandler("notify.show"),
    FakeHandler("screen.click", scope = ScopeKind.PACKAGES),
    FakeSchedule(),
))

internal class PackageBuilder(private val dir: File) {
    var id = "price-watch"
    var version = "1.0.0"
    var background: String? = "main.js"
    var ui: String? = "ui/index.html"
    var permissions = """[{"capability":"net.http","required":true,"scope":{"domains":["api.example.com"],"methods":["GET"]}},
        {"capability":"storage.sql","required":true},{"capability":"time.schedule","required":true}]"""
    var config = """[{"key":"threshold","type":"number","required":true,"label":"阈值"},
        {"key":"apiToken","type":"secret","required":true,"label":"令牌","domains":["api.example.com"]}]"""
    var subscriptions = """[{"id":"hourly","event":"time.schedule","filter":{"every":"PT1H"},"handler":"onSchedule"}]"""
    var extra = ""
    var html = """<!doctype html><script type="module" src="app.js"></script><a href="https://example.com/help">帮助</a>"""
    val files = mutableMapOf("main.js" to "module.exports = {}", "ui/app.js" to "export {}")

    fun write(): File {
        dir.deleteRecursively(); dir.mkdirs()
        val fields = listOfNotNull(
            "\"schema\":\"mobby.program/1\"", "\"id\":\"$id\"", "\"version\":\"$version\"", "\"name\":\"价格提醒\"",
            background?.let { "\"background\":\"$it\"" }, ui?.let { "\"ui\":{\"entry\":\"$it\"}" },
            "\"permissions\":$permissions", "\"config\":$config", "\"subscriptions\":$subscriptions",
        ) + listOfNotNull(extra.takeIf { it.isNotEmpty() })
        File(dir, "manifest.json").writeText(fields.joinToString(",", "{", "}"))
        ui?.let { File(dir, it).apply { parentFile!!.mkdirs() }.writeText(html) }
        files.forEach { (path, text) -> File(dir, path).apply { parentFile!!.mkdirs() }.writeText(text) }
        return dir
    }
}
