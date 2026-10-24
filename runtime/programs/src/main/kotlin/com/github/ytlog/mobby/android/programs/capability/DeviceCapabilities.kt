package com.github.ytlog.mobby.android.programs.capability

import android.content.Context
import com.github.ytlog.mobby.android.device.DeviceExecutionContext
import com.github.ytlog.mobby.android.device.DeviceExecutors
import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.DeviceFailure
import com.github.ytlog.mobby.android.runtime.api.device.DeviceResult
import com.github.ytlog.mobby.android.runtime.api.device.EffectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.CancellationException

/** Phone capabilities that reuse the existing device executor unchanged. */
internal data class DeviceCapabilitySpec(
    val id: String, val plugin: String, val action: String, val kind: CapabilityKind,
    val sideEffects: Set<SideEffect>, val timeoutMillis: Long = 15_000,
)

internal val PHASE_ONE_DEVICE_CAPABILITIES = listOf(
    DeviceCapabilitySpec("sms.list", "sms", "list", CapabilityKind.QUERY, setOf(SideEffect.READ)),
    DeviceCapabilitySpec("sms.send", "sms", "send", CapabilityKind.COMMAND, setOf(SideEffect.SEND), 60_000),
    DeviceCapabilitySpec("contacts.list", "contacts", "list", CapabilityKind.QUERY, setOf(SideEffect.READ)),
    DeviceCapabilitySpec("contacts.create", "contacts", "create", CapabilityKind.COMMAND, setOf(SideEffect.WRITE)),
    DeviceCapabilitySpec("contacts.update", "contacts", "update", CapabilityKind.COMMAND, setOf(SideEffect.WRITE)),
    DeviceCapabilitySpec("contacts.delete", "contacts", "delete", CapabilityKind.COMMAND, setOf(SideEffect.DELETE)),
    DeviceCapabilitySpec("calendar.list", "calendar", "list", CapabilityKind.QUERY, setOf(SideEffect.READ)),
    DeviceCapabilitySpec("calendar.create", "calendar", "create", CapabilityKind.COMMAND, setOf(SideEffect.WRITE)),
    DeviceCapabilitySpec("calendar.update", "calendar", "update", CapabilityKind.COMMAND, setOf(SideEffect.WRITE)),
    DeviceCapabilitySpec("calendar.delete", "calendar", "delete", CapabilityKind.COMMAND, setOf(SideEffect.DELETE)),
    DeviceCapabilitySpec("clipboard.read", "clipboard", "read", CapabilityKind.QUERY, setOf(SideEffect.READ)),
    DeviceCapabilitySpec("clipboard.write", "clipboard", "write", CapabilityKind.COMMAND, setOf(SideEffect.WRITE)),
    DeviceCapabilitySpec("location.current", "location", "current", CapabilityKind.QUERY, setOf(SideEffect.READ), 30_000),
    DeviceCapabilitySpec("sensors.sample", "sensors", "sample", CapabilityKind.QUERY, setOf(SideEffect.READ)),
    DeviceCapabilitySpec("device.storage.list", "storage", "list", CapabilityKind.QUERY, setOf(SideEffect.READ)),
    DeviceCapabilitySpec("device.storage.copy", "storage", "copy", CapabilityKind.COMMAND, setOf(SideEffect.READ)),
    DeviceCapabilitySpec("device.storage.export", "storage", "export", CapabilityKind.COMMAND, setOf(SideEffect.WRITE)),
    DeviceCapabilitySpec("office.inspect", "office", "inspect", CapabilityKind.QUERY, setOf(SideEffect.READ)),
    DeviceCapabilitySpec("office.read", "office", "read", CapabilityKind.QUERY, setOf(SideEffect.READ)),
    DeviceCapabilitySpec("office.write", "office", "write", CapabilityKind.COMMAND, setOf(SideEffect.WRITE)),
)

/** Seam over [DeviceExecutors] so the mapping is testable without device services. */
internal interface DevicePort {
    fun validate(plugin: String, action: String, args: JsonObject): Map<String, String>
    fun systemGranted(plugin: String, action: String): Boolean
    fun perform(plugin: String, action: String, args: Map<String, String>, execution: DeviceExecutionContext): DeviceResult
}

internal class AndroidDevicePort(private val context: Context) : DevicePort {
    override fun validate(plugin: String, action: String, args: JsonObject) = DeviceExecutors.validate(plugin, action, args)
    override fun systemGranted(plugin: String, action: String) = DeviceExecutors.systemGranted(context, plugin, action)
    override fun perform(plugin: String, action: String, args: Map<String, String>, execution: DeviceExecutionContext) =
        DeviceExecutors.perform(context, plugin, action, args, execution)
}

