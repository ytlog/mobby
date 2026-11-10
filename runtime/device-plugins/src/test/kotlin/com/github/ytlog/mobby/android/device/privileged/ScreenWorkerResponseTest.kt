package com.github.ytlog.mobby.android.device.privileged

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScreenWorkerResponseTest {
    @Test fun `an oversized observation drops the screenshot before the page text`() {
        val text = "a".repeat(1_000_000)
        val result = JSONObject().put("ok", true).put("text", text).put("jpeg", "b".repeat(ScreenServer.MAX_RESPONSE))
            .put("frameAt", 7).put("screenshotStatus", "captured")
        val fitted = ScreenServer.fit(result, "snapshot")
        assertTrue(fitted.getBoolean("ok"))
        assertEquals(text, fitted.getString("text"))
        assertFalse(fitted.has("jpeg") || fitted.has("frameAt"))
        assertEquals("too_large", fitted.getString("screenshotStatus"))
    }

    @Test fun `page text over the limit is refused instead of ending the session`() {
        val fitted = ScreenServer.fit(JSONObject().put("ok", true).put("text", "a".repeat(ScreenServer.MAX_RESPONSE)), "snapshot")
        assertFalse(fitted.getBoolean("ok"))
        assertEquals("response_too_large", fitted.getString("detail"))
        assertEquals("virtual_screen_snapshot_failed", fitted.getString("error"))
    }

    @Test fun `a responsive frame fits unchanged`() {
        val result = JSONObject().put("ok", true).put("text", "page").put("jpeg", "abc")
        assertSame(result, ScreenServer.fit(result, "tap"))
        assertEquals("abc", result.getString("jpeg"))
    }

    @Test fun `only an entirely black frame is reported as black`() {
        assertTrue(VirtualScreen.black(IntArray(64) { 0xFF000000.toInt() }))
        // Alpha alone does not light a pixel; one dim pixel of content does.
        assertTrue(VirtualScreen.black(IntArray(64) { 0x00000000 }))
        assertFalse(VirtualScreen.black(IntArray(64) { if (it == 40) 0xFF010101.toInt() else 0xFF000000.toInt() }))
    }
}
