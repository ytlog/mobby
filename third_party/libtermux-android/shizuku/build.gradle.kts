plugins {
    alias(libs.plugins.android.library)
    // AGP 9.0+ has built-in Kotlin — kotlin.android must NOT be applied here.
}

android {
    namespace = "com.libtermux.shizuku"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}