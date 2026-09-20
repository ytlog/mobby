plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0+ has built-in Kotlin — kotlin.android must NOT be applied here.
}

android {
    namespace = "com.libtermux.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.libtermux.sample"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
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
    implementation(project(":core"))
    implementation(project(":terminal-view"))
    // Demonstrates the install-time-executable fix — without this, bash
    // exec fails with EACCES on Android 10+. See NativeLibBootstrapProvider
    // usage in MainViewModel.kt. Real apps should add every ABI they ship
    // for (bootstrap-arm, bootstrap-x86_64, bootstrap-x86 alongside this).
    implementation(project(":bootstrap-arm64"))

    // UI Dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)                         // ✅ Now defined
    implementation(libs.androidx.constraintlayout)        // ✅ Now defined

    // Lifecycle & Coroutines
    implementation(libs.androidx.lifecycle.runtime.ktx)   // ✅ Now defined
    implementation(libs.androidx.lifecycle.viewmodel.ktx) // ✅ Now defined
    implementation(libs.androidx.activity.ktx)            // ✅ Now defined
    implementation(libs.kotlinx.coroutines.android)
}
