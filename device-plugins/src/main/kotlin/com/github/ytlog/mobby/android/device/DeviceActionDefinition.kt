package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.deviceinteraction.model.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Extends the actual execution catalog; the UI has no second executable capability list. */
internal class DeviceActionDefinition(val plugin: String, val action: String) {
    private val spec = DeviceCatalog.all.firstOrNull { it.id == plugin }
        ?: deviceFailure(DeviceErrorCode.UNSUPPORTED_CAPABILITY)
    private val command = spec.commands.firstOrNull { it.action == action }
        ?: deviceFailure(DeviceErrorCode.UNSUPPORTED_CAPABILITY)
    val writes = (plugin == "screen" && action != "snapshot") || command.grant || (plugin == "storage" && action == "export") || (plugin == "office" && action == "write")
    val displayType = when (plugin) {
        "screen" -> "screen_control"
        "sms" -> if (action == "send") "message_send" else "message_list"
        "media" -> "media_grid"
        "storage" -> if (action == "list") "file_list" else "file_transfer"
        "camera", "microphone" -> "capture"
        "location", "sensors" -> "measurement"
        "contacts", "calendar" -> if (action == "list") "file_list" else "record_change"
        "office", "clipboard" -> if (action == "write") "record_change" else "basic"
        else -> "basic"
    }
    private val fields: Map<String, Int> = when ("$plugin.$action") {
        "screen.click" -> mapOf("query" to 200)
        "screen.type" -> mapOf("text" to 2000)
        "screen.tap" -> mapOf("x" to 24, "y" to 24)
        "sms.send" -> mapOf("to" to 23, "body" to 500)
        "contacts.create" -> mapOf("name" to 80, "phone" to 40)
        "contacts.update" -> mapOf("id" to 20, "name" to 80)
        "contacts.delete", "calendar.delete" -> mapOf("id" to 20)
        "calendar.create" -> mapOf("title" to 120, "start" to 20, "end" to 20)
        "calendar.update" -> mapOf("id" to 20, "title" to 120)
        "media.copy" -> mapOf("kind" to 5, "id" to 20)
        "storage.copy" -> mapOf("name" to 120)
        "storage.export" -> mapOf("from" to 4000, "name" to 120)
        "clipboard.write" -> mapOf("text" to 4000)
        "office.inspect", "office.read" -> mapOf("path" to 4000)
        "office.write" -> mapOf("path" to 4000, "text" to 4000)
        else -> emptyMap()
    }
    fun validate(args: JsonObject): Map<String, String> {
        if (args.keys.any { it !in fields }) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT, "Unknown action argument")
        val result = args.mapValues { (key, value) ->
            val primitive = value as? JsonPrimitive ?: deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
            if (primitive == JsonNull || (!primitive.isString && !(plugin == "screen" && action == "tap")))
                deviceFailure(DeviceErrorCode.INVALID_ARGUMENT, "Invalid argument type")
            primitive.content.also { if (it.length > fields.getValue(key) || '\u0000' in it) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT) }
        }
        val optional = if (plugin == "contacts" && action == "create") setOf("phone") else emptySet()
        if ((fields.keys - optional).any { it !in result || (result[it].isNullOrBlank() && !(plugin == "office" && it == "text")) })
            deviceFailure(DeviceErrorCode.INVALID_ARGUMENT, "Missing action argument")
        if ("id" in result && (result["id"]?.toLongOrNull()?.let { it >= 0 } != true)) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
        if (plugin == "screen" && action == "tap" && result.values.any { it.toDoubleOrNull()?.let { n -> n.isFinite() && n in 0.0..1.0 } != true }) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
        if (plugin == "sms" && action == "send" && !result.getValue("to").matches(Regex("[+0-9][0-9\\- ]{2,20}"))) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
        if (plugin == "calendar" && action == "create") {
            val start = result["start"]?.toLongOrNull(); val end = result["end"]?.toLongOrNull()
            if (start == null || end == null || start < 0 || end < start) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
        }
        if (plugin == "media" && action == "copy" && result["kind"] !in setOf("image", "video", "audio")) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
        return result
    }
    fun admission(request: DeviceRequest, args: Map<String, String>): DeviceAdmission {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(buildJsonObject {
            put("plugin", plugin); put("action", action)
            put("args", JsonObject(request.args.toSortedMap()))
        }.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val input = if (plugin == "sms" && action == "send") buildJsonObject {
            put("recipientLabel", ""); put("recipientMasked", args.getValue("to").filter(Char::isDigit).takeLast(4)); put("body", args.getValue("body"))
        } else JsonObject(request.args.filterKeys { it !in setOf("to", "phone") })
        val label = args["name"] ?: args["title"] ?: args["query"] ?: args["path"]?.substringAfterLast('/') ?: ""
        return DeviceAdmission(request.requestId, fingerprint, plugin, action, displayType, DeviceSubject(label.take(1000), ""), input)
    }
}
