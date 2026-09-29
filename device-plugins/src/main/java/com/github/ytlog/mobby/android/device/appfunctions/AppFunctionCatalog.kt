package com.github.ytlog.mobby.android.device.appfunctions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.metadata.AppFunctionMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.Base64

data class AppFunctionEntry(
    val ref: String,
    val packageName: String,
    val appName: String,
    val functionId: String,
    val description: String,
    val enabled: Boolean,
    val unavailableReason: String?,
    val parameters: List<AppFunctionParameter>,
)

data class AppFunctionParameter(val name: String, val description: String, val required: Boolean, val type: String)

sealed interface AppFunctionListing {
    data class Available(val functions: List<AppFunctionEntry>) : AppFunctionListing
    data class Unavailable(val reason: Reason) : AppFunctionListing
    enum class Reason { UNSUPPORTED_DEVICE, PERMISSION_DENIED, SYSTEM_DENIED, QUERY_FAILED }
}

/** Identity is package plus the exact declared function ID, never a display name. */
object AppFunctionRefs {
    const val PREFIX = "plugin:appfunction:"
    fun encode(packageName: String, functionId: String): String {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")))
        require(functionId.isNotBlank() && functionId.length <= 400 && '\n' !in functionId && '\u0000' !in functionId)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString("$packageName\n$functionId".toByteArray(Charsets.UTF_8))
    }
    fun decode(ref: String): Pair<String, String>? = runCatching {
        if (!ref.startsWith(PREFIX) || ref.length > 1024) return@runCatching null
        val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Base64.getUrlDecoder().decode(ref.removePrefix(PREFIX)))).toString()
        val packageName = decoded.substringBefore('\n')
        val functionId = decoded.substringAfter('\n', "")
        if (encode(packageName, functionId) == ref) packageName to functionId else null
    }.getOrNull()
}

class AppFunctionCatalog(private val context: Context) {
    fun isSupported(): Boolean = manager() != null

    fun manager(): AppFunctionManager? {
        if (Build.VERSION.SDK_INT < 34) return null
        return AppFunctionManager.getInstance(context)
    }

    suspend fun list(): AppFunctionListing {
        val manager = manager() ?: return AppFunctionListing.Unavailable(AppFunctionListing.Reason.UNSUPPORTED_DEVICE)
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
            return AppFunctionListing.Unavailable(AppFunctionListing.Reason.PERMISSION_DENIED)
        return try {
            val metadata = withTimeout(10_000) { manager.observeAppFunctions(AppFunctionSearchSpec()).first() }
                .flatMap { it.appFunctions }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName to it.id }
            AppFunctionListing.Available(metadata.map { item ->
                val reason = when {
                    !item.isEnabled -> "disabled"
                    AppFunctionJson.unsupportedType(item) != null -> "unsupported_type"
                    else -> null
                }
                AppFunctionEntry(
                    AppFunctionRefs.encode(item.packageName, item.id), item.packageName,
                    appLabel(item.packageName), item.id, item.description,
                    reason == null, reason,
                    item.parameters.map { AppFunctionParameter(it.name, it.description, it.isRequired, it.dataType.javaClass.simpleName.removePrefix("AppFunction").removeSuffix("TypeMetadata")) },
                )
            }.sortedWith(compareBy<AppFunctionEntry> { it.appName }.thenBy { it.functionId }))
        } catch (_: SecurityException) {
            AppFunctionListing.Unavailable(AppFunctionListing.Reason.SYSTEM_DENIED)
        } catch (_: Exception) {
            AppFunctionListing.Unavailable(AppFunctionListing.Reason.QUERY_FAILED)
        }
    }

    suspend fun isAvailable(ref: String): Boolean = resolve(ref) != null

    internal suspend fun resolve(ref: String): AppFunctionMetadata? {
        val (packageName, functionId) = AppFunctionRefs.decode(ref) ?: return null
        val manager = manager() ?: return null
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return null
        val metadata = withTimeout(10_000) { manager.observeAppFunctions(AppFunctionSearchSpec(packageNames = setOf(packageName))).first() }
            .flatMap { it.appFunctions }
            .firstOrNull { it.packageName == packageName && it.id == functionId }
            ?: return null
        val enabled = metadata.isEnabled && withTimeout(10_000) { manager.isAppFunctionEnabled(packageName, functionId) }
        return metadata.takeIf { enabled && AppFunctionJson.unsupportedType(it) == null }
    }

    private fun appLabel(packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object { const val PERMISSION = "android.permission.EXECUTE_APP_FUNCTIONS" }
}
