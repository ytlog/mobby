package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.engine.AgentMode

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
    internal val workspaces get() = WorkspaceStore.forContext(context)
    val workspace get() = File(sdk.vfs.homeDir, "workspace")

    suspend fun initialize(output: (String) -> Unit) = withContext(Dispatchers.IO) {
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "当前构建仅支持 ARM64 设备" }
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
        check(sdk.isInstalled) { "APK 缺少可执行的 Bash 资源" }
        output("正在准备内置运行文件…")
        prepareFiles(nativeDir, output)
        workspace.mkdirs()
        File(sdk.vfs.prefixDir, "etc/tls/certs").mkdirs()
        val shell = sdk.executor.resolveBinary("bash").absolutePath
        val extra = mapOf("SHELL" to shell)
        val probe = sdk.executor.execute("printf 'MOBBY_RUNTIME_OK\\n'; printf '%s\\n' \"\$BASH_VERSION\"", workspace, extra)
        check(probe.isSuccess && probe.stdout.startsWith("MOBBY_RUNTIME_OK")) { "Bash 启动失败 (${probe.exitCode}): ${probe.stderr}" }
        output("Bash ${probe.stdout.lineSequence().drop(1).firstOrNull().orEmpty()}")
        dependenciesReady = true
        for (name in listOf("git", "node", "npm", "claude", "codex")) {
            output("正在验证 $name…")
            val result = sdk.executor.execute("$name --version", workspace)
            if (result.isSuccess && result.stdout.isNotBlank()) {
                output("✓ $name：${result.stdout.lineSequence().first()}")
            } else {
                dependenciesReady = false
                output("✗ $name 安装校验失败（退出码 ${result.exitCode}）：${result.stderr.ifBlank { result.stdout }}")
            }
        }
        val git = sdk.executor.execute("git init -q .", workspace)
        if (git.isSuccess) output("✓ Git 工作区已就绪") else {
            dependenciesReady = false
            output("Git 工作区初始化失败：${git.stderr}")
        }
        File(context.filesDir, "gateway.cjs").outputStream().use { target ->
            context.assets.open("gateway/bridge.cjs").use { it.copyTo(target) }
        }
        if (dependenciesReady) output("依赖已自动安装并验证。请在「网关设置」填写地址、协议、模型和密钥。")
        output("工作目录：${workspace.absolutePath}")
    }

    private fun prepareFiles(nativeDir: String, output: (String) -> Unit) {
        val prefix = sdk.vfs.prefixDir
        fun safe(path: String): File {
            val file = File(prefix, path).toPath().normalize().toFile()
            check(file.path.startsWith(prefix.absolutePath + File.separator)) { "非法 bootstrap 路径" }
            return file
        }
        val version = context.assets.open("bootstrap/version.txt").bufferedReader().use { it.readText() }
        val marker = File(sdk.vfs.root, ".mobby-bootstrap")
        // Data files remain writable and are not replaced on every launch.
        val required = listOf("lib/node_modules/npm/bin/npm-cli.js", "lib/node_modules/@anthropic-ai/claude-code/cli.js")
        if (!marker.exists() || marker.readText() != version || required.any { !File(prefix, it).isFile }) {
            output("首次启动或依赖更新：正在自动安装 Git、Node.js、npm 和 Agent…")
            ZipInputStream(context.assets.open("bootstrap/data.zip")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val target = safe(entry.name)
                        target.parentFile?.mkdirs()
                        check(target.parentFile!!.canonicalPath.startsWith(prefix.canonicalPath + File.separator) || target.parentFile!!.canonicalFile == prefix.canonicalFile) { "安装目录链接越界" }
                        val temporary = File(target.parentFile, target.name + ".mobby-tmp")
                        temporary.delete()
                        temporary.outputStream().use { zip.copyTo(it) }
                        check(temporary.renameTo(target)) { "无法安装 ${entry.name}" }
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
                check(resolved.toPath().normalize().startsWith(prefix.toPath())) { "非法 bootstrap 链接" }
                link.parentFile?.mkdirs(); link.delete()
                Os.symlink(value, link.absolutePath)
            }
        }
        marker.writeText(version)
        output("依赖文件安装完成，正在检查实际运行能力…")
    }

    fun executable(mode: AgentMode): String {
        if (mode == AgentMode.SHELL) return sdk.executor.resolveBinary("bash").absolutePath
        val name = if (mode == AgentMode.CLAUDE) "claude" else "codex"
        val locations = listOf(File(sdk.vfs.binDir, name), File(sdk.vfs.homeDir, ".local/bin/$name"))
        return locations.firstOrNull { it.exists() }?.absolutePath
            ?: error("$name 尚未安装。请先在本运行环境安装 CLI 并完成认证。")
    }

    /** Internal Shell diagnostics; Agent runs go through RuntimeClient and ProcessPort. */
    fun runShell(input: String, onStarted: (Int) -> Unit = {}, onTerminated: (Int?) -> Unit = {}): kotlinx.coroutines.flow.Flow<OutputLine> =
        sdk.executor.executeArgsStreaming(listOf(executable(AgentMode.SHELL), "-c", input), workspace,
            onStarted = onStarted, onTerminated = onTerminated)
}
