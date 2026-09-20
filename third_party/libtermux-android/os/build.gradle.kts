/**
 * LibTermux-Android — OS Module Build Script
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Separate Gradle module for Linux distro management:
 *   • proot-based distro environments (no root required)
 *   • Real chroot environments (rooted devices)
 *   • VNC-based graphical desktop in Jetpack Compose
 *   • Distro settings persistence via DataStore
 *
 * Usage in app build.gradle.kts:
 *   implementation(project(":core"))   // always required
 *   implementation(project(":os"))     // add if you need distro support
 */
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    // AGP 9.0+ has built-in Kotlin — kotlin.android must NOT be applied here.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

android {
    namespace  = "com.libtermux.os"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ── Core SDK (api = transitive for library consumers) ─────────────────
    api(project(":core"))

    // ── Jetpack Compose ───────────────────────────────────────────────────
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── DataStore — distro settings persistence ───────────────────────────
    implementation(libs.androidx.datastore.preferences)

    // ── Networking — rootfs download ──────────────────────────────────────
    // okhttp is already api()'d from :core, but explicit here for clarity
    implementation(libs.okhttp)

    // ── Testing ───────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ── Maven Publishing ──────────────────────────────────────────────────────────
mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant           = "release",
            sourcesJar        = true,
            publishJavadocJar = true,
        )
    )

    publishToMavenCentral(SonatypeHost.S01)
    signAllPublications()

    coordinates(
        groupId    = project.property("LIB_GROUP_ID").toString(),
        artifactId = "libtermux-os",
        version    = project.property("LIB_VERSION").toString(),
    )

    pom {
        name.set("LibTermux OS Module")
        description.set("Linux distro management with proot/chroot and VNC desktop for Android")
        url.set(project.property("LIB_URL").toString())
        licenses {
            license {
                name.set(project.property("LIB_LICENSE_NAME").toString())
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set(project.property("LIB_DEVELOPER_ID").toString())
                name.set(project.property("LIB_DEVELOPER_NAME").toString())
            }
        }
        scm { url.set(project.property("LIB_URL").toString()) }
    }
}
