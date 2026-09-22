plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.github.ytlog.mobby.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        // Install identity. Changing it again creates a separate app and does not read the previous install's data.
        applicationId = "com.github.ytlog.mobby.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
}
dependencies {
    implementation(project(":interaction-ui"))
    implementation(project(":interaction-domain"))
    implementation(project(":interaction-data"))
    implementation(project(":runtime-api"))
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
