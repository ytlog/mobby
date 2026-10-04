package com.github.ytlog.mobby.android

import com.github.ytlog.mobby.android.conversation.ui.AppUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateManagerTest {
    private val sha = "a".repeat(64)

    @Test fun restoresRecentSuccessfulCheckAfterProcessRestart() {
        val context = RuntimeEnvironment.getApplication()
        val code = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val prefs = context.getSharedPreferences("mobby.updates", 0)
        try {
            prefs.edit().putLong("last_check", System.currentTimeMillis())
                .putString("last_outcome", "current").putLong("checked_app_code", code).commit()
            assertEquals(AppUpdateManager.Phase.CURRENT, AppUpdateManager(context).state.phase)
        } finally { prefs.edit().clear().commit() }
    }

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
