package com.github.ytlog.mobby.android.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
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

    fun start(destination: ScreenAccessService.AdbSetupDestination) {
        stop()
        if (Build.VERSION.SDK_INT < 33 || ScreenCoordinator.chatActive) return
        task = CoroutineScope(Dispatchers.Main).launch {
            val flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            val info = service.serviceInfo ?: return@launch
            val addedFlags = flags and info.flags.inv()
            if (addedFlags != 0) {
                info.flags = info.flags or addedFlags
                service.serviceInfo = info
            }
            try {
                withTimeoutOrNull(10_000) { navigate(destination) }
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
        var scrolls = 0
        while (true) {
            delay(if (enteredSettings) 350 else 200)
            if (!service.getSystemService(PowerManager::class.java).isInteractive ||
                service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
            val root = service.getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) ?: continue
            try {
                val packageName = root.packageName?.toString()
                if (packageName != SETTINGS_PACKAGE) {
                    if (enteredSettings || packageName != service.packageName) return
                    continue
                }
                val window = root.window ?: continue
                val foreground = try {
                    window.displayId == Display.DEFAULT_DISPLAY && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive
                } finally { window.recycle() }
                if (!foreground) return
                enteredSettings = true
                val result = ScreenCoordinator.programAction {
                    visit(root, destination, openedPage, scrolls < 8)
                }
                when (result) {
                    Result.STOP -> return
                    Result.OPENED_PAGE -> { openedPage = true; delay(450) }
                    Result.SCROLLED -> scrolls++
                }
            } catch (_: ScreenRefused) {
                return
            } finally { root.recycle() }
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
        if (hasHeading(root, DEVELOPER)) {
            if (openedPage) return Result.STOP
            val wireless = exactNodes(root, WIRELESS)
            try {
                val entry = wireless.singleOrNull()
                if (entry != null && fullLabel(entry))
                    return if (clickPreference(entry)) Result.OPENED_PAGE else Result.STOP
                if (wireless.size <= 1) return scroll(root, canScroll)
            } finally { wireless.forEach(AccessibilityNodeInfo::recycle) }
            return Result.STOP
        }
        if (destination == ScreenAccessService.AdbSetupDestination.PAIR && hasHeading(root, WIRELESS) &&
            Settings.Global.getInt(service.contentResolver, "adb_wifi_enabled", -1) != 0) {
            clickUnique(root, PAIR)
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
        val nodes = exactNodes(root, labels)
        return try { nodes.any {
            val id = it.viewIdResourceName?.substringAfterLast('/')
            id?.startsWith("action_bar_title") == true || id == "toolbar_title" || id == "collapsing_toolbar_title"
        } } finally { nodes.forEach(AccessibilityNodeInfo::recycle) }
    }

    private fun exactNodes(root: AccessibilityNodeInfo, labels: List<String>): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        for (label in labels) {
            for (node in root.findAccessibilityNodeInfosByText(label)) {
                if (node.isVisibleToUser && node.text?.toString()?.trim()?.equals(label, ignoreCase = true) == true &&
                    node.packageName?.toString() == SETTINGS_PACKAGE && node !in matches) matches += node
                else node.recycle()
            }
        }
        return matches
    }

    private enum class Result { STOP, OPENED_PAGE, SCROLLED }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        val ABOUT = listOf("关于手机", "关于本机", "我的设备", "關於手機", "About phone", "About device", "My device")
        val SOFTWARE_DETAILS = listOf("全部参数", "详细信息与规格", "软件信息", "版本信息", "版本",
            "All specs", "Detailed info and specs", "Software information", "Version information", "Version")
        val VERSION_CONTROL = listOf("MIUI 版本", "MIUI version", "OS 版本", "OS version", "版本号", "软件版本号", "编译编号",
            "Build number", "Software version", "Version number")
        val DEVELOPER = listOf("开发者选项", "開發人員選項", "開發者選項", "Developer options")
        val WIRELESS = listOf("无线调试", "無線偵錯", "無線除錯", "Wireless debugging")
        val PAIR = listOf("使用配对码配对设备", "使用配對碼配對裝置", "Pair device with pairing code")
    }
}
