plugins { id("com.android.library"); kotlin("android"); kotlin("plugin.serialization"); kotlin("plugin.compose") }
android {
    namespace = "com.github.ytlog.mobby.android.localmodel"
    compileSdk = 35
    defaultConfig { minSdk = 26; ndk { abiFilters += "arm64-v8a" } }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":local-model-backend-llama"))
    implementation(project(":localization"))
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    testImplementation("junit:junit:4.13.2")
}
