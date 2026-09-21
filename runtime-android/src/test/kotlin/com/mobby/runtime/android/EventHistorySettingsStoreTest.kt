package com.mobby.runtime.android

import com.mobby.runtime.api.EventHistorySettings
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EventHistorySettingsStoreTest {
    @Test fun `saved limits reopen and convert to the cleanup policy without changing gateway preferences`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("runtime-storage-policy", 0).edit().clear().commit()
        val gateway = context.getSharedPreferences("gateway", 0)
        gateway.edit().putString("fixture", "unchanged").commit()
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EventHistorySettingsStore(context) { key }
        assertEquals(EventHistorySettings(), store.load())
        store.save(EventHistorySettings(7, 8))
        val reopened = EventHistorySettingsStore(context) { key }
        assertEquals(EventHistorySettings(7, 8), reopened.load())
        assertEquals(EventHistoryPolicy(7 * 86_400_000L, 8 * 1024L * 1024), reopened.policy())
        assertEquals("unchanged", gateway.getString("fixture", null))
        for ((days, mib) in listOf(0 to 32, 3651 to 32, 30 to 0, 30 to 1025)) {
            try { store.save(EventHistorySettings(days, mib)); fail("out of range") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(EventHistorySettings(7, 8), reopened.load())
        val prefs = context.getSharedPreferences("runtime-storage-policy", 0)
        assertEquals(setOf("limits"), prefs.all.keys)
        val encrypted = prefs.getString("limits", null)!!
        assertNotEquals("7:8", encrypted)
        val bytes = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        prefs.edit().putString("limits", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit()
        try { reopened.load(); fail("tampering must not silently restore defaults") } catch (_: javax.crypto.AEADBadTagException) { }
        context.getSharedPreferences("runtime-storage-policy", 0).edit().clear().commit()
    }
}
