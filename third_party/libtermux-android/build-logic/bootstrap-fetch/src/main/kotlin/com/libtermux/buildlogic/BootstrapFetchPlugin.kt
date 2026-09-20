/**
 * LibTermux-Android — build-logic
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.libtermux.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Registers a `bootstrapFetch { }` extension and `fetchBootstrapBinaries` /
 * `fetchProotBinary` tasks on the applying project (a `bootstrap-<abi>`
 * module).
 *
 * ## What this does and why
 * Termux bootstrap binaries downloaded into `filesDir` at *runtime* can
 * never be executed on Android 10+ (API 29+) — see
 * `core/bootstrap/BootstrapProvider.kt` for the full explanation. The fix
 * is to make binaries part of the APK's native library set, which Android's
 * own installer extracts with execute permission intact. This task performs
 * the download+prepare step at *build* time instead of runtime, writing
 * results straight into `src/main/jniLibs/<abi>/`.
 *
 * ## Verified against a real bootstrap archive
 * The archive layout and SYMLINKS.txt format used here were checked
 * directly against a real `bootstrap-aarch64.zip`, not assumed from
 * documentation:
 *   - Archive root contains `bin/`, `etc/`, `lib/`, etc. directly — there
 *     is NO `usr/` prefix inside the zip (that segment only appears in the
 *     runtime install path Termux itself extracts to).
 *   - `bash`, `apt`, `dpkg`, `tar` are genuine files directly under `bin/`.
 *   - `busybox` and `proot` are NOT present anywhere in this archive —
 *     don't add them to [BootstrapFetchExtension.binaries]. `proot` is
 *     fetched by the separate [FetchProotBinaryTask] from a different
 *     upstream source entirely.
 *   - SYMLINKS.txt format is `target←symlinkPath` (literal U+2190
 *     separator). For the four binaries above, none require symlink
 *     resolution in practice (they're real files) — the resolution logic
 *     exists mainly for future-proofing against bootstrap layout changes.
 *
 * ## Known tradeoff: symlinks are resolved, not preserved
 * `jniLibs/` is a flat bag of `.so` files — Android's packager does not
 * preserve symlink structure the way a normal zip extraction would. If a
 * future bootstrap release makes one of [BootstrapFetchExtension.binaries]
 * a symlink instead of a real file, this task resolves it to a real copy
 * of its target rather than failing — meaning:
 *   - Only binaries actually needed for exec should be listed — non-
 *     executable bootstrap content (share/, etc/ config files, docs) is
 *     NOT something this task handles, since only *execution* is
 *     restricted, not file storage. Ship that content the old way
 *     (bundled as an asset, or still downloaded to filesDir at runtime)
 *     if you need it.
 */
class BootstrapFetchPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create<BootstrapFetchExtension>("bootstrapFetch")

        project.tasks.register<FetchBootstrapBinariesTask>("fetchBootstrapBinaries") {
            group = "libtermux"
            description = "Downloads the Termux bootstrap zip for the configured ABI and " +
                "prepares jniLibs/<abi>/lib*.so from it."

            abi.set(ext.abi)
            termuxArch.set(ext.termuxArch)
            bootstrapTag.set(ext.bootstrapTag)
            binaries.set(ext.binaries)
            outputDir.set(project.layout.projectDirectory.dir("src/main/jniLibs"))
            gradleUserHomeDir.set(project.gradle.gradleUserHomeDir)
        }

        project.tasks.register<FetchProotBinaryTask>("fetchProotBinary") {
            group = "libtermux"
            description = "Downloads proot from Termux's official .deb package repository " +
                "for the configured ABI and extracts it into jniLibs/<abi>/libproot.so. " +
                "proot is NOT part of the Termux bootstrap archive — verified directly " +
                "against a real bootstrap zip, which contains no proot binary at all — so " +
                "this fetches proot's own official .deb, the same source `pkg install proot` " +
                "itself uses on-device."

            abi.set(ext.abi)
            prootArch.set(ext.prootArch)
            outputDir.set(project.layout.projectDirectory.dir("src/main/jniLibs"))
            gradleUserHomeDir.set(project.gradle.gradleUserHomeDir)
            onlyIf { ext.includeProot.getOrElse(false) }
        }

        // fetchBootstrapBinaries and fetchProotBinary both write into the
        // same src/main/jniLibs/<abi>/ directory; run them together.
        project.tasks.named("fetchBootstrapBinaries") {
            finalizedBy("fetchProotBinary")
        }

        // AGP's *JniLibFolders merge tasks (mergeDebugJniLibFolders,
        // mergeReleaseJniLibFolders, and one per build-type/flavor variant)
        // read src/main/jniLibs as a source-set input, but Gradle only
        // infers task dependencies from declared task *outputs* — a
        // source-set directory isn't one, so there's no automatic edge to
        // whatever writes into it. Under Gradle 9's stricter validation
        // that surfaces as "Property has implicit dependency" on every
        // variant's merge task.
        //
        // `finalizedBy` above only orders our two fetch tasks relative to
        // each other; it does nothing for AGP's tasks, which may still run
        // concurrently with — or before — fetchBootstrapBinaries.
        //
        // AGP creates its per-variant merge tasks lazily and doesn't expose
        // their names up front, so `tasks.named(...)` can't target them
        // directly at plugin-apply time. `tasks.whenTaskAdded` is the
        // standard way around that (the same pattern used by e.g.
        // mozilla/rust-android-gradle for this exact class of problem):
        // it fires for every task as AGP registers it, letting us match by
        // name and attach a real dependsOn edge to each variant as it
        // appears.
        project.tasks.whenTaskAdded {
            if (name.endsWith("JniLibFolders")) {
                dependsOn("fetchBootstrapBinaries")
                // The finalizedBy declared above only orders fetchProotBinary
                // *after* fetchBootstrapBinaries — it does not create a
                // dependency edge, so Gradle's task-output validation still
                // flags fetchProotBinary's write into the same jniLibs
                // directory as undeclared. Depend on it explicitly too.
                // as undeclared. Depend on it explicitly too.
                dependsOn("fetchProotBinary")
            }
        }
    }
}

