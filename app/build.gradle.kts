plugins { id("com.android.application"); kotlin("android") }

val releaseStoreFile = providers.environmentVariable("MOBBY_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("MOBBY_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MOBBY_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MOBBY_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
require(releaseSigningValues.all { it.isNullOrBlank() } || releaseSigningValues.all { !it.isNullOrBlank() }) {
    "Release signing requires all four MOBBY_RELEASE_* environment variables"
}

android {
    namespace = "com.github.ytlog.mobby.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        // Install identity. Changing it again creates a separate app and does not read the previous install's data.
        applicationId = "com.github.ytlog.mobby.android"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("mobby.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("mobby.versionName").orNull ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.0" }
    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank()) {
            create("mobbyRelease") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!releaseStoreFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("mobbyRelease")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += "**/*.so"
        // OnlineRecognizer loads only the JNI library, whose sole bundled dependency is ONNX Runtime.
        excludes += "**/libsherpa-onnx-c-api.so"
        excludes += "**/libsherpa-onnx-cxx-api.so"
    } }
}
dependencies {
    implementation(project(":local-model"))
    implementation(project(":localization"))
    implementation(project(":interaction-ui"))
    implementation(project(":interaction-domain"))
    implementation(project(":interaction-data"))
    implementation(project(":runtime-api"))
    implementation(project(":device-plugins"))
    androidTestImplementation(project(":runtime-engine"))
    implementation(project(":runtime-android"))
    androidTestImplementation(project(":termux-core"))
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
