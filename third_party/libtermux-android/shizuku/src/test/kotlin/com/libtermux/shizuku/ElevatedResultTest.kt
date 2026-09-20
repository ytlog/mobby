/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.shizuku

import com.libtermux.executor.ExecutionResult
import org.junit.Assert.*
import org.junit.Test

class ElevatedResultTest {

    private fun execResult(stdout: String = "", stderr: String = "", exit: Int = 0) =
        ExecutionResult(stdout, stderr, exit, 0L, "test_cmd")

    @Test
    fun `isSuccess is true when exitCode is 0`() {
        val result = ElevatedResult("out", "", 0, elevated = true)
        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
    }

    @Test
    fun `isFailure is true when exitCode is non-zero`() {
        val result = ElevatedResult("", "err", 1, elevated = false)
        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `output combines stdout and stderr`() {
        val result = ElevatedResult("hello", "world", 0, elevated = true)
        assertTrue(result.output.contains("hello"))
        assertTrue(result.output.contains("world"))
    }

    @Test
    fun `output is stdout only when stderr is empty`() {
        val result = ElevatedResult("only_out", "", 0, elevated = true)
        assertEquals("only_out", result.output)
    }

    @Test
    fun `fromExecutionResult maps fields correctly`() {
        val exec    = execResult(stdout = "hello", stderr = "warn", exit = 0)
        val elevated = ElevatedResult.fromExecutionResult(exec, elevated = true)
        assertEquals("hello", elevated.stdout)
        assertEquals("warn",  elevated.stderr)
        assertEquals(0,       elevated.exitCode)
        assertTrue(elevated.elevated)
        assertTrue(elevated.isSuccess)
    }

    @Test
    fun `fromExecutionResult with elevated=false marks fallback`() {
        val exec     = execResult(stdout = "fallback", exit = 0)
        val elevated = ElevatedResult.fromExecutionResult(exec, elevated = false)
        assertFalse(elevated.elevated)
        assertEquals("fallback", elevated.stdout)
    }

    @Test
    fun `fromExecutionResult preserves failure exit code`() {
        val exec     = execResult(stderr = "permission denied", exit = 126)
        val elevated = ElevatedResult.fromExecutionResult(exec, elevated = true)
        assertEquals(126, elevated.exitCode)
        assertTrue(elevated.isFailure)
    }
}
