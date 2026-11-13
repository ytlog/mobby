package com.github.ytlog.mobby.android.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A short, user-started trip through Settings. The system's permission controls stay with the user. */
@android.annotation.TargetApi(33)
internal class AdbSettingsAssistant(private val service: ScreenAccessService) {
    private var task: Job? = null
    private val settingsPackage by lazy {
        val manager = service.packageManager
        manager.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_SYSTEM_ONLY)
            ?.activityInfo?.packageName?.takeUnless { it == "android" } ?: try {
            val info = manager.getApplicationInfo("com.android.settings", 0)
            info.packageName.takeIf { info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
        } catch (_: PackageManager.NameNotFoundException) { null }
    }
    private val settingsResources by lazy {
        try { settingsPackage?.let(service.packageManager::getResourcesForApplication) }
        catch (_: PackageManager.NameNotFoundException) { null }
    }
    private val developerLabels by lazy { systemLabels("development_settings_title", DEVELOPER_LABELS) }
    private val wirelessLabels by lazy { systemLabels("adb_wireless_settings", WIRELESS) }
    private val pairLabels by lazy { systemLabels("adb_pair_method_code_title", PAIR) }
    private val dialogTitles by lazy { systemLabels("adb_pairing_device_dialog_title", PAIR_DIALOG) }
    private val codeLabels by lazy { systemLabels("adb_pairing_device_dialog_pairing_code_label", CODE_LABELS) }
    private val addressLabels by lazy { systemLabels("adb_wireless_ip_addr_preference_title", CONNECTION_ADDRESS) }

    @android.annotation.SuppressLint("DiscouragedApi") // Known strings in the system Settings APK.
    private fun systemLabels(name: String, aliases: List<String>): List<String> {
        val resources = settingsResources ?: return aliases
        val id = resources.getIdentifier(name, "string", settingsPackage)
        if (id == 0) return aliases
        val label = try { resources.getString(id) } catch (_: Resources.NotFoundException) { return aliases }
        return (aliases + label).distinct()
    }

    fun start(destination: ScreenAccessService.AdbSetupDestination) {
        val timeout = if (destination == ScreenAccessService.AdbSetupDestination.PAIR) 30_000L else 10_000L
        withSettingsWindow(timeout) { navigate(destination) }
    }

    fun returnAfterPairing(component: ComponentName) {
        if (component.packageName == service.packageName) withSettingsWindow(3_000) { returnToWizard(component) }
    }

    private fun withSettingsWindow(timeout: Long, action: suspend () -> Unit) {
        val previous = task
        stop()
        if (Build.VERSION.SDK_INT < 33 || ScreenCoordinator.chatActive || settingsPackage == null) return
        task = CoroutineScope(Dispatchers.Main).launch {
            previous?.join()
            val flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            val info = service.serviceInfo ?: return@launch
            val addedFlags = flags and info.flags.inv()
            if (addedFlags != 0) {
                info.flags = info.flags or addedFlags
                service.serviceInfo = info
            }
            try {
                withTimeoutOrNull(timeout) { action() }
            } finally {
                if (addedFlags != 0) service.serviceInfo?.let {
                    it.flags = it.flags and addedFlags.inv()
                    service.serviceInfo = it
                }
            }
        }
    }

    fun stop() { task?.cancel(); task = null }

