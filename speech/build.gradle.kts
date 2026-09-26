plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.github.ytlog.mobby.android.speech"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":localization"))
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8@aar")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
