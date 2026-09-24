package com.github.ytlog.mobby.android.localization

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AppStringsTest {
    @After fun reset() { AppLanguage.current = AppLanguage.CHINESE }

    @Test fun `all catalogue messages are complete in both languages and preserve arguments`() {
        val copy = StringCatalog::class.java.declaredMethods.filter { it.returnType == String::class.java && java.lang.reflect.Modifier.isPublic(it.modifiers) }
        assertTrue(copy.size > 700)
        for (language in AppLanguage.values()) {
            AppLanguage.current = language
            for (method in copy) {
                val arguments = Array<Any>(method.parameterCount) {
                    if (method.parameterTypes[it] == Int::class.javaPrimitiveType) 7 else "argument-$it-{0}-\\-$"
                }
                val result = method.invoke(AppStrings, *arguments) as String
                assertTrue(method.name, result.isNotBlank())
                if (language == AppLanguage.ENGLISH) assertFalse(method.name, Regex("[\\p{IsHan}]").containsMatchIn(result))
                arguments.forEach { assertTrue(method.name, result.contains(it.toString())) }
            }
        }
    }

    @Test fun `unsupported or missing saved language falls back to Chinese`() {
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
    }
}