    private suspend fun navigate(destination: ScreenAccessService.AdbSetupDestination) {
        var enteredSettings = false
        var openedPage = false
        var awaitingPairingDialog = false
        var scrolls = 0
        while (true) {
            delay(if (enteredSettings) 350 else 200)
            if (!screenAvailable() || destination == ScreenAccessService.AdbSetupDestination.PAIR &&
                !ScreenControl.state.value.adbPairing) return
            val root = service.getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) ?: continue
            val result = try {
                val packageName = root.packageName?.toString()
                if (packageName != settingsPackage) {
                    if (enteredSettings || packageName != service.packageName) return
                    continue
                }
                val foreground = isForeground(root) ?: continue
                if (!foreground) {
                    if (enteredSettings) return
                    continue
                }
                enteredSettings = true
                ScreenCoordinator.programAction {
                    if (destination == ScreenAccessService.AdbSetupDestination.PAIR) {
                        val result = readPairingDialog(root)
                        if (result != null) return@programAction result
                        wirelessConnectionPort(root)?.let { AdbPairingService.offerConnectionPort(it) }
                        if (awaitingPairingDialog) return@programAction Result.WAIT
                    }
                    visit(root, destination, openedPage, scrolls < 8)
                }
            } catch (_: ScreenRefused) {
                return
            } finally { root.recycle() }
            when (result) {
                Result.STOP -> if (destination == ScreenAccessService.AdbSetupDestination.PAIR) awaitingPairingDialog = true else return
                Result.PAIRING_SUBMITTED -> return
                Result.OPENED_PAGE -> { openedPage = true; delay(450) }
                Result.OPENED_PAIRING -> awaitingPairingDialog = true
                Result.SCROLLED -> scrolls++
                Result.WAIT -> Unit
            }
        }
    }

    private fun screenAvailable(): Boolean = service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun isForeground(root: AccessibilityNodeInfo): Boolean? {
        val window = root.window ?: return null
        return try {
            window.displayId == Display.DEFAULT_DISPLAY && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive
        } finally { window.recycle() }
    }

    private fun readPairingDialog(root: AccessibilityNodeInfo): Result? {
        // The dialog container is omitted from the normal accessibility tree on MIUI.
        val code = withSettingNode(root, PAIRING_CODE) { it.text?.toString().orEmpty() }
        val address = withSettingNode(root, PAIRING_ADDRESS) { it.text?.toString() }
        val fields = AdbPairingFields.parse(code, address) ?: run {
            if (!hasHeading(root, dialogTitles)) return if (code == null) null else Result.WAIT
            val text = dialogText(root) ?: return Result.WAIT
            AdbPairingFields.parseLabeled(text, codeLabels, addressLabels) ?: run {
                val port = AdbPairingFields.parsePort(address) ?: AdbPairingFields.parseLabeledPort(text, addressLabels)
                port?.let { AdbPairingService.offerSettingsPort(it) }
                return Result.WAIT
            }
        }
        return if (AdbPairingService.submitFromSettings(fields.port, fields.code)) Result.PAIRING_SUBMITTED
        else Result.WAIT
    }

    private fun dialogText(root: AccessibilityNodeInfo): List<String>? {
        val text = mutableListOf<String>()
        var remaining = 96
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (remaining-- <= 0 || depth > 12) return false
            if (!node.isVisibleToUser || node.packageName?.toString() != settingsPackage || node.windowId != root.windowId) return true
            if (node.isEditable || node.isPassword) return false
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(text::add)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (!visit(child, depth + 1)) return false } finally { child.recycle() }
            }
            return true
        }
        return if (visit(root, 0)) text else null
    }

    private fun wirelessConnectionPort(root: AccessibilityNodeInfo): Int? {
        val labels = exactNodes(root, addressLabels)
        return try {
            val title = labels.singleOrNull { it.viewIdResourceName == "android:id/title" } ?: return null
            connectionPortForLabel(title)
        } finally { labels.forEach(AccessibilityNodeInfo::recycle) }
    }

    private fun <T> withSettingNode(root: AccessibilityNodeInfo, id: String, read: (AccessibilityNodeInfo) -> T?): T? {
        val nodes = root.findAccessibilityNodeInfosByViewId("$settingsPackage:id/$id")
        return try {
            nodes.filter { it.isVisibleToUser && it.packageName?.toString() == settingsPackage && it.windowId == root.windowId }
                .singleOrNull()?.let(read)
        } finally { nodes.forEach(AccessibilityNodeInfo::recycle) }
    }

    private suspend fun returnToWizard(component: ComponentName) {
        // Settings can replace the dialog window immediately after accepting the pairing.
        repeat(8) {
            delay(200)
            if (ScreenAccessService.instance !== service || !screenAvailable() || ScreenCoordinator.chatActive) return
            val root = service.rootInActiveWindow ?: return@repeat
            try {
                val packageName = root.packageName?.toString()
                if (packageName == "com.android.systemui") return@repeat
                if (packageName != settingsPackage) return
                if (isForeground(root) != true ||
                    !(hasHeading(root, wirelessLabels) || wirelessConnectionPort(root) != null || hasHeading(root, dialogTitles) ||
                        withSettingNode(root, PAIRING_CODE) { true } == true)) return@repeat
                service.startActivity(AdbPairingService.wizardIntent(component))
                return
            } catch (_: android.content.ActivityNotFoundException) { return }
            catch (_: SecurityException) { return }
            finally { root.recycle() }
        }
    }

    private fun visit(root: AccessibilityNodeInfo, destination: ScreenAccessService.AdbSetupDestination,
        openedPage: Boolean, canScroll: Boolean): Result {
        if (destination == ScreenAccessService.AdbSetupDestination.VERSION) {
            if (hasLabel(root, VERSION_CONTROL)) return Result.STOP
            if (!hasHeading(root, ABOUT + SOFTWARE_DETAILS)) return Result.STOP
            if (!openedPage && hasHeading(root, ABOUT) && clickUnique(root, SOFTWARE_DETAILS)) return Result.OPENED_PAGE
            return scroll(root, canScroll)
        }
        if (hasHeading(root, developerLabels)) {
            if (openedPage) return Result.STOP
            val wireless = exactNodes(root, wirelessLabels)
            try {
                val entry = wireless.singleOrNull()
                if (entry != null && fullLabel(entry))
                    return if (clickPreference(entry)) Result.OPENED_PAGE else Result.STOP
                if (wireless.size <= 1) return scroll(root, canScroll)
            } finally { wireless.forEach(AccessibilityNodeInfo::recycle) }
            return Result.STOP
        }
        if (destination == ScreenAccessService.AdbSetupDestination.PAIR &&
            (hasHeading(root, wirelessLabels) || wirelessConnectionPort(root) != null || hasLabel(root, wirelessLabels)) &&
            Settings.Global.getInt(service.contentResolver, "adb_wifi_enabled", -1) != 0) {
            if (clickUnique(root, pairLabels)) return Result.OPENED_PAIRING
        }
        return Result.STOP
    }

    private fun fullLabel(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        return bounds.width() > 0 && bounds.height() >= 14 * service.resources.displayMetrics.density
    }

    private fun clickUnique(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val nodes = exactNodes(root, labels)
        return try { nodes.singleOrNull()?.takeIf(::fullLabel)?.let(::clickPreference) == true }
        finally { nodes.forEach(AccessibilityNodeInfo::recycle) }
    }

    private fun clickPreference(label: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(label)
        repeat(4) {
            val candidate = node ?: return false
            try {
                if (!candidate.isEnabled || candidate.isCheckable || candidate.isEditable || candidate.isScrollable) return false
                // Some ROMs put a toggle in the row itself. Leave that control to the user.
                if (candidate.isClickable)
                    return !containsSwitch(candidate, 0) && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = candidate.parent
            } finally { candidate.recycle() }
        }
        node?.recycle()
        return false
    }

    private fun containsSwitch(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (node.isCheckable || depth > 8) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { if (containsSwitch(child, depth + 1)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun scroll(root: AccessibilityNodeInfo, allowed: Boolean) =
        if (allowed && scrollForward(root, 0)) Result.SCROLLED else Result.STOP

    private fun scrollForward(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 12 || !node.isVisibleToUser) return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { if (scrollForward(child, depth + 1)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun hasLabel(root: AccessibilityNodeInfo, labels: List<String>): Boolean =
        exactNodes(root, labels).let { matches ->
            matches.forEach(AccessibilityNodeInfo::recycle)
            matches.isNotEmpty()
        }

    private fun hasHeading(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val window = root.window
        if (window != null) {
            val title = try { window.title?.toString()?.trim() } finally { window.recycle() }
            if (labels.any { it.equals(title, ignoreCase = true) }) return true
        }
        val nodes = exactNodes(root, labels)
        return try { nodes.any {
            val id = it.viewIdResourceName?.substringAfterLast('/')
            it.isHeading || id?.startsWith("action_bar_title") == true || id in HEADING_IDS
        } } finally { nodes.forEach(AccessibilityNodeInfo::recycle) }
    }

    private fun exactNodes(root: AccessibilityNodeInfo, labels: List<String>): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        for (label in labels) {
            for (node in root.findAccessibilityNodeInfosByText(label)) {
                if (node.isVisibleToUser && node.text?.toString()?.trim()?.equals(label, ignoreCase = true) == true &&
                    node.packageName?.toString() == settingsPackage && node.windowId == root.windowId && node !in matches) matches += node
                else node.recycle()
            }
        }
        return matches
    }

    private enum class Result { STOP, OPENED_PAGE, OPENED_PAIRING, SCROLLED, WAIT, PAIRING_SUBMITTED }

    companion object {
        private const val PAIRING_CODE = "pairing_code"
        private const val PAIRING_ADDRESS = "ip_addr"
        private val HEADING_IDS = setOf("toolbar_title", "collapsing_toolbar_title", "alertTitle", "dialog_title", "sesl_dialog_title")
        private val ABOUT = listOf("关于手机", "关于本机", "我的设备", "關於手機", "About phone", "About device", "My device")
        private val SOFTWARE_DETAILS = listOf("全部参数", "详细信息与规格", "软件信息", "版本信息", "版本",
            "All specs", "Detailed info and specs", "Software information", "Version information", "Version")
        private val VERSION_CONTROL = listOf("MIUI 版本", "MIUI version", "OS 版本", "OS version", "版本号", "软件版本号", "编译编号",
            "Build number", "Software version", "Version number")
        internal val DEVELOPER_LABELS = listOf("开发者选项", "开发人员选项", "開發人員選項", "開發者選項", "Developer options")

        internal fun connectionPortForLabel(label: AccessibilityNodeInfo): Int? {
            // MagicOS places the title and summary in separate containers inside the same row.
            val parent = label.parent ?: return null
            try {
                val value = ConnectionSummary(label)
                value.read(parent)
                if (value.count == 0) {
                    val row = parent.parent ?: return null
                    try { value.read(row) } finally { row.recycle() }
                }
                return if (value.count == 1) AdbPairingFields.parsePort(value.text) else null
            } finally { parent.recycle() }
        }

        private class ConnectionSummary(private val label: AccessibilityNodeInfo) {
            var count = 0
                private set
            var text: String? = null
                private set

            fun read(node: AccessibilityNodeInfo, depth: Int = 0) {
                if (depth > 4 || count > 1 || !node.isVisibleToUser ||
                    node.packageName != label.packageName || node.windowId != label.windowId) return
                when (node.viewIdResourceName) {
                    "android:id/title" -> if (node != label) {
                        // A larger container with other preferences is not a single address row.
                        count = 2
                        return
                    }
                    "android:id/summary" -> {
                        count++
                        text = node.text?.toString()
                    }
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { read(child, depth + 1) } finally { child.recycle() }
                }
            }
        }
        private val WIRELESS = listOf("无线调试", "無線偵錯", "無線除錯", "Wireless debugging")
        private val PAIR = listOf("使用配对码配对设备", "使用配對碼配對裝置", "Pair device with pairing code", "Pair using pairing code")
        private val PAIR_DIALOG = listOf("与设备配对", "與裝置配對", "Pair with device")
        private val CODE_LABELS = listOf("WLAN 配对码", "Wi-Fi 配对码", "Wi‑Fi 配对码", "Wi-Fi 配對碼", "Wi-Fi pairing code", "Wi‑Fi pairing code")
        private val CONNECTION_ADDRESS = listOf("IP 地址和端口", "IP 地址与端口", "IP 位址和通訊埠", "IP address & Port", "IP address and port")
    }
}

/** Parsed only from the visible system pairing dialog. The displayed IP is never used to connect. */
internal class AdbPairingFields private constructor(val code: String, val port: Int) {
    companion object {
        private val codePattern = Regex("[0-9]{6}")
        private val addressPattern = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}:([0-9]{1,5})")

        fun parse(code: String?, address: String?): AdbPairingFields? {
            val pin = code?.trim()?.takeIf(codePattern::matches) ?: return null
            val port = parsePort(address) ?: return null
            return AdbPairingFields(pin, port)
        }

        /** Fallback for a recognized system dialog with different view IDs. */
        fun parseLabeled(text: List<String>, codeLabels: List<String>, addressLabels: List<String>): AdbPairingFields? =
            parse(valueAfter(text, codeLabels), valueAfter(text, addressLabels))

        fun parseLabeledPort(text: List<String>, addressLabels: List<String>): Int? = parsePort(valueAfter(text, addressLabels))

        private fun valueAfter(text: List<String>, labels: List<String>): String? {
            val index = text.indices.singleOrNull { index -> labels.any { it.equals(text[index], ignoreCase = true) } }
                ?: return null
            return text.getOrNull(index + 1)
        }

        fun parsePort(address: String?): Int? = addressPattern.matchEntire(address?.trim().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
