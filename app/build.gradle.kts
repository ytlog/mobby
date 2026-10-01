plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose") }

val releaseStoreFile = providers.environmentVariable("MOBBY_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("MOBBY_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MOBBY_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MOBBY_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = releaseSigningValues.any { !it.isNullOrBlank() }

android {
    namespace = "com.github.ytlog.mobby.android"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        // Install identity. Changing it again creates a separate app and does not read the previous install's data.
        applicationId = "com.github.ytlog.mobby.android"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("mobby.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("mobby.versionName").orNull ?: "0.1.0"
        testInstrumentationRunner = if (providers.gradleProperty("mobby.appFunctionsTestOnly").orNull == "true")
            "com.github.ytlog.mobby.android.AppFunctionTestRunner"
        else "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/license-assets"))
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("mobbyRelease") {
                storeFile = releaseStoreFile?.takeIf { it.isNotBlank() }?.let { file(it) }
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            providers.gradleProperty("mobby.debugApplicationIdSuffix").orNull?.takeIf { it.isNotBlank() }?.let {
                applicationIdSuffix = it
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // AGP validates this configuration only when building release; debug keeps its own signing.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("mobbyRelease")
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
    implementation(project(":speech"))
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.30.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.0")
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(project(":local-model"))
    implementation(project(":localization"))
    implementation(project(":conversation-domain"))
    implementation(project(":conversation-data"))
    implementation(project(":runtime-api"))
    implementation(project(":device-plugins"))
    androidTestImplementation(project(":runtime-engine"))
    androidTestImplementation("androidx.appfunctions:appfunctions:1.0.0-alpha08")
    implementation(project(":runtime-android"))
    androidTestImplementation(project(":termux-core"))
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}

apply(from = "licenses.gradle.kts")