abstract class BootstrapFetchExtension {
    /** Android ABI folder name, e.g. "arm64-v8a". Used as the jniLibs subfolder. */
    abstract val abi: Property<String>

    /** Termux's own arch name for the bootstrap zip filename, e.g. "aarch64". */
    abstract val termuxArch: Property<String>

    /**
     * Bootstrap release tag, e.g. "bootstrap-2026.05.24-r1+apt.android-7".
     * Keep this in sync with core's BootstrapReleaseResolver fallback tag —
     * they should generally point at the same release.
     */
    abstract val bootstrapTag: Property<String>

    /**
     * Binary names (as they appear directly under `bin/` in the bootstrap
     * archive, after symlink resolution if needed) to extract into jniLibs.
     *
     * VERIFIED against a real bootstrap-aarch64.zip: `bash`, `apt`, `dpkg`,
     * `tar` are genuine files under `bin/`. `busybox` is NOT present in
     * this bootstrap (Termux dropped busybox as a bundled bootstrap
     * component; coreutils/dash/etc. are separate real binaries instead —
     * don't list "busybox" here, it will be silently skipped with a
     * warning). `proot` is ALSO not present — see [FetchProotBinaryTask]
     * for how :os obtains proot separately, since it comes from a
     * different upstream project entirely, not the Termux bootstrap.
     */
    abstract val binaries: org.gradle.api.provider.SetProperty<String>

    /**
     * Whether to also fetch a standalone proot binary via
     * [FetchProotBinaryTask]. No implicit default here (Gradle's
     * [Property] has none) — each `bootstrap-<abi>` module's build.gradle.kts
     * must set this explicitly. The four published modules all set it
     * `true`, since :os's ProotRunner needs proot and the binary is small;
     * a `core`-only consumer that wants to skip it can set `false`.
     */
    abstract val includeProot: Property<Boolean>

    /**
     * Termux architecture name used in the official `proot_<version>_<arch>.deb`
     * filename at packages.termux.dev — same value space as [termuxArch]
     * ("aarch64", "arm", "i686", "x86_64"). Kept as a separate property
     * (rather than reusing [termuxArch] directly in [FetchProotBinaryTask])
     * so the two fetch tasks stay decoupled even though their arch naming
     * happens to coincide today.
     */
    abstract val prootArch: Property<String>
}

@org.gradle.api.tasks.CacheableTask
abstract class FetchBootstrapBinariesTask : DefaultTask() {

    @get:Input abstract val abi: Property<String>
    @get:Input abstract val termuxArch: Property<String>
    @get:Input abstract val bootstrapTag: Property<String>
    @get:Input abstract val binaries: org.gradle.api.provider.SetProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /**
     * Gradle user home directory, captured at configuration time via
     * `project.gradle.gradleUserHomeDir` in [BootstrapFetchPlugin.apply].
     * Not annotated `@Internal`/`@Input` on purpose beyond marking it
     * `@Internal` below — its value doesn't affect task outputs (it's just
     * a shared download cache location), so it shouldn't participate in
     * up-to-date checks. Reading `project` directly inside [fetch] (an
     * execution-time `@TaskAction`) is what breaks the configuration
     * cache — see https://docs.gradle.org/9.6.1/userguide/configuration_cache_requirements.html#config_cache:requirements:use_project_during_execution
     * — so the value is threaded in here instead.
     */
    @get:org.gradle.api.tasks.Internal
    abstract val gradleUserHomeDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val abiName = abi.get()
        val arch = termuxArch.get()
        val tag = bootstrapTag.get()
        val wanted = binaries.get()
        val destDir = outputDir.get().dir(abiName).asFile.also { it.mkdirs() }

