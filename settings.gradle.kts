pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.k2-fsa.sherpa-onnx") } }
    }
}
rootProject.name = "mobby"
include(":app", ":termux-core", ":bootstrap-arm64")
project(":termux-core").projectDir = file("third_party/libtermux-android/core")
project(":termux-core").buildFileName = "mobby.gradle.kts"
project(":bootstrap-arm64").projectDir = file("third_party/libtermux-android/bootstrap-arm64")
project(":bootstrap-arm64").buildFileName = "mobby.gradle.kts"

include(":runtime-api", ":interaction-domain")
include(":runtime-engine", ":runtime-android")
include(":interaction-data", ":speech", ":device-plugins")

include(":localization")

include(":local-model", ":local-model-backend-llama")

// Physical directories follow vertical responsibilities; each leaf is one Gradle module.
project(":runtime-api").projectDir = file("runtime/api")
project(":runtime-engine").projectDir = file("runtime/engine")
project(":runtime-android").projectDir = file("runtime/android")
project(":device-plugins").projectDir = file("runtime/device-plugins")
project(":interaction-domain").projectDir = file("interaction/domain")
project(":interaction-data").projectDir = file("interaction/data")
project(":local-model").projectDir = file("model/service")
project(":local-model-backend-llama").projectDir = file("model/backend-llama")
project(":speech").projectDir = file("shared/speech")
project(":localization").projectDir = file("shared/localization")
