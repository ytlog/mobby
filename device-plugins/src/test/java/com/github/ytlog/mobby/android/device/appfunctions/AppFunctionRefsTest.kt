package com.github.ytlog.mobby.android.device.appfunctions

import org.junit.Assert.*
import org.junit.Test

class AppFunctionRefsTest {
    @Test fun preservesExactPackageAndFunctionIdentity() {
        val ref = AppFunctionRefs.encode("com.example.tasks", "TaskService#create待办")
        assertEquals("com.example.tasks" to "TaskService#create待办", AppFunctionRefs.decode(ref))
        assertTrue(ref.startsWith("plugin:appfunction:"))
    }

    @Test fun rejectsMalformedAndNonCanonicalReferences() {
        val valid = AppFunctionRefs.encode("com.example.tasks", "create")
        assertNull(AppFunctionRefs.decode(valid + "="))
        assertNull(AppFunctionRefs.decode("plugin:appfunction:!!!!"))
        assertNull(AppFunctionRefs.decode("plugin:device:screen"))
        assertNull(AppFunctionRefs.decode("plugin:appfunction:" + "a".repeat(1100)))
    }

    @Test fun functionIdentityMustNotContainSeparatorOrControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) { AppFunctionRefs.encode("invalid", "create") }
        assertThrows(IllegalArgumentException::class.java) { AppFunctionRefs.encode("com.example.tasks", "one\ntwo") }
        assertThrows(IllegalArgumentException::class.java) { AppFunctionRefs.encode("com.example.tasks", "") }
    }
}
