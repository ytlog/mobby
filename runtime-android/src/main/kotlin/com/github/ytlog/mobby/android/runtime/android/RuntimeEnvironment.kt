package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.runtime.engine.AgentMode
import com.github.ytlog.mobby.android.runtime.engine.program

import android.content.Context
import android.os.Build
import android.system.Os
import com.libtermux.*
import com.libtermux.bootstrap.NativeLibBootstrapProvider
import com.libtermux.executor.OutputLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.zip.ZipInputStream

class RuntimeEnvironment(private val context: Context) {
    lateinit var sdk: LibTermux
        private set
    var dependenciesReady: Boolean = false
        private set
    var opencodeReady: Boolean = false
        private set
    internal val workspaces get() = WorkspaceStore.forContext(context)
    val workspace get() = File(sdk.vfs.homeDir, "workspace")

    suspend fun initialize(output: (String) -> Unit) = withContext(Dispatchers.IO) {
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { AppStrings.thisBuildOnlySupportsArmDevices }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val prefix = File(context.filesDir, "libtermux/usr").absolutePath
        val home = File(context.filesDir, "libtermux/home").absolutePath
        sdk = LibTermux.init(context, TermuxConfig(
            bootstrapProvider = NativeLibBootstrapProvider.from(context),
            maxCommandTimeoutMs = 600_000,
            logLevel = LogLevel.NONE,
            enableLogging = false,
            environmentVariables = mapOf(
                "NO_COLOR" to "1", "TERM" to "dumb",
                "GIT_EXEC_PATH" to "$prefix/libexec/git-core",
                "GIT_TEMPLATE_DIR" to "$prefix/share/git-core/templates",
                "GIT_CONFIG_NOSYSTEM" to "1",
                "GIT_SSL_CAINFO" to "$prefix/etc/tls/cert.pem",
                "SSL_CERT_FILE" to "$prefix/etc/tls/cert.pem",
                "SSL_CERT_DIR" to "$prefix/etc/tls/certs",
                "NODE_EXTRA_CA_CERTS" to "$prefix/etc/tls/cert.pem",
                "npm_config_prefix" to prefix,
                "npm_config_cache" to "$home/.npm",
                "USE_BUILTIN_RIPGREP" to "0",
                "DISABLE_AUTOUPDATER" to "1"
            )
        ))
        check(sdk.isInstalled) { AppStrings.apkIsMissingAnExecutableBashResource }
        output(AppStrings.preparingBundledRuntimeFiles)
        prepareFiles(nativeDir, output)
        workspace.mkdirs()
        File(sdk.vfs.prefixDir, "etc/tls/certs").mkdirs()
        val shell = sdk.executor.resolveBinary("bash").absolutePath
        val extra = mapOf("SHELL" to shell)
        val probe = sdk.executor.execute("printf 'MOBBY_RUNTIME_OK\\n'; printf '%s\\n' \"\$BASH_VERSION\"", workspace, extra)
        check(probe.isSuccess && probe.stdout.startsWith("MOBBY_RUNTIME_OK")) { AppStrings.bashFailedToStart(probe.exitCode, probe.stderr) }
        output("Bash ${probe.stdout.lineSequence().drop(1).firstOrNull().orEmpty()}")
        dependenciesReady = true
        opencodeReady = false
        for (name in listOf("git", "node", "npm", "claude", "codex", "opencode")) {
            output(AppStrings.verifying(name))
            val result = sdk.executor.execute("$name --version", workspace)
            if (result.isSuccess && result.stdout.isNotBlank()) {
                if (name == "opencode") opencodeReady = true
                output("✓ $name：${result.stdout.lineSequence().first()}")
            } else if (name == "opencode") {
                output(AppStrings.opencodeFailedToStartExitCodeClaudeCodeAnd(result.exitCode, result.stderr.ifBlank { result.stdout }))
            } else {
                dependenciesReady = false
                output(AppStrings.installationVerificationFailedExitCode(name, result.exitCode, result.stderr.ifBlank { result.stdout }))
            }
        }
        val git = sdk.executor.execute("git init -q .", workspace)
        if (git.isSuccess) output(AppStrings.gitWorkspaceReady) else {
            dependenciesReady = false
            output(AppStrings.gitWorkspaceInitializationFailed(git.stderr))
        }
        File(context.filesDir, "gateway.cjs").outputStream().use { target ->
            context.assets.open("gateway/bridge.cjs").use { it.copyTo(target) }
        }
        File(context.filesDir, "gateway-strings.cjs").outputStream().use { target ->
            context.assets.open("gateway/gateway-strings.cjs").use { it.copyTo(target) }
        }
        if (dependenciesReady) output(AppStrings.dependenciesInstalledAndVerifiedConfigureAddressProtocolModelAnd)
        output(AppStrings.workingDirectory(workspace.absolutePath))
    }