internal class DeviceCapabilityHandler(private val spec: DeviceCapabilitySpec, private val device: DevicePort) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor(
        id = spec.id, version = 1, kind = spec.kind,
        inputSchema = inputSchema(spec), outputSchema = OUTPUT_SCHEMA,
        scope = ScopeKind.NONE, sideEffects = spec.sideEffects,
        prerequisites = listOf(Prerequisite("device-plugin", spec.plugin)),
        surfaces = setOf(Surface.BACKGROUND, Surface.UI),
        timeoutMillis = spec.timeoutMillis, cancellable = true,
        limits = CapabilityLimits(maxArgumentBytes = 16 * 1024, callsPerMinute = 60),
    )

    override fun availability(): Availability =
        if (device.systemGranted(spec.plugin, spec.action)) Availability.Available
        else Availability.Unavailable(AvailabilityReason.SYSTEM_PERMISSION_MISSING)

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome = withContext(Dispatchers.IO) {
        val args = try { device.validate(spec.plugin, spec.action, call.args) } catch (failure: DeviceFailure) { throw failure.toCapability() }
        val execution = DeviceExecutionContext(call.space.temp, call.space.files, call.isCancelled) { file, _ -> relative(call.space, file) }
        val result = try { device.perform(spec.plugin, spec.action, args, execution) }
            catch (failure: DeviceFailure) { throw failure.toCapability() }
            catch (cancelled: CancellationException) { throw CapabilityException(ProgramErrorCode.CANCELLED, "cancelled") }
            catch (failure: IllegalStateException) { throw CapabilityException(ProgramErrorCode.FAILED, failure.message ?: "device_error") }
            catch (failure: IllegalArgumentException) { throw CapabilityException(ProgramErrorCode.INVALID_ARGUMENT, failure.message ?: "invalid_argument") }
        CapabilityOutcome(result.effectState.toEffect(), buildJsonObject {
            put("kind", result.kind)
            put("data", result.data)
            put("files", JsonArray(result.resourceRefs.map(::JsonPrimitive)))
        })
    }

    companion object {
        private val OUTPUT_SCHEMA = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("kind", buildJsonObject { put("type", "string") })
                put("data", buildJsonObject { put("type", "object") })
                put("files", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
            })
        }

        /** Generated from the device action's own argument rules, so the two cannot drift apart. */
        fun inputSchema(spec: DeviceCapabilitySpec): JsonObject {
            val arguments = DeviceExecutors.arguments(spec.plugin, spec.action)
            return buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", buildJsonObject {
                    arguments.maxLengths.forEach { (name, max) ->
                        put(name, buildJsonObject {
                            if (arguments.numericCoordinates) put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("number"))))
                            else put("type", "string")
                            put("maxLength", max)
                        })
                    }
                })
                put("required", JsonArray((arguments.maxLengths.keys - arguments.optional).sorted().map(::JsonPrimitive)))
            }
        }

        /** Produced files are named relative to the program space, never by absolute path. */
        internal fun relative(space: ProgramSpace, file: File): String {
            val canonical = file.canonicalFile
            for ((prefix, root) in listOf("tmp" to space.temp, "files" to space.files)) {
                val base = root.canonicalFile
                if (canonical.path.startsWith(base.path + File.separator)) return "$prefix/" + canonical.relativeTo(base).invariantSeparatorsPath
            }
            throw CapabilityException(ProgramErrorCode.FAILED, "file_outside_program_space")
        }
    }
}

internal fun EffectState.toEffect() = when (this) {
    EffectState.NONE -> Effect.NONE
    EffectState.SUBMITTED -> Effect.SUBMITTED
    EffectState.PARTIAL -> Effect.PARTIAL
    EffectState.CONFIRMED -> Effect.CONFIRMED
    EffectState.UNKNOWN -> Effect.UNKNOWN
}

internal fun DeviceFailure.toCapability(): CapabilityException {
    val code = when (error.code) {
        DeviceErrorCode.INVALID_ARGUMENT -> ProgramErrorCode.INVALID_ARGUMENT
        DeviceErrorCode.UNAUTHORIZED, DeviceErrorCode.PERMISSION_REVOKED -> ProgramErrorCode.PERMISSION_DENIED
        DeviceErrorCode.UNSUPPORTED_CAPABILITY, DeviceErrorCode.INCOMPATIBLE_VERSION -> ProgramErrorCode.UNSUPPORTED
        DeviceErrorCode.REQUEST_CONFLICT -> ProgramErrorCode.REQUEST_CONFLICT
        DeviceErrorCode.NOT_FOUND, DeviceErrorCode.RESOURCE_MISSING -> ProgramErrorCode.RESOURCE_MISSING
        DeviceErrorCode.UNAVAILABLE -> ProgramErrorCode.SOURCE_DISCONNECTED
        DeviceErrorCode.TIMEOUT -> ProgramErrorCode.TIMEOUT
        DeviceErrorCode.CANCELLED, DeviceErrorCode.INTERRUPTED -> ProgramErrorCode.CANCELLED
        DeviceErrorCode.RESULT_UNCONFIRMED -> ProgramErrorCode.EFFECT_UNKNOWN
        DeviceErrorCode.STORAGE_FULL -> ProgramErrorCode.QUOTA_EXCEEDED
        DeviceErrorCode.STALE_DEVICE_PROMPT -> ProgramErrorCode.STALE_OBSERVATION
        DeviceErrorCode.PROTOCOL_ERROR -> ProgramErrorCode.FAILED
    }
    return CapabilityException(code, error.message.ifBlank { error.code.name.lowercase() }, error.effectState.toEffect())
}
