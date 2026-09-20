/**
 * LibTermux-Android — Root Project Settings
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 */
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "libtermux-android"

// Core SDK — bootstrap, executor, bridge, file system
include(":core")

// OS Module — Linux distro management, proot/chroot, VNC GUI
// Depends on :core. Add separately if your app needs distro support.
include(":os")

// Terminal UI widget
include(":terminal-view")

// Optional: Shizuku elevated execution module
include(":shizuku")

// Per-ABI bootstrap artifacts — bundle Termux bootstrap binaries (bash,
// apt, dpkg, busybox, proot, tar) as jniLibs/<abi>/lib*.so so Android's
// installer extracts them with execute permission intact (required on
// API 29+; see core/bootstrap/BootstrapProvider.kt). Consumers add only
// the ABI(s) their app targets, keeping :core itself lightweight.
include(":bootstrap-arm64")
include(":bootstrap-arm")
include(":bootstrap-x86_64")
include(":bootstrap-x86")

// Demo application
include(":sample")