    private fun prepareFiles(nativeDir: String, output: (String) -> Unit) {
        val prefix = sdk.vfs.prefixDir
        fun safe(path: String): File {
            val file = File(prefix, path).toPath().normalize().toFile()
            check(file.path.startsWith(prefix.absolutePath + File.separator)) { AppStrings.invalidBootstrapPath }
            return file
        }
        val version = context.assets.open("bootstrap/version.txt").bufferedReader().use { it.readText() }
        val marker = File(sdk.vfs.root, ".mobby-bootstrap")
        // Data files remain writable and are not replaced on every launch.
        val required = listOf("lib/node_modules/npm/bin/npm-cli.js", "lib/node_modules/@anthropic-ai/claude-code/cli.js")
        if (!marker.exists() || marker.readText() != version || required.any { !File(prefix, it).isFile }) {
            output(AppStrings.firstLaunchOrDependencyUpdateInstallingGitNodeJs)
            ZipInputStream(context.assets.open("bootstrap/data.zip")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val target = safe(entry.name)
                        target.parentFile?.mkdirs()
                        check(target.parentFile!!.canonicalPath.startsWith(prefix.canonicalPath + File.separator) || target.parentFile!!.canonicalFile == prefix.canonicalFile) { AppStrings.installationDirectoryLinkEscapesItsRoot }
                        val temporary = File(target.parentFile, target.name + ".mobby-tmp")
                        temporary.delete()
                        temporary.outputStream().use { zip.copyTo(it) }
                        check(temporary.renameTo(target)) { AppStrings.cannotInstall(entry.name) }
                    }
                }
            }
        }
        val mapping = context.assets.open("bootstrap/binaries.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        // Recreate links after an APK update, whose nativeLibraryDir can change.
        for ((relative, value) in mapping) {
            val target = File(prefix, relative)
            check(!relative.startsWith('/') && relative.split('/').none { it == ".." })
            target.parentFile?.mkdirs()
            target.delete()
            Os.symlink(File(nativeDir, value.jsonPrimitive.content).absolutePath, target.absolutePath)
        }
        File(prefix, "SYMLINKS.txt").takeIf { it.exists() }?.forEachLine { line ->
            val parts = line.split('←', limit = 2)
            if (parts.size == 2) {
                val linkPath = parts[1].removePrefix("./")
                check(!linkPath.startsWith('/') && linkPath.split('/').none { it == ".." })
                val link = File(prefix, linkPath)
                val value = parts[0].replace("/data/data/com.termux/files/usr", prefix.absolutePath)
                val resolved = if (value.startsWith('/')) File(value) else File(link.parentFile, value)
                check(resolved.toPath().normalize().startsWith(prefix.toPath())) { AppStrings.invalidBootstrapLink }
                link.parentFile?.mkdirs(); link.delete()
                Os.symlink(value, link.absolutePath)
            }
        }
        marker.writeText(version)
        output(AppStrings.dependencyFilesInstalledCheckingRuntimeCapabilities)
    }

    fun executable(mode: AgentMode): String {
        if (mode == AgentMode.SHELL) return sdk.executor.resolveBinary("bash").absolutePath
        val name = mode.program()
        val locations = listOf(File(sdk.vfs.binDir, name), File(sdk.vfs.homeDir, ".local/bin/$name"))
        return locations.firstOrNull { it.exists() }?.absolutePath
            ?: error(AppStrings.isNotInstalledInstallAndAuthenticateTheCliIn(name))
    }

    /** Internal Shell diagnostics; Agent runs go through RuntimeClient and ProcessPort. */
    fun runShell(input: String, onStarted: (Int) -> Unit = {}, onTerminated: (Int?) -> Unit = {}): kotlinx.coroutines.flow.Flow<OutputLine> =
        sdk.executor.executeArgsStreaming(listOf(executable(AgentMode.SHELL), "-c", input), workspace,
            onStarted = onStarted, onTerminated = onTerminated)
}
