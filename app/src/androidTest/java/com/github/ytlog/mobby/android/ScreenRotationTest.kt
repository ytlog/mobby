package com.github.ytlog.mobby.android

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.ytlog.mobby.android.conversation.ui.ScreenControlSettingsActivity
import org.junit.Assert.assertSame
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
