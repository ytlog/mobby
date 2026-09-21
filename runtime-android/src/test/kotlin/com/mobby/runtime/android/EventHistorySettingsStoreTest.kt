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
        store.save(EventHistorySettings(7, 8, 14, 64))
        val reopened = EventHistorySettingsStore(context) { key }
        assertEquals(EventHistorySettings(7, 8, 14, 64), reopened.load())
        assertEquals(EventHistoryPolicy(7 * 86_400_000L, 8 * 1024L * 1024), reopened.policy())
        assertEquals(OutputRetentionPolicy(14 * 86_400_000L, 64 * 1024L * 1024), reopened.outputPolicy())
        assertEquals("unchanged", gateway.getString("fixture", null))
        for ((days, mib) in listOf(0 to 32, 3651 to 32, 30 to 0, 30 to 1025)) {
            try { store.save(EventHistorySettings(days, mib)); fail("out of range") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(EventHistorySettings(7, 8, 14, 64), reopened.load())
        for ((days, mib) in listOf(0 to 256, 3651 to 256, 30 to 0, 30 to 4097)) {
            try { store.save(EventHistorySettings(outputRetentionDays = days, outputBudgetMiB = mib)); fail("output range") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(EventHistorySettings(7, 8, 14, 64), reopened.load())
        val prefs = context.getSharedPreferences("runtime-storage-policy", 0)
        assertEquals(setOf("limits"), prefs.all.keys)
        val encrypted = prefs.getString("limits", null)!!
        assertNotEquals("7:8:14:64", encrypted)
        val bytes = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        prefs.edit().putString("limits", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit()
        try { reopened.load(); fail("tampering must not silently restore defaults") } catch (_: javax.crypto.AEADBadTagException) { }
        context.getSharedPreferences("runtime-storage-policy", 0).edit().clear().commit()
    }
    @Test fun `old encrypted event settings keep their values and receive default output limits`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("runtime-storage-policy", 0)
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
        val old = android.util.Base64.encodeToString(cipher.iv + cipher.doFinal("9:16".toByteArray()), android.util.Base64.NO_WRAP)
        prefs.edit().clear().putString("limits", old).commit()
        val store = EventHistorySettingsStore(context) { key }
        assertEquals(EventHistorySettings(9, 16, 30, 256), store.load())
        assertEquals(old, prefs.getString("limits", null))
        store.save(store.load().copy(outputRetentionDays = 60, outputBudgetMiB = 512))
        assertEquals(EventHistorySettings(9, 16, 60, 512), EventHistorySettingsStore(context) { key }.load())
        prefs.edit().clear().commit()
    }

}