        val cacheDir = File(gradleUserHomeDir.get().asFile, "libtermux-bootstrap-cache")
        cacheDir.mkdirs()
        val cachedZip = File(cacheDir, "bootstrap-$arch-$tag.zip")

        if (!cachedZip.exists()) {
            val encodedTag = tag.replace("+", "%2B")
            val url = "https://github.com/termux/termux-packages/releases/download/" +
                "$encodedTag/bootstrap-$arch.zip"
            logger.lifecycle("Downloading Termux bootstrap for $arch: $url")
            downloadTo(url, cachedZip)
        } else {
            logger.lifecycle("Using cached bootstrap zip: ${cachedZip.absolutePath}")
        }

        // Extract into a scratch dir first — we need SYMLINKS.txt processed
        // before we know which real files each wanted binary resolves to.
        val scratchDir = File(temporaryDir, "extracted").apply {
            deleteRecursively()
            mkdirs()
        }
        extractZip(cachedZip, scratchDir)

        val symlinks = resolveSymlinkMap(File(scratchDir, "SYMLINKS.txt"))

        // Clean previous outputs for this ABI so removed binaries don't linger.
        destDir.listFiles()?.forEach { it.delete() }

        var packaged = 0
        for (name in wanted) {
            val sourceFile = locateBinary(scratchDir, name, symlinks)
            if (sourceFile == null) {
                logger.warn("bootstrap-fetch: binary '$name' not found in bootstrap for $arch — skipped.")
                continue
            }
            val target = File(destDir, "lib$name.so")
            sourceFile.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            packaged++
        }

        logger.lifecycle("bootstrap-fetch: packaged $packaged/${wanted.size} binaries into ${destDir.absolutePath}")
        check(packaged > 0) {
            "bootstrap-fetch produced zero binaries for $abiName — check `binaries` and bootstrapTag are correct."
        }
    }

    /**
     * Parse SYMLINKS.txt into a map of `symlinkPath -> target`.
     *
     * VERIFIED FORMAT (checked directly against a real bootstrap archive's
     * SYMLINKS.txt): each line is `target←symlinkPath`, using a literal
     * U+2190 (←) separator — NOT `target→symlinkPath` and not whitespace
     * separated, as an earlier version of this task guessed. Examples
     * actually observed:
     *   `dash←./bin/sh`                      (sh is a symlink to dash)
     *   `coreutils←./bin/shred`               (shred is a symlink to coreutils)
     *   `../../LICENSES/GPL-3.0.txt←./share/doc/tar/copyright`
     *   `/data/data/com.termux/files/usr/...←./share/pacman/keyrings/...`
     *     (target is an absolute runtime path — points outside the
     *     archive, not resolvable at build time)
     *
     * The symlinkPath side (right of ←) is always written relative to the
     * archive root (e.g. "./bin/sh"); we strip the leading "./" so it
     * matches the plain "bin/sh" form [locateBinary] looks up.
     */
    private fun resolveSymlinkMap(symlinksFile: File): Map<String, String> {
        if (!symlinksFile.exists()) return emptyMap()
        val map = mutableMapOf<String, String>()
        symlinksFile.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val sepIndex = line.indexOf('\u2190') // ←
            if (sepIndex < 0) return@forEachLine
            val target = line.substring(0, sepIndex).trim()
            val symlinkPath = line.substring(sepIndex + 1).trim().removePrefix("./")
            map[symlinkPath] = target
        }
        return map
    }

    /**
     * Find [name] under the bootstrap archive's `bin/` directory, following
     * symlinks via [symlinks] if the direct file doesn't exist.
     *
     * NOTE ON LAYOUT: verified directly against a real
     * bootstrap-aarch64.zip — the archive root contains `bin/`, `etc/`,
     * `lib/`, etc. directly, with NO `usr/` prefix (unlike the runtime
     * install path `/data/data/.../files/usr/bin/...`, which is where
     * Termux itself extracts it to — that `usr/` segment is added at
     * install time, not present in the archive itself). An earlier version
     * of this task incorrectly assumed a `usr/bin/` prefix inside the zip;
     * fixed after inspecting the real archive contents.
     */
    private fun locateBinary(scratchDir: File, name: String, symlinks: Map<String, String>): File? {
        var relPath = "bin/$name"
        var direct = File(scratchDir, relPath)
        var hops = 0
        while (!direct.exists() && hops < 5) {
            val target = symlinks[relPath] ?: return null
            relPath = resolveSymlinkTarget(relPath, target)
            direct = File(scratchDir, relPath)
            hops++
        }
        return direct.takeIf { it.exists() && it.isFile }
    }

    /**
     * Resolve a SYMLINKS.txt target against the directory of the symlink
     * that points to it. Handles the three forms actually observed in a
     * real bootstrap archive:
     *   - bare name, e.g. "coreutils"      -> sibling file in same dir
     *   - relative path, e.g. "../../LICENSES/x.txt" -> resolved from symlink's dir
     *   - absolute runtime path, e.g. "/data/data/com.termux/files/usr/..."
     *     -> not resolvable inside the archive itself; treated as a dead
     *     end (returns the runtime path as-is, which locateBinary's
     *     existence check will correctly reject).
     */
    private fun resolveSymlinkTarget(symlinkRelPath: String, target: String): String {
        if (target.startsWith("/")) return target // absolute runtime path — won't exist in scratchDir, ends the walk
        val symlinkDir = File(symlinkRelPath).parent ?: "."
        return File(symlinkDir, target).normalize().path
    }

    private fun downloadTo(urlStr: String, dest: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "LibTermux-Android-BuildLogic/1.0")
        conn.instanceFollowRedirects = true
        conn.connect()
        check(conn.responseCode in 200..299) {
            "Failed to download bootstrap zip: HTTP ${conn.responseCode} from $urlStr"
        }
        dest.parentFile.mkdirs()
        conn.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

