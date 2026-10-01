package com.github.ytlog.mobby.android

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.ytlog.mobby.android.conversation.ui.SystemPetWindow
import com.github.ytlog.mobby.android.conversation.ui.overlayDisplaySize
import com.github.ytlog.mobby.android.conversation.ui.overlayWindowContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayWindowDeviceTest {
    @Test fun overlayContextAndMetricsAreAvailableWithoutAttachingAWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val windowContext = overlayWindowContext(context)
        assertNotNull(windowContext.getSystemService(WindowManager::class.java))
        val (width, height) = overlayDisplaySize(context)
        assertTrue(width > 0 && height > 0)
        // Fold metadata may legitimately be absent on a non-folding phone.
        SystemPetWindow(context).foldRegion()
    }
}
