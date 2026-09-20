/**
 * LibTermux-Android — Core Module Build Script
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Licensed under the Apache License, Version 2.0
 */
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    // AGP 9.0+ has built-in Kotlin — kotlin.android must NOT be applied here.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

android {
    namespace  = "com.libtermux"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags  += "-std=c++17"
                arguments("-DANDROID_STL=c++_shared")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isJniDebuggable = true
        }
    }

    buildFeatures {
        buildConfig = true
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
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // ─────────────────────────────────────────────────────────────────────
    // NOTE: Do NOT add android { publishing { singleVariant(...) } } here.
    //
    // The vanniktech maven-publish plugin (configured in mavenPublishing {}
    // below) calls singleVariant("release") internally via
    // AndroidSingleVariantLibrary. Adding it here too causes:
    //   "Using singleVariant publishing DSL multiple times … is not allowed."
    // ─────────────────────────────────────────────────────────────────────
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}

// ── Maven Publishing (vanniktech plugin handles singleVariant internally) ──
mavenPublishing {
    // AndroidSingleVariantLibrary publishes the "release" variant,
    // with sources and Javadoc JARs — no manual singleVariant() call needed.
    configure(
        AndroidSingleVariantLibrary(
            variant            = "release",
            sourcesJar         = true,
            publishJavadocJar  = true,
        )
    )

    publishToMavenCentral(SonatypeHost.S01)
    signAllPublications()

    coordinates(
        groupId    = project.property("LIB_GROUP_ID").toString(),
        artifactId = project.property("LIB_ARTIFACT_ID").toString(),
        version    = project.property("LIB_VERSION").toString(),
    )

    pom {
        name.set(project.property("LIB_NAME").toString())
        description.set(project.property("LIB_DESCRIPTION").toString())
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
        scm {
            url.set(project.property("LIB_URL").toString())
        }
    }
}