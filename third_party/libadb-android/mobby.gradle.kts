// mobby builds the pinned library sources with its own toolchain. See docs/design/builtin-adb-screen.md.
plugins { id("com.android.library") }
android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    sourceSets["main"].java.srcDirs("libadb/src/main/java")
    sourceSets["main"].manifest.srcFile("libadb/src/main/AndroidManifest.xml")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation(project(":screen-adb-spake2"))
}
