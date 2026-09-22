pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "mobby"
include(":app", ":termux-core", ":bootstrap-arm64")
project(":termux-core").projectDir = file("third_party/libtermux-android/core")
project(":termux-core").buildFileName = "mobby.gradle.kts"
project(":bootstrap-arm64").projectDir = file("third_party/libtermux-android/bootstrap-arm64")
project(":bootstrap-arm64").buildFileName = "mobby.gradle.kts"

include(":runtime-api", ":interaction-domain")
include(":runtime-engine", ":runtime-android")
include(":interaction-data", ":interaction-ui", ":speech", ":device-plugins")
