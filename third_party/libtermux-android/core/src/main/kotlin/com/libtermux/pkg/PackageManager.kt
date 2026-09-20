/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.pkg

import com.libtermux.executor.CommandExecutor
import com.libtermux.executor.ExecutionResult
import com.libtermux.utils.TermuxLogger

/** Package metadata */
data class Package(
    val name: String,
    val version: String = "",
    val description: String = "",
    val installed: Boolean = false,
    val size: String = "",
)

sealed class PkgResult {
    data class Success(val output: String)             : PkgResult()
    data class AlreadyInstalled(val name: String)      : PkgResult()
    data class NotFound(val name: String)              : PkgResult()
    data class Failed(val error: String, val exitCode: Int) : PkgResult()
}

/**
 * High-level package manager wrapping pkg/apt/dpkg commands.
 * Provides idiomatic Kotlin APIs for the full Termux package ecosystem.
 */
class PackageManager(private val executor: CommandExecutor) {

    /** Install one or more packages via pkg */
    suspend fun install(vararg packages: String): PkgResult {
        val names = packages.joinToString(" ")
        TermuxLogger.i("pkg install: $names")
        val result = executor.execute("pkg install -y $names")
        return result.toPkgResult(names)
    }

    /** Uninstall one or more packages via pkg */
    suspend fun uninstall(vararg packages: String): PkgResult {
        val names = packages.joinToString(" ")
        TermuxLogger.i("pkg uninstall: $names")
        val result = executor.execute("pkg uninstall -y $names")
        return result.toPkgResult(names)
    }

    /** Update all installed packages */
    suspend fun upgradeAll(): PkgResult {
        TermuxLogger.i("pkg upgrade all")
        val result = executor.execute("pkg upgrade -y")
        return result.toPkgResult("all packages")
    }

    /** Search packages by query */
    suspend fun search(query: String): List<Package> {
        val result = executor.execute("pkg search $query 2>&1")
        return parseSearchOutput(result.stdout)
    }

    /** List all installed packages */
    suspend fun listInstalled(): List<Package> {
        // Raw string to avoid backslash escape issues in awk command
        val result = executor.execute("""dpkg --list 2>/dev/null | awk '/^ii/{print $2,$3}'""")
        return result.stdoutLines().mapNotNull { line ->
            // Use explicit whitespace pattern instead of \s
            val parts = line.trim().split(Regex("[ \t]+"))
            if (parts.size >= 2) Package(parts[0], parts[1], installed = true) else null
        }
    }

    /** Check if a package is installed */
    suspend fun isInstalled(packageName: String): Boolean {
        val result = executor.execute("dpkg -l $packageName 2>/dev/null | grep -c '^ii'")
        return result.isSuccess && result.stdout.trim() == "1"
    }

    /** Get detailed info about a package */
    suspend fun info(packageName: String): Package? {
        val result = executor.execute("pkg show $packageName 2>/dev/null")
        if (result.isFailure) return null
        return parsePackageInfo(result.stdout, packageName)
    }

    /** Update repository index */
    suspend fun updateRepo(): PkgResult {
        TermuxLogger.i("pkg update repo")
        val result = executor.execute("pkg update -y")
        return result.toPkgResult("repository")
    }

    /** Install pip package */
    suspend fun pipInstall(vararg packages: String): PkgResult {
        val names = packages.joinToString(" ")
        TermuxLogger.i("pip install: $names")
        val result = executor.execute("pip install $names")
        return result.toPkgResult(names)
    }

    /** Install npm package globally */
    suspend fun npmInstall(vararg packages: String): PkgResult {
        val names = packages.joinToString(" ")
        TermuxLogger.i("npm install -g: $names")
        val result = executor.execute("npm install -g $names")
        return result.toPkgResult(names)
    }

    // ── Parsing helpers ──────────────────────────────────────────────────

    private fun parseSearchOutput(output: String): List<Package> {
        return output.lines()
            .filter { it.contains("/") }
            .mapNotNull { line ->
                // Use explicit whitespace pattern instead of \s
                val parts = line.trim().split(Regex("[ \t]+"))
                if (parts.isNotEmpty()) {
                    val nameParts = parts[0].split("/")
                    Package(
                        name    = nameParts.first(),
                        version = if (parts.size > 1) parts[1] else "",
                    )
                } else null
            }
    }

    private fun parsePackageInfo(output: String, fallbackName: String): Package {
        val fields = mutableMapOf<String, String>()
        output.lines().forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
        }
        return Package(
            name        = fields["Package"] ?: fallbackName,
            version     = fields["Version"] ?: "",
            description = fields["Description"] ?: "",
            installed   = true,
            size        = fields["Installed-Size"] ?: "",
        )
    }

    private fun ExecutionResult.toPkgResult(target: String): PkgResult = when {
        isSuccess -> PkgResult.Success(stdout)
        stderr.contains("Unable to locate package") -> PkgResult.NotFound(target)
        stderr.contains("already installed")        -> PkgResult.AlreadyInstalled(target)
        else -> PkgResult.Failed(stderr.ifBlank { stdout }, exitCode)
    }
}
