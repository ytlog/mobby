package com.github.ytlog.mobby.android.conversation.ui

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.ytlog.mobby.android.device.ScreenAccessService
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

internal enum class DeviceSettingsFamily {
    XIAOMI, OPPO, VIVO, SAMSUNG, HONOR, HUAWEI, ANDROID;
    fun label(): String = when (this) {
        XIAOMI -> "Xiaomi / Redmi / POCO · MIUI / HyperOS"
        OPPO -> "OPPO / OnePlus / realme"
        VIVO -> "vivo / iQOO"
        SAMSUNG -> "Samsung · One UI"
        HONOR -> "HONOR · MagicOS"
        HUAWEI -> "Huawei · Android / EMUI"
        ANDROID -> "Pixel / Android"
    }
    fun instructions(): String = when (this) {
        XIAOMI -> AppStrings.developerXiaomi
        OPPO -> AppStrings.developerOppo
        VIVO -> AppStrings.developerVivo
        SAMSUNG -> AppStrings.developerSamsung
        HONOR -> AppStrings.developerHonor
        HUAWEI -> AppStrings.developerHuawei
        ANDROID -> AppStrings.developerAndroid
    }
    companion object {
        fun detect(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): DeviceSettingsFamily {
            val names = setOf(manufacturer.lowercase(java.util.Locale.ROOT), brand.lowercase(java.util.Locale.ROOT))
            return when {
                names.any { it in setOf("xiaomi", "redmi", "poco") } -> XIAOMI
                names.any { it in setOf("oppo", "oneplus", "realme") } -> OPPO
                names.any { it in setOf("vivo", "iqoo") } -> VIVO
                "samsung" in names -> SAMSUNG
                "honor" in names -> HONOR
                "huawei" in names -> HUAWEI
                else -> ANDROID
            }
        }
    }
}

internal data class ScreenSetupStatus(
    val developerEnabled: Boolean? = null,
    val wirelessEnabled: Boolean? = null,
    val notificationsAllowed: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val sdk: Int = Build.VERSION.SDK_INT,
    val resumed: Boolean = true,
) {
    companion object {
        fun read(context: Context, pairing: Boolean = true): ScreenSetupStatus {
            fun flag(name: String): Boolean? = try { Settings.Global.getInt(context.contentResolver, name) == 1 }
                catch (_: Settings.SettingNotFoundException) { null }
                catch (_: SecurityException) { null }
            val notifications = context.getSystemService(NotificationManager::class.java)
            val channels = if (pairing) listOf("runtime", "screen-adb-pairing") else listOf("runtime")
            val allowed = notifications.areNotificationsEnabled() && channels.none {
                notifications.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
            }
            val expected = ComponentName(context, ScreenAccessService::class.java)
            val accessibility = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                    .split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
            val network = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION") val wifi = network.allNetworks.any { network.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            return ScreenSetupStatus(flag(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), flag("adb_wifi_enabled"), allowed, accessibility, wifi)
        }
    }
}

@Composable internal fun rememberScreenSetupStatus(refresh: Int = 0, pairing: Boolean = true): ScreenSetupStatus {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember(context, pairing) { mutableStateOf(ScreenSetupStatus.read(context, pairing).copy(resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) }
    LaunchedEffect(refresh, pairing) { status = ScreenSetupStatus.read(context, pairing).copy(resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(context, lifecycle, pairing) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = ScreenSetupStatus.read(context, pairing)
            else if (event == Lifecycle.Event.ON_PAUSE) status = status.copy(resumed = false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return status
}

internal enum class ScreenSettingsTarget { ABOUT, DEVELOPER, WIRELESS, WIFI, NOTIFICATIONS, ACCESSIBILITY }

/** Try a device's exported shortcut, then a standard Settings destination. No privileged setting writes. */
internal fun screenSettingsIntents(context: Context, target: ScreenSettingsTarget, pairing: Boolean = true): List<Intent> = when (target) {
    ScreenSettingsTarget.ABOUT -> listOf(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    ScreenSettingsTarget.DEVELOPER -> listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    ScreenSettingsTarget.WIRELESS -> listOf(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"), Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    ScreenSettingsTarget.WIFI -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS))
    ScreenSettingsTarget.ACCESSIBILITY -> listOf(
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, ScreenAccessService::class.java).flattenToString()),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )
    ScreenSettingsTarget.NOTIFICATIONS -> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = if (pairing) listOf("screen-adb-pairing", "runtime") else listOf("runtime")
        val blocked = channels.firstOrNull { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
        if (manager.areNotificationsEnabled() && blocked != null) listOf(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, blocked))
        else listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
} + Intent(Settings.ACTION_SETTINGS)

internal fun openScreenSettings(context: Context, target: ScreenSettingsTarget, pairing: Boolean = true): Boolean {
    for (intent in screenSettingsIntents(context, target, pairing)) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) { }
        catch (_: SecurityException) { }
    }
    return false
}
