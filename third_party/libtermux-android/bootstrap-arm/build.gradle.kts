/**
 * LibTermux-Android — bootstrap-arm
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Bundles Termux bootstrap binaries for the armeabi-v7a (32-bit ARM) ABI.
 * See bootstrap-arm64/build.gradle.kts for full module documentation.
 */
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("libtermux.bootstrap-module")
    id("com.libtermux.bootstrap-fetch")
    alias(libs.plugins.maven.publish)
}

bootstrapFetch {
    abi.set("armeabi-v7a")
    termuxArch.set("arm")
    bootstrapTag.set("bootstrap-2026.05.24-r1+apt.android-7")
    // busybox and proot are NOT in the Termux bootstrap archive — verified
    // directly against a real bootstrap-aarch64.zip. Do not add them here.
    binaries.set(setOf("bash", "apt", "dpkg", "tar"))
    includeProot.set(true)
    // Termux's official proot .deb uses "arm" (matches termuxArch above) —
    // note this module previously used "armv7" for a since-removed
    // third-party binary source with different naming; corrected here.
    prootArch.set("arm")
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
        artifactId = "libtermux-android-bootstrap-arm",
        version    = project.property("LIB_VERSION").toString(),
    )

    pom {
        name.set("LibTermux Bootstrap (armeabi-v7a)")
        description.set("Termux bootstrap binaries for armeabi-v7a, packaged for install-time-executable use with libtermux-android")
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
