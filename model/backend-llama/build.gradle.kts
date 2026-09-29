plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.github.ytlog.mobby.android.localmodel.llama"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += listOf("-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF", "-DGGML_LLAMAFILE=OFF", "-DLLAMA_OPENSSL=OFF", "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=OFF", "-DLLAMA_BUILD_SERVER=OFF", "-DLLAMA_BUILD_COMMON=OFF", "-DGGML_CPU_KLEIDIAI=ON")
            if (providers.gradleProperty("mobby.requireVulkan").orNull == "true") arguments += "-DMOBBY_REQUIRE_VULKAN=ON"
        } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
