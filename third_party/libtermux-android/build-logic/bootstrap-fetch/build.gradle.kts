plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Needed so the precompiled `libtermux.bootstrap-module` script plugin
    // can configure { LibraryExtension }. MUST match the AGP version the
    // rest of the project uses (gradle/libs.versions.toml -> agp) — this
    // included build can't read that catalog itself, so keep these in sync
    // by hand when bumping AGP.
    //
    // AGP 9.0 changed the LibraryExtension/CommonExtension DSL shape
    // (parameterization removed — see AGP 9.0 release notes). This was
    // written against 9.2.1's API as best-known; if `configure<LibraryExtension>`
    // fails to compile, check the current LibraryExtension surface against
    // whatever AGP version actually resolves here.
    compileOnly("com.android.tools.build:gradle:9.2.1")
}

gradlePlugin {
    plugins {
        create("bootstrapFetch") {
            id = "com.libtermux.bootstrap-fetch"
            implementationClass = "com.libtermux.buildlogic.BootstrapFetchPlugin"
        }
    }
}
