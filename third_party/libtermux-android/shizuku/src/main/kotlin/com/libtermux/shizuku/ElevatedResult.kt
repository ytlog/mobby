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

/**
 * Result of an elevated command executed via [ShizukuTermux.runElevated].
 *
 * @property stdout   Standard output of the command.
 * @property stderr   Standard error output.
 * @property exitCode Process exit code (0 = success).
 * @property elevated `true` if the command ran with Shizuku elevated privileges,
 *                    `false` if Shizuku was unavailable and a normal fallback was used.
 */
data class ElevatedResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val elevated: Boolean,
) {
    /** `true` when [exitCode] is 0 */
    val isSuccess: Boolean get() = exitCode == 0

    /** `true` when [exitCode] is non-zero */
    val isFailure: Boolean get() = !isSuccess

    /** Combined stdout + stderr (useful for logging) */
    val output: String
        get() = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (stdout.isNotEmpty()) appendLine()
                append(stderr)
            }
        }

    companion object {
        /**
         * Convert an [ExecutionResult] from the core module to [ElevatedResult].
         *
         * @param result   The core execution result to wrap.
         * @param elevated Whether the command ran with elevated privileges.
         */
        fun fromExecutionResult(result: ExecutionResult, elevated: Boolean) = ElevatedResult(
            stdout   = result.stdout,
            stderr   = result.stderr,
            exitCode = result.exitCode,
            elevated = elevated,
        )
    }
}
