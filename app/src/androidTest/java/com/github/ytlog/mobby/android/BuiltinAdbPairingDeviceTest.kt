package com.github.ytlog.mobby.android

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.github.ytlog.mobby.android.conversation.ui.ScreenControlSettingsActivity
import com.github.ytlog.mobby.android.device.ScreenControl
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID

/** Opt-in real system pairing. A private test socket avoids putting the actual code in test arguments/logs. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
@android.annotation.TargetApi(33)
class BuiltinAdbPairingDeviceTest {
    @Test fun pairUsingTheRealNotificationAction() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pairAdb") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 34) assertEquals("The packaged pairing service needs its dataSync permission",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            target.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC))
        val previous = ScreenControl.state.value.mode
        assertFalse(ScreenControl.state.value.active)
        ScreenControl.select(target, ScreenMode.ADB)
        instrumentation.runOnMainSync {
            target.startActivity(Intent(target, ScreenControlSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val nonce = UUID.randomUUID().toString()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 90_000
            try {
                val previousSuccess = ScreenControl.state.value.adbPairingSuccess
                ScreenControl.startAdbPairing(target)
                instrumentation.runOnMainSync {
                    target.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("pair_test_endpoint", JSONObject().put("port", server.localPort).put("nonce", nonce).toString())
                })
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = DataInputStream(socket.getInputStream())
                    val length = input.readInt()
                    check(length in 1..1024) { "invalid_test_frame" }
                    val bytes = ByteArray(length); input.readFully(bytes)
                    val body = JSONObject(String(bytes, Charsets.UTF_8))
                    check(MessageDigest.isEqual(nonce.toByteArray(), body.getString("nonce").toByteArray())) { "invalid_test_authentication" }
                    val code = body.getString("code")
                    check(code.matches(Regex("[0-9]{6}"))) { "invalid_system_code_shape" }
                    // An explicit port exercises the same user-provided hint on devices without mDNS.
                    body.optString("connectionPort").takeIf { it.isNotBlank() }?.let { ScreenControl.setAdbPort(target, it) }
                    val notifications = target.getSystemService(NotificationManager::class.java)
                    var action: android.app.Notification.Action? = null
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    while (action == null && SystemClock.uptimeMillis() < deadline) {
                        action = notifications.activeNotifications.firstOrNull { it.id == 4602 }?.notification?.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
                        if (action == null) SystemClock.sleep(100)
                    }
                    val reply = checkNotNull(action) { "pairing_notification_action_missing" }
                    val results = Bundle().apply { putCharSequence(reply.remoteInputs.single().resultKey, code) }
                    val fill = Intent()
                    RemoteInput.addResultsToIntent(reply.remoteInputs, fill, results)
                    reply.actionIntent.send(target, 0, fill)
                }
                val deadline = SystemClock.uptimeMillis() + 60_000
                while (ScreenControl.state.value.adbPairing && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                assertFalse("Pairing must finish within its deadline", ScreenControl.state.value.adbPairing)
                assertNull("System pairing failed", ScreenControl.state.value.error)
                assertTrue("System must accept this app's pairing identity", ScreenControl.state.value.adbPaired)
                assertTrue("This notification must complete a new pairing", ScreenControl.state.value.adbPairingSuccess > previousSuccess)
                assertTrue("The backend must connect without reopening the wizard", ScreenControl.state.value.connected)
            } finally {
                ScreenControl.cancelAdbPairing(target)
                val deadline = SystemClock.uptimeMillis() + 2000
                while (ScreenControl.state.value.adbPairing && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                if (!ScreenControl.state.value.adbPairing) {
                    ScreenControl.setAdbPort(target, "")
                    ScreenControl.select(target, previous)
                }
            }
        }
    }
}
