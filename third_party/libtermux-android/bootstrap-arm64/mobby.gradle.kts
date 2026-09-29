plugins { id("com.android.library") }
val prepareBootstrap by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("runtime/prepare_bootstrap.py"), rootProject.file("runtime/bootstrap.lock.json"), rootProject.file("runtime/agents.lock.json"), rootProject.file("runtime/agent_bundle.py"), rootProject.file("runtime/agent_launcher.c"))
    inputs.files(rootProject.file("runtime/pi-package/package.json"), rootProject.file("runtime/pi-package/package-lock.json"))
    inputs.files(rootProject.file("runtime/release-sources.lock.json"), rootProject.file("third_party/codex/dependency-notices.json"), rootProject.file("third_party/bun/dependency-notices.json"), rootProject.file("third_party/opencode-termux/dependency-notices.json"))
    inputs.files(listOf("LICENSE", "third_party/NOTICE.md", "third_party/codex/LICENSE", "third_party/codex/NOTICE", "third_party/bubblewrap/COPYING", "third_party/opencode-termux/LICENSE", "third_party/opencode-termux/OpenCode-LICENSE", "third_party/bun/LICENSE.md", "third_party/bun/JavaScriptCore-COPYING.LIB", "third_party/bun/TinyCC-COPYING", "third_party/musl/COPYRIGHT", "third_party/gcc/COPYING3", "third_party/gcc/COPYING.RUNTIME", "third_party/libtermux-android/LICENSE", "third_party/llama.cpp/LICENSE", "third_party/kleidiai/LICENSE", "third_party/kleidiai/NOTICE", "third_party/android-cxx/libc++-LICENSE", "third_party/android-cxx/libc++abi-LICENSE", "third_party/android-cxx/unwind-compiler-runtime-LICENSE").map { rootProject.file(it) })
    outputs.dir(layout.buildDirectory.dir("generated/bootstrap"))
    commandLine("python3", rootProject.file("runtime/prepare_bootstrap.py"), "--output", layout.buildDirectory.dir("generated/bootstrap").get().asFile, "--ndk", File(android.sdkDirectory, "ndk/27.2.12479018"))
}
android {
    namespace = "com.github.ytlog.mobby.android.bootstrap.arm64"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig { minSdk = 26 }
    packaging { jniLibs.keepDebugSymbols += "**/*.so" }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/bootstrap/jniLibs"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/bootstrap/assets"))
}
tasks.named("preBuild") { dependsOn(prepareBootstrap) }
