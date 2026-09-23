package com.github.ytlog.mobby.android

import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherResumeTest {
    @Test fun launcherRestoresSettingsAfterHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // A notification or explicit launch can create the task before its first launcher intent.
        shell("am start -f 0x10008000 -n ${context.packageName}/.MainActivity")
        await("打开会话抽屉")
        click("打开会话抽屉")
        await("设置")
        click("设置")
        await("网关设置")
        shell("input keyevent KEYCODE_HOME")
        shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n ${context.packageName}/.MainActivity")
        await("网关设置")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun await(label: String) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (node(label) != null) return
            SystemClock.sleep(100)
        }
        assertTrue("Expected visible page element: $label", node(label) != null)
    }

    private fun click(label: String) {
        var target = requireNotNull(node(label))
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("Click $label", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::visit)
    }
}
