/**
 * LibTermux-Android — bootstrap-x86_64
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Bundles Termux bootstrap binaries for the x86_64 ABI (emulators, some
 * Chromebooks/tablets). See bootstrap-arm64/build.gradle.kts for full
 * module documentation.
 */
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("libtermux.bootstrap-module")
    id("com.libtermux.bootstrap-fetch")
    alias(libs.plugins.maven.publish)
}

bootstrapFetch {
    abi.set("x86_64")
    termuxArch.set("x86_64")
    bootstrapTag.set("bootstrap-2026.05.24-r1+apt.android-7")
    binaries.set(setOf("bash", "apt", "dpkg", "tar"))
    includeProot.set(true)
    prootArch.set("x86_64")
}

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant           = "release",
            sourcesJar        = false,
            publishJavadocJar = false,
        )
    )
    publishToMavenCentral(SonatypeHost.S01)
    signAllPublications()

    coordinates(
        groupId    = project.property("LIB_GROUP_ID").toString(),
        artifactId = "libtermux-android-bootstrap-x86_64",
        version    = project.property("LIB_VERSION").toString(),
    )

    pom {
        name.set("LibTermux Bootstrap (x86_64)")
        description.set("Termux bootstrap binaries for x86_64, packaged for install-time-executable use with libtermux-android")
        url.set(project.property("LIB_URL").toString())
        licenses {
            license {
                name.set(project.property("LIB_LICENSE_NAME").toString())
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set(project.property("LIB_DEVELOPER_ID").toString())
                name.set(project.property("LIB_DEVELOPER_NAME").toString())
            }
        }
        scm {
            url.set(project.property("LIB_URL").toString())
        }
    }
}
