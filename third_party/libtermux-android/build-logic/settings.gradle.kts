/**
 * LibTermux-Android — build-logic included build.
 * Hosts the shared FetchBootstrapBinaries task used by every
 * bootstrap-<abi> module, so the download/rename/jniLibs logic lives
 * in exactly one place instead of being copy-pasted per ABI.
 */
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
include(":bootstrap-fetch")
