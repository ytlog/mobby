plugins { id("com.android.library") }
val prepareBootstrap by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("runtime/prepare_bootstrap.py"), rootProject.file("runtime/bootstrap.lock.json"), rootProject.file("runtime/agents.lock.json"), rootProject.file("runtime/agent_bundle.py"), rootProject.file("runtime/agent_launcher.c"))
    outputs.dir(layout.buildDirectory.dir("generated/bootstrap"))
    commandLine("python3", rootProject.file("runtime/prepare_bootstrap.py"), "--output", layout.buildDirectory.dir("generated/bootstrap").get().asFile, "--ndk", File(android.sdkDirectory, "ndk/27.2.12479018"))
}
android {
    namespace = "com.mobby.bootstrap.arm64"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig { minSdk = 26 }
    packaging { jniLibs.keepDebugSymbols += "**/*.so" }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/bootstrap/jniLibs"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/bootstrap/assets"))
}
tasks.named("preBuild") { dependsOn(prepareBootstrap) }
