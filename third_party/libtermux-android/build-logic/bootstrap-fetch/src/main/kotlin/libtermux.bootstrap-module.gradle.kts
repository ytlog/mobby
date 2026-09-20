/**
 * LibTermux-Android — build-logic
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Precompiled convention plugin shared by every bootstrap-<abi> module.
 * Each module's own build.gradle.kts applies this plugin plus
 * `com.libtermux.bootstrap-fetch`, then only sets its own ABI-specific
 * bootstrapFetch { } values — everything else (AGP config, publishing,
 * wireBinariesToBuild) lives here exactly once.
 */
import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.configure

plugins {
    id("com.android.library")
}

configure<LibraryExtension> {
    namespace = "com.libtermux.bootstrap"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // No source code, no native build of our own — this module's sole
        // job is shipping prebuilt binaries under src/main/jniLibs/<abi>/.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Make sure binaries are fetched before anything reads src/main/jniLibs/.
//
// This is deliberately over-broad rather than targeting one "the" task name,
// because AGP's exact task graph for a *library* module's jniLibs pickup
// has shifted across AGP versions and isn't something we can execute-verify
// in this environment. Three independent hooks, so a naming mismatch in any
// one of them doesn't silently produce an empty jniLibs directory:
//
//   1. preBuild — always exists, always runs first, on every AGP version.
//   2. Any task whose name contains "JniLib" (case-insensitive) — covers
//      mergeDebugJniLibFolders, mergeReleaseJniLibFolders, and whatever
//      AGP 9.x specifically calls it, without hardcoding one exact string.
//   3. Any AAR-bundling task (bundle*Aar) — the actual artifact-producing
//      task read by consumers; if jniLibs picked up nothing by the time
//      this runs, the AAR would ship empty regardless of #1/#2.
//
// If `fetchBootstrapBinaries` still doesn't appear to run, verify manually:
//   ./gradlew :bootstrap-arm64:fetchBootstrapBinaries --stacktrace
// and check src/main/jniLibs/arm64-v8a/ was populated directly, independent
// of whatever task ends up consuming it.
val fetchTaskName = "fetchBootstrapBinaries"

tasks.matching { it.name == "preBuild" }
    .configureEach { dependsOn(fetchTaskName) }

tasks.matching { it.name.contains("JniLib", ignoreCase = true) }
    .configureEach { dependsOn(fetchTaskName) }

tasks.matching { it.name.startsWith("bundle") && it.name.endsWith("Aar") }
    .configureEach { dependsOn(fetchTaskName) }

// Belt-and-suspenders: also run it eagerly whenever this project is
// configured at all, so `./gradlew :sample:assembleDebug` (which configures
// :bootstrap-arm64 as a dependency but may not touch any of the task names
// matched above) still has jniLibs populated before Gradle decides what
// needs rebuilding.
project.afterEvaluate {
    tasks.findByName(fetchTaskName)?.let { fetchTask ->
        tasks.matching { it != fetchTask && it.name != fetchTaskName }
            .matching { it.name == "assemble" || it.name.startsWith("assemble") }
            .configureEach { dependsOn(fetchTask) }
    }
}
