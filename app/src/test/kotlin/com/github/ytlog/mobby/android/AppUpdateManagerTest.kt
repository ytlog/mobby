package com.github.ytlog.mobby.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateManagerTest {
    private val sha = "a".repeat(64)

    @Test fun parsesMatchingReleaseMetadata() {
        val release = AppUpdateManager.Release.parse(metadata("0.2.0", 12, "mobby-v0.2.0-arm64-v8a.apk", sha), "v0.2.0")
        assertEquals(12, release.versionCode)
        assertEquals("mobby-v0.2.0-arm64-v8a.apk", release.apk)
    }

    @Test fun rejectsDifferentTagAndPathLikeAsset() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateManager.Release.parse(metadata("0.2.0", 12, "mobby-v0.2.0-arm64-v8a.apk", sha), "v0.3.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateManager.Release.parse(metadata("0.2.0", 12, "../mobby.apk", sha), "v0.2.0")
        }
    }

    @Test fun rejectsMalformedChecksumAndVersionCode() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateManager.Release.parse(metadata("0.2.0", 0, "mobby-v0.2.0-arm64-v8a.apk", sha), "v0.2.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateManager.Release.parse(metadata("0.2.0", 12, "mobby-v0.2.0-arm64-v8a.apk", "not-a-hash"), "v0.2.0")
        }
    }

    private fun metadata(version: String, code: Int, apk: String, checksum: String) =
        """{"schemaVersion":1,"version":"$version","versionCode":$code,"apk":"$apk","sha256":"$checksum"}"""
}