/**
 * Downloads `proot` from Termux's own official `.deb` package repository
 * and places the extracted binary at `jniLibs/<abi>/libproot.so`.
 *
 * ## Why this is a separate task from [FetchBootstrapBinariesTask]
 * Verified directly against a real `bootstrap-aarch64.zip`: it contains no
 * `proot` binary anywhere, and no SYMLINKS.txt entry pointing to one.
 * Confirmed independently by termux/proot-distro's own README, which
 * describes `proot` as a separate `pkg install proot` dependency —
 * i.e. its own `.deb`, not something bundled in the base bootstrap.
 *
 * ## Official source (corrected from an earlier third-party-mirror version)
 * An earlier version of this task downloaded a prebuilt proot binary from
 * skirsten/proot-portable-android-binaries, a small unofficial GitHub
 * Pages mirror. That approach is REPLACED here with Termux's own official,
 * team-signed package repository — the same source `pkg install proot`
 * itself pulls from on-device, confirmed live at packages.termux.dev — verified directly:
 *   https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_<version>_<arch>.deb
 * This matches how Termux's own app handles ALL package installation on
 * Android 10+: per termux-packages wiki ("Termux and Android 10"), *.deb
 * package contents are placed into APK-bundled JNI-lib directories (marked
 * executable by the OS) rather than downloaded loose into app-private
 * storage — this task follows that same approach: fetch the official
 * .deb at *build* time, extract it, and let AGP bundle the binary into
 * jniLibs so PackageManager extracts it with execute permission intact.
 * The `.deb` itself is unpacked with the system `dpkg-deb` tool (see
 * [extractDeb]) rather than a hand-rolled Kotlin tar/xz reader, since
 * Termux's data.tar payload may be gzip, xz, or zstd compressed depending
 * on the package build — dpkg-deb handles all three correctly; Java's
 * built-in java.util.zip does not support xz or zstd.
 *
 * ## Requirements
 * Requires `dpkg-deb` (and `ar`, which `dpkg-deb -x` uses internally) on
 * the build machine's PATH. Present by default on Debian/Ubuntu CI images
 * (including GitHub Actions' ubuntu-latest runners) and installable via
 * `apt-get install dpkg` elsewhere. Not available out of the box on
 * Windows or macOS build machines — those need WSL/a container, or
 * `brew install dpkg` on macOS.
 */
@org.gradle.api.tasks.CacheableTask
abstract class FetchProotBinaryTask : DefaultTask() {

    @get:Input abstract val abi: Property<String>
    /** Termux arch name for the .deb filename, e.g. "aarch64" — same values as [BootstrapFetchExtension.termuxArch]. */
    @get:Input abstract val prootArch: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /**
     * Gradle user home directory, captured at configuration time — see the
     * matching property on [FetchBootstrapBinariesTask] for why `project`
     * can't be read directly inside [fetch] under the configuration cache.
     */
    @get:org.gradle.api.tasks.Internal
    abstract val gradleUserHomeDir: DirectoryProperty

