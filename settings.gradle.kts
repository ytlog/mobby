pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Mdoer"
include(":app", ":termux-core", ":bootstrap-arm64")
project(":termux-core").projectDir = file("third_party/libtermux-android/core")
project(":termux-core").buildFileName = "mdoer.gradle.kts"
project(":bootstrap-arm64").projectDir = file("third_party/libtermux-android/bootstrap-arm64")
project(":bootstrap-arm64").buildFileName = "mdoer.gradle.kts"
