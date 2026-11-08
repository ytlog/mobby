package com.github.ytlog.mobby.android

import android.app.Activity
import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.ytlog.mobby.android.conversation.ui.ScreenControlSettingsActivity
import com.github.ytlog.mobby.android.conversation.ui.ScreenSetupStep
import com.github.ytlog.mobby.android.conversation.ui.ScreenSetupViewModel
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenRotationTest {
    @Test fun conversationHostStaysAliveWhileItsWindowRotates() {
        ActivityScenario.launch(MainActivity::class.java).use { verifyRotation(it) }
    }

    @Test fun screenSetupStaysAliveWhileItsWindowRotates() {
        ActivityScenario.launch(ScreenControlSettingsActivity::class.java).use { verifyRotation(it) }
    }

    @Test fun recreatingScreenSetupKeepsThePortDraftOnScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ActivityScenario.launch(ScreenControlSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[ScreenSetupViewModel::class.java].draft(ScreenMode.ADB, false).history =
                    arrayListOf(ScreenSetupStep.MODE.name, ScreenSetupStep.CONNECT.name, ScreenSetupStep.PORT.name)
            }
            val port = "32123"
            portField(automation).let { field ->
                try {
                    assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, port)
                    }))
                } finally { field.recycle() }
            }
            scenario.recreate()
            portField(automation).let { field ->
                try { assertEquals(port, field.text.toString()) } finally { field.recycle() }
            }
        }
    }

    private fun portField(automation: UiAutomation): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.className.toString() == "android.widget.EditText") return AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { find(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            automation.rootInActiveWindow?.let { root ->
                try { find(root)?.let { return it } } finally { root.recycle() }
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("ADB connection port field did not appear")
    }

    private fun <A : Activity> verifyRotation(scenario: ActivityScenario<A>) {
        var before: A? = null
        var previousRequest = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario.onActivity { previousRequest = it.requestedOrientation }
        try {
            rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            scenario.onActivity { before = it }
            rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            scenario.onActivity { assertSame(before, it) }
            rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            scenario.onActivity { assertSame(before, it) }
        } finally {
            scenario.onActivity { it.requestedOrientation = previousRequest }
        }
    }

    private fun <A : Activity> rotate(scenario: ActivityScenario<A>, requested: Int, expected: Int) {
        scenario.onActivity { it.requestedOrientation = requested }
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var laidOut = false
            scenario.onActivity {
                val decor = it.window.decorView
                val landscape = decor.width > decor.height
                laidOut = it.resources.configuration.orientation == expected &&
                    decor.width > 0 && decor.height > 0 && landscape == (expected == Configuration.ORIENTATION_LANDSCAPE)
            }
            if (laidOut) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("The activity did not lay out in the requested orientation")
    }
}
