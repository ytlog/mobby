package com.github.ytlog.mobby.android.programs.screen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.github.ytlog.mobby.android.device.ProgramScreen
import com.github.ytlog.mobby.android.device.ScreenAccessService
import com.github.ytlog.mobby.android.device.ScreenChange
import com.github.ytlog.mobby.android.device.ScreenEvents
import com.github.ytlog.mobby.android.device.ScreenNode
import com.github.ytlog.mobby.android.device.ScreenRefusal
import com.github.ytlog.mobby.android.device.ScreenRefused
import com.github.ytlog.mobby.android.device.ScreenTarget
import com.github.ytlog.mobby.android.device.ScreenWindow
import com.github.ytlog.mobby.android.programs.base.storage.invalid
import com.github.ytlog.mobby.android.programs.base.storage.schema
import com.github.ytlog.mobby.android.programs.base.storage.stringType
import com.github.ytlog.mobby.android.programs.base.storage.text
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

/** The device side of the screen capabilities, replaceable in tests. */
interface ScreenPort {
    fun available(): Boolean
    fun read(): ScreenWindow
    fun click(packageName: String, target: ScreenTarget): ScreenNode
    fun back(packageName: String)
    fun home(packageName: String)
}

class AndroidScreenPort(private val context: Context) : ScreenPort {
    override fun available() = ScreenAccessService.connected()
    override fun read() = ProgramScreen.read(context)
    override fun click(packageName: String, target: ScreenTarget) = ProgramScreen.click(context, packageName, target)
    override fun back(packageName: String) = ProgramScreen.global(context, packageName, AccessibilityService.GLOBAL_ACTION_BACK)
    override fun home(packageName: String) = ProgramScreen.global(context, packageName, AccessibilityService.GLOBAL_ACTION_HOME)
}

/** Where window changes come from; the platform listens while it runs and asks for content changes only when subscribed. */
interface ScreenEventSource {
    fun listen(listener: ((ScreenChange) -> Unit)?)
    fun wantContent(wanted: Boolean)
}

object AndroidScreenEvents : ScreenEventSource {
    override fun listen(listener: ((ScreenChange) -> Unit)?) { ScreenEvents.listener = listener }
    override fun wantContent(wanted: Boolean) { ScreenEvents.wantContent = wanted }
}

/**
 * `screen.read`, `screen.click`, `screen.back` and `screen.home`: the window in front, read fresh for every call, scoped
 * to the apps the program was granted (`"*"` for all). An action names the app it expects in front and is refused when
 * another window is there. Programs never read or act on mobby itself, and act only with the screen on and unlocked.
 */
class ScreenCapability(id: String, private val port: ScreenPort) : CapabilityHandler {
    private val action = id.substringAfter("screen.")

    init { require(action in setOf("read", "click", "back", "home")) }

    override val descriptor = CapabilityDescriptor(id, 1, if (action == "read") CapabilityKind.QUERY else CapabilityKind.COMMAND,
        when (action) {
            "read" -> schema(emptyList(), "package" to stringType(255))
            "click" -> schema(listOf("package"), "package" to stringType(255), "text" to stringType(200), "textContains" to stringType(200),
                "description" to stringType(200), "descriptionContains" to stringType(200), "viewId" to stringType(255), "className" to stringType(255))
            else -> schema(listOf("package"), "package" to stringType(255))
        },
        buildJsonObject { put("type", "object") }, ScopeKind.PACKAGES,
        if (action == "read") setOf(SideEffect.READ) else setOf(SideEffect.CONTROL), emptyList(),
        setOf(Surface.BACKGROUND), 5_000, false, CapabilityLimits(2_048, if (action == "read") 240 else 120))

    override fun availability(): Availability =
        if (port.available()) Availability.Available else Availability.Unavailable(AvailabilityReason.SYSTEM_PERMISSION_MISSING)

