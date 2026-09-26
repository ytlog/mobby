import java.net.URL
import java.security.MessageDigest

plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.github.ytlog.mobby.android.speech"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
tasks.register("fetchSherpaOnnx") {
    val target = layout.buildDirectory.file("vendor/sherpa-onnx-1.13.8.aar")
    outputs.file(target)
    outputs.upToDateWhen { false }
    doLast {
        val file = target.get().asFile
        val expected = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
        fun valid() = file.isFile && MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) } == expected
        if (!valid()) {
            file.parentFile.mkdirs()
            val partial = file.resolveSibling("${file.name}.partial")
            URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar")
                .openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            partial.renameTo(file)
            check(valid()) { "Sherpa-ONNX AAR checksum mismatch" }
        }
    }
}

dependencies {
    implementation(project(":localization"))
    implementation(files(layout.buildDirectory.file("vendor/sherpa-onnx-1.13.8.aar")).builtBy(tasks.named("fetchSherpaOnnx")))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
