package com.github.ytlog.mobby.android.device

import android.content.Context
import com.github.ytlog.mobby.android.runtime.api.device.DeviceResult
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Transport-independent host for callers other than the chat bridge. [tempDir] receives imported
 * files, [workDir] is where relative paths resolve, and [registerFile] names a produced file.
 */
class DeviceExecutionContext(
    val tempDir: File,
    val workDir: File,
    val isCancelled: () -> Boolean,
    val registerFile: (File, String) -> String,
)

/** Argument contract of one existing device action. */
data class DeviceArguments(val maxLengths: Map<String, Int>, val optional: Set<String>, val numericCoordinates: Boolean)

/** The single device executor, shared with the chat bridge through [DeviceActions]. */
object DeviceExecutors {
    fun arguments(plugin: String, action: String): DeviceArguments {
        val definition = DeviceActionDefinition(plugin, action)
        return DeviceArguments(definition.arguments, definition.optionalArguments, plugin == "screen" && action == "tap")
    }

    /** Same validation the chat bridge applies; throws a device failure on invalid input. */
    fun validate(plugin: String, action: String, args: JsonObject): Map<String, String> = DeviceActionDefinition(plugin, action).validate(args)

    /** Whether the Android side currently allows the action, using the same rule as chat runs. */
    fun systemGranted(context: Context, plugin: String, action: String): Boolean {
        val spec = DeviceCatalog.all.firstOrNull { it.id == plugin } ?: return false
        val ref = if (action in spec.grant?.actions.orEmpty()) spec.grantRef!! else spec.ref
        return DeviceHost.granted(context, ref)
    }

    fun perform(context: Context, plugin: String, action: String, args: Map<String, String>, execution: DeviceExecutionContext): DeviceResult {
        execution.tempDir.mkdirs()
        val registrar = DeviceResourceRegistrar(execution.registerFile)
        val actions = DeviceActions(context, execution.tempDir, execution.workDir, DeviceActionGate(execution.isCancelled), registrar, registrar)
        return actions.perform(plugin, action, args, DeviceExecution("program") { _, _, _ -> })
    }
}