    // Injected rather than using the deprecated Project.exec { } — required
    // for configuration-cache compatibility under Gradle 8+/9+, since a
    // @TaskAction should not reach back into `project` at execution time.
    @get:javax.inject.Inject
    abstract val execOperations: org.gradle.process.ExecOperations

    @TaskAction
    fun fetch() {
        val abiName = abi.get()
        val arch = prootArch.get()
        val destDir = outputDir.get().dir(abiName).asFile.also { it.mkdirs() }

        val cacheDir = File(gradleUserHomeDir.get().asFile, "libtermux-bootstrap-cache")
        cacheDir.mkdirs()

        val debFile = resolveAndDownloadDeb(arch, cacheDir)

        val extractDir = File(temporaryDir, "proot-extracted-$arch").apply {
            deleteRecursively()
            mkdirs()
        }
        extractDeb(debFile, extractDir)

        // Termux packages install under /data/data/com.termux/files/usr/...
        // inside data.tar — the proot binary specifically lands at usr/bin/proot.
        val extractedBinary = File(extractDir, "data/data/com.termux/files/usr/bin/proot")
        check(extractedBinary.exists()) {
            "proot binary not found at expected path inside the .deb after extraction: " +
            "${extractedBinary.absolutePath}. The Termux package layout may have changed — " +
            "run `dpkg-deb -c ${debFile.absolutePath}` manually to inspect its actual contents."
        }

        val target = File(destDir, "libproot.so")
        extractedBinary.copyTo(target, overwrite = true)
        target.setExecutable(true, false)
        logger.lifecycle("fetchProotBinary: placed official Termux proot at ${target.absolutePath}")
    }

    /**
     * Resolve the exact `.deb` filename for [arch] by listing the package
     * pool directory (Termux doesn't publish a stable "latest" URL — the
     * version number is embedded in the filename, e.g.
     * `proot_5.1.107-65_aarch64.deb`), then download it.
     */
    private fun resolveAndDownloadDeb(arch: String, cacheDir: File): File {
        val poolUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/"
        val listing = fetchText(poolUrl)

        // Directory listing HTML contains href="proot_<version>_<arch>.deb" entries.
        val pattern = Regex("""href="(proot_[^"]*_$arch\.deb)"""")
        val match = pattern.find(listing)
            ?: error(
                "Could not find a proot_..._$arch.deb entry in the Termux package pool " +
                "listing at $poolUrl — the repository layout may have changed."
            )
        val filename = match.groupValues[1]
        val cached = File(cacheDir, filename)

        if (!cached.exists()) {
            val debUrl = poolUrl + filename
            logger.lifecycle("Downloading official Termux proot package: $debUrl")
            downloadTo(debUrl, cached)
        } else {
            logger.lifecycle("Using cached proot .deb: ${cached.absolutePath}")
        }
        return cached
    }

    /**
     * Extract a .deb's data archive using the system `dpkg-deb` tool.
     * Handles gzip/xz/zstd data.tar compression transparently — a hand
     * -rolled Kotlin extractor would need separate xz/zstd decoder
     * dependencies to do the same (Java's built-in java.util.zip only
     * covers gzip/deflate).
     */
    private fun extractDeb(debFile: File, destDir: File) {
        val result = execOperations.exec {
            commandLine("dpkg-deb", "-x", debFile.absolutePath, destDir.absolutePath)
            isIgnoreExitValue = true
        }
        check(result.exitValue == 0) {
            "dpkg-deb -x failed for ${debFile.absolutePath} (exit ${result.exitValue}). " +
            "Ensure dpkg-deb is installed on the build machine (apt-get install dpkg on " +
            "Debian/Ubuntu CI images; brew install dpkg on macOS)."
        }
    }

    private fun fetchText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "LibTermux-Android-BuildLogic/1.0")
        conn.instanceFollowRedirects = true
        conn.connect()
        check(conn.responseCode in 200..299) {
            "Failed to list Termux package pool: HTTP ${conn.responseCode} from $urlStr"
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadTo(urlStr: String, dest: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "LibTermux-Android-BuildLogic/1.0")
        conn.instanceFollowRedirects = true
        conn.connect()
        check(conn.responseCode in 200..299) {
            "Failed to download proot .deb: HTTP ${conn.responseCode} from $urlStr"
        }
        dest.parentFile.mkdirs()
        conn.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
    }
}
