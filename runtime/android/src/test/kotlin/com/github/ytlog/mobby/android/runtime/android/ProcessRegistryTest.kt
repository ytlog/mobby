package com.github.ytlog.mobby.android.runtime.android

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProcessRegistryTest {
    @Test fun recordsChildIdentityWhenProcBecomesReadableAfterLaunch() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("runtime-process", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var attempts = 0
        var pauses = 0
        val registry = ProcessRegistry(context,
            readStartTime = { if (++attempts < 3) null else "123456" },
            pause = { pauses++ })
        registry.started(42)
        assertEquals(42, prefs.getInt("pid", -1))
        assertEquals("123456", prefs.getString("identity", null))
        assertEquals(2, pauses)
        registry.terminated(0)
        assertFalse(prefs.contains("pid"))
    }

    @Test fun refusesUnidentifiableChildWithinBoundedAttempts() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("runtime-process", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var attempts = 0
        val registry = ProcessRegistry(context, readStartTime = { attempts++; null }, pause = {})
        assertThrows(IllegalStateException::class.java) { registry.started(42) }
        assertEquals(101, attempts)
        assertFalse(prefs.contains("pid"))
    }
}
