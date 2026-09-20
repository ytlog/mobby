/**
 * LibTermux-Android — bootstrap-arm64
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Bundles binaries needed by :core and :os for the arm64-v8a ABI, packaged
 * as src/main/jniLibs/arm64-v8a/lib*.so so Android's installer extracts
 * them with execute permission intact:
 *   - bash, apt, dpkg, tar — from the Termux bootstrap archive
 *   - proot — from Termux's official .deb package repository (see
 *     FetchProotBinaryTask in build-logic), needed by :os's ProotRunner
 *
 * Apps targeting only 64-bit ARM devices (the large majority of current
 * Android hardware) need only this one bootstrap artifact — see
 * core/bootstrap/BootstrapProvider.kt for how :core resolves binaries
 * bundled here at runtime.
 */
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("libtermux.bootstrap-module")
    id("com.libtermux.bootstrap-fetch")
    alias(libs.plugins.maven.publish)
}

bootstrapFetch {
    abi.set("arm64-v8a")
    termuxArch.set("aarch64")
    bootstrapTag.set("bootstrap-2026.05.24-r1+apt.android-7")
    // busybox and proot are NOT in the Termux bootstrap archive — verified
    // directly against a real bootstrap-aarch64.zip. Do not add them here.
    binaries.set(setOf("bash", "apt", "dpkg", "tar"))
    // proot comes from a separate source (see FetchProotBinaryTask) since
    // it's not bundled in the bootstrap; needed for :os's ProotRunner.
    // Included by default — the binary is small (a few hundred KB) relative
    // to splitting this into proot/no-proot module variants.
    includeProot.set(true)
    prootArch.set("aarch64")
}

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant           = "release",
            sourcesJar        = false, // no source code in this module
            publishJavadocJar = false,
        )
    )
    publishToMavenCentral(SonatypeHost.S01)
    signAllPublications()

    coordinates(
        groupId    = project.property("LIB_GROUP_ID").toString(),
        artifactId = "libtermux-android-bootstrap-arm64",
        version    = project.property("LIB_VERSION").toString(),
    )

    pom {
        name.set("LibTermux Bootstrap (arm64-v8a)")
        description.set("Termux bootstrap binaries for arm64-v8a, packaged for install-time-executable use with libtermux-android")
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
