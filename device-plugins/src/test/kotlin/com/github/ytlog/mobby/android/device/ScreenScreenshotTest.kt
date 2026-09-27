package com.github.ytlog.mobby.android.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.ytlog.mobby.android.deviceinteraction.model.EffectState
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScreenScreenshotTest {
    @Test fun `screen evidence is a decodable image within resource limits`() {
        val bitmap = Bitmap.createBitmap(1200, 2400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val encoded = try { ScreenScreenshotEncoder.encode(bitmap) } finally { bitmap.recycle() }
        assertTrue(encoded.size in 1..2 * 1024 * 1024)
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        assertNotNull(decoded)
        assertEquals(900, decoded.width)
        assertEquals(1800, decoded.height)
        decoded.recycle()
    }

    @Test fun `screen result exposes screenshot ref and accurately reports missing capture`() {
        val captured = ScreenAccessService.Observation("window text", "example.app", 123L,
            ScreenScreenshot(byteArrayOf(1), "captured"))
        val snapshot = screenObservationResult("snapshot", captured, "image:abc", "captured")
        assertEquals(listOf("image:abc"), snapshot.resourceRefs)
        assertEquals("image:abc", snapshot.data["observationRef"]?.jsonPrimitive?.content)
        assertEquals(EffectState.NONE, snapshot.effectState)

        val unavailable = screenObservationResult("tap", captured, null, "system_error_4")
        assertTrue(unavailable.resourceRefs.isEmpty())
        assertEquals("system_error_4", unavailable.data["screenshotStatus"]?.jsonPrimitive?.content)
        assertEquals(EffectState.CONFIRMED, unavailable.effectState)
    }
}