    override fun callScope(args: JsonObject): CallScope =
        CallScope(packageName = args.text("package") ?: if (action == "read") Scope.ANY_PACKAGE else invalid("package_required"))

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome = try {
        val packageName = call.args.text("package")
        runInterruptible(Dispatchers.IO) {
            when (action) {
                "read" -> {
                    val window = port.read()
                    if (packageName != null && window.packageName != packageName)
                        throw CapabilityException(ProgramErrorCode.STALE_OBSERVATION, "window_is_${window.packageName}")
                    if (!call.allows(CallScope(packageName = window.packageName)))
                        throw CapabilityException(ProgramErrorCode.SCOPE_DENIED, "window_app_not_granted: ${window.packageName}")
                    CapabilityOutcome(Effect.NONE, window.toJson())
                }
                "click" -> {
                    val target = ScreenTarget(call.args.text("text"), call.args.text("textContains"), call.args.text("description"),
                        call.args.text("descriptionContains"), call.args.text("viewId"), call.args.text("className"))
                    if (target.empty) invalid("click_needs_text_description_view_id_or_class_name")
                    CapabilityOutcome(Effect.CONFIRMED, buildJsonObject { put("clicked", port.click(packageName!!, target).toJson()) })
                }
                "back" -> { port.back(packageName!!); CapabilityOutcome(Effect.CONFIRMED, JsonObject(emptyMap())) }
                else -> { port.home(packageName!!); CapabilityOutcome(Effect.CONFIRMED, JsonObject(emptyMap())) }
            }
        }
    } catch (refused: ScreenRefused) {
        throw CapabilityException(when (refused.reason) {
            ScreenRefusal.SERVICE_OFF -> ProgramErrorCode.SOURCE_DISCONNECTED
            ScreenRefusal.OWN_APP -> ProgramErrorCode.SCOPE_DENIED
            ScreenRefusal.WINDOW_CHANGED -> ProgramErrorCode.STALE_OBSERVATION
            ScreenRefusal.NOT_FOUND -> ProgramErrorCode.RESOURCE_MISSING
            ScreenRefusal.NOT_UNIQUE -> ProgramErrorCode.TARGET_NOT_UNIQUE
            ScreenRefusal.FAILED -> ProgramErrorCode.FAILED
            ScreenRefusal.NO_WINDOW, ScreenRefusal.SCREEN_OFF, ScreenRefusal.LOCKED, ScreenRefusal.BUSY -> ProgramErrorCode.CONDITION_UNMET
        }, refused.message.orEmpty(), if (refused.reason == ScreenRefusal.FAILED) Effect.UNKNOWN else Effect.NONE)
    }
}

/**
 * `screen.window`: an app's window came to the front (`change: "window"`) or, when the filter asks with `"content": true`,
 * its content changed. Metadata only; read the nodes with `screen.read`. The filter may narrow `packages` to apps within
 * the requested scope.
 */
class ScreenWindowCapability : CapabilityHandler, EventCapability {
    override val descriptor = CapabilityDescriptor(ID, 1, CapabilityKind.EVENT,
        buildJsonObject { put("type", "object") }, buildJsonObject { put("type", "object") }, ScopeKind.PACKAGES, setOf(SideEffect.READ),
        emptyList(), setOf(Surface.BACKGROUND), 1_000, false, CapabilityLimits(2_048, 60))

    override fun availability(): Availability = Availability.Available

    override fun validateFilter(filter: JsonObject, requested: Scope): List<String> = buildList {
        (filter.keys - setOf("packages", "content")).forEach { add("unknown filter field $it; use packages and content") }
        filter["content"]?.let { if ((it as? JsonPrimitive)?.booleanOrNull == null) add("content must be true or false") }
        filter["packages"]?.let { value ->
            val names = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (names == null || names.isEmpty() || names.any { it == null }) add("packages must be a non-empty array of app package names")
            else names.filterNotNull().forEach { name ->
                if (name == Scope.ANY_PACKAGE) add("leave packages out to receive every app in the requested scope")
                else if (!requested.allowsPackage(name)) add("package $name is not in the scope requested for $ID")
            }
        }
    }

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome =
        throw CapabilityException(ProgramErrorCode.UNSUPPORTED, "event_capabilities_are_subscribed_not_called")

    companion object {
        const val ID = "screen.window"

        /** Whether a subscription's [filter] lets [change] through; the grant is checked separately. */
        fun accepts(filter: JsonObject, change: ScreenChange): Boolean {
            if (change.content && (filter["content"] as? JsonPrimitive)?.booleanOrNull != true) return false
            val packages = (filter["packages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return true
            return change.packageName in packages
        }

        fun payload(change: ScreenChange) = buildJsonObject {
            put("packageName", change.packageName)
            change.className?.let { put("className", it) }
            change.previousPackage?.let { put("previousPackage", it) }
            put("change", if (change.content) "content" else "window")
            put("at", change.at)
        }
    }
}

val SCREEN_CAPABILITIES = listOf("screen.read", "screen.click", "screen.back", "screen.home")

private fun ScreenNode.toJson() = buildJsonObject {
    put("index", index)
    parent?.let { put("parent", it) }
    put("className", className)
    viewId?.let { put("viewId", it) }
    text?.let { put("text", it) }
    description?.let { put("description", it) }
    put("clickable", clickable)
    if (editable) put("editable", true)
    put("visible", visible)
    put("bounds", JsonArray(bounds.map(::JsonPrimitive)))
}

private fun ScreenWindow.toJson() = buildJsonObject {
    put("packageName", packageName)
    className?.let { put("className", it) }
    put("readAt", readAt)
    put("truncated", truncated)
    put("nodes", JsonArray(nodes.map { it.toJson() }))
}
