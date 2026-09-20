plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":runtime-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
