/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * * http://www.apache.org/licenses/LICENSE-2.0
 * * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux

import com.libtermux.executor.ExecutionResult
import org.junit.Assert.*
import org.junit.Test

class ExecutionResultTest {

    private fun result(
        stdout: String = "",
        stderr: String = "",
        exit: Int = 0,
    ) = ExecutionResult(stdout, stderr, exit, 0L, "test")

    @Test
    fun `isSuccess is true when exit code is 0`() {
        assertTrue(result(exit = 0).isSuccess)
    }

    @Test
    fun `isFailure is true when exit code is non-zero`() {
        assertTrue(result(exit = 1).isFailure)
        assertTrue(result(exit = 127).isFailure)
    }

    @Test
    fun `output combines stdout and stderr`() {
        val r = result(stdout = "hello", stderr = "error")
        assertTrue(r.output.contains("hello"))
        assertTrue(r.output.contains("error"))
    }

    @Test
    fun `stdoutLines splits correctly`() {
        val r = result(stdout = "line1\nline2\nline3")
        assertEquals(listOf("line1", "line2", "line3"), r.stdoutLines())
    }

    @Test
    fun `stdoutLines filters blank lines`() {
        val r = result(stdout = "line1\n\nline2\n")
        assertEquals(listOf("line1", "line2"), r.stdoutLines())
    }

    @Test
    fun `output is stdout only when stderr is empty`() {
        val r = result(stdout = "only stdout", stderr = "")
        assertEquals("only stdout", r.output)
    }

    @Test
    fun `parseJson works for valid JSON`() {
        val r = result(stdout = """{"name":"test","value":42}""")
        val json = r.parseJson<Map<String, Any>>()
        // Just verifies no exception is thrown
        assertNotNull(json)
    }
}
