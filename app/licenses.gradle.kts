import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

afterEvaluate {
val externalArtifacts = configurations.getByName("releaseRuntimeClasspath").incoming.artifactView {
    componentFilter { it is ModuleComponentIdentifier }
}.artifacts
val licenseMetadata = layout.buildDirectory.file("generated/license-metadata/artifacts.json")
val writeLicenseMetadata by tasks.registering {
    inputs.files(externalArtifacts.artifactFiles)
    outputs.file(licenseMetadata)
    doLast {
        val rows = externalArtifacts.artifacts.map { artifact ->
            val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
            mapOf("group" to id.group, "name" to id.module, "version" to id.version, "file" to artifact.file.absolutePath)
        }
        licenseMetadata.get().asFile.apply { parentFile.mkdirs(); writeText(JsonOutput.toJson(rows)) }
    }
}
val generateLicenseNotices by tasks.registering(Exec::class) {
    dependsOn(writeLicenseMetadata)
    inputs.file(licenseMetadata)
    inputs.file(rootProject.file("runtime/maven_notices.py"))
    inputs.dir(rootProject.file("third_party/maven"))
    inputs.dir(rootProject.file("third_party/onnxruntime"))
    inputs.file(rootProject.file("third_party/codex/LICENSE"))
    outputs.file(layout.buildDirectory.file("generated/license-assets/third-party/maven.json"))
    commandLine("python3", rootProject.file("runtime/maven_notices.py"), "--artifacts", licenseMetadata.get().asFile,
        "--cache", File(gradle.gradleUserHomeDir, "caches/modules-2/files-2.1"),
        "--output", layout.buildDirectory.file("generated/license-assets/third-party/maven.json").get().asFile)
}
tasks.named("preBuild") { dependsOn(generateLicenseNotices) }

}
