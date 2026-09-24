plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.github.ytlog.mobby.android.localmodel.mlc"
    compileSdk = 35
    defaultConfig { minSdk = 26; ndk { abiFilters += "arm64-v8a" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") }
