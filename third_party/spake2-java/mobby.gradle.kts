// Build the upstream Android implementation and its pinned C submodule using the host's existing NDK.
plugins { id("com.android.library") }
android {
    namespace = "io.github.muntashirakon.crypto.spake2"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-Wl,-z,max-page-size=16384" } }
    }
    sourceSets["main"].java.srcDirs("android/src/main/java")
    sourceSets["main"].manifest.srcFile("android/src/main/AndroidManifest.xml")
    externalNativeBuild { cmake { path = file("android/src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("androidx.annotation:annotation:1.9.1") }
