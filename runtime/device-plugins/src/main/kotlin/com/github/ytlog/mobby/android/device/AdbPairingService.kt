package com.github.ytlog.mobby.android.device

import android.app.*
import android.content.Intent
import android.os.IBinder
import com.github.ytlog.mobby.android.localization.AppStrings
import kotlinx.coroutines.*

/** Ordinary app UID. A short-lived notification keeps the system pairing dialog in front. */
@android.annotation.TargetApi(33)
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var port: Int? = null
    private var submitted = false
    private var discovery: Job? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == SUBMIT) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString().orEmpty()
            port?.let { submit(it, code) }
        } else if (discovery == null) {
            notifications.cancel(ID + 1)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, AppStrings.adbPairingNotification, NotificationManager.IMPORTANCE_DEFAULT))
            startForeground(ID, notice(AppStrings.adbPairingSearching))
            active = this
            discovery = scope.launch {
                try {
                    withTimeout(120_000) {
                        port = EmbeddedAdb.discover(this@AdbPairingService, pairing = true)
                        notifications.notify(ID, notice(AppStrings.adbPairingReplyHelp, reply = true))
                        awaitCancellation()
                    }
                } catch (timeout: TimeoutCancellationException) { finishPairing(AppStrings.adbPairingTimedOut) }
                catch (_: java.util.concurrent.TimeoutException) { finishPairing(AppStrings.adbPairingTimedOut) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { finishPairing(AppStrings.adbPairingFailed) }
            }
        }
        return START_NOT_STICKY
    }

    private fun submit(port: Int, code: String, connect: Boolean = false, connectionPort: Int? = null): Boolean {
        if (submitted || !ScreenControl.state.value.adbPairing) return false
        submitted = true
        discovery?.cancel()
        notifications.notify(ID, notice(AppStrings.adbPairingInProgress))
        scope.launch {
            try { ScreenControl.pairAdb(this@AdbPairingService, port.toString(), code) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finishPairing(AppStrings.adbPairingFailed); return@launch }
            if (connect) {
                try {
                    ScreenControl.connectAfterPairing(this@AdbPairingService, ScreenControl.state.value.adbPairingSuccess, connectionPort)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { finishPairing(AppStrings.adbConnectionFailed); return@launch }
            }
            finishPairing(null)
        }
        return true
    }

    private fun notice(message: String, reply: Boolean = false): Notification {
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(AppStrings.adbPairingNotification).setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message)).setOngoing(true).setOnlyAlertOnce(true)
        if (reply) {
            val pending = PendingIntent.getService(this, 0, Intent(this, AdbPairingService::class.java).setAction(SUBMIT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val input = RemoteInput.Builder(CODE).setLabel(AppStrings.adbPairingCode).build()
            builder.addAction(Notification.Action.Builder(null, AppStrings.adbPairingCode, pending).addRemoteInput(input).build())
        }
        return builder.build()
    }

    private fun finishPairing(error: String?) {
        ScreenControl.pairingFinished(error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.notify(ID + 1, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(AppStrings.adbPairingNotification).setContentText(error ?: AppStrings.adbPaired)
            .setStyle(Notification.BigTextStyle().bigText(error ?: AppStrings.adbPaired)).setAutoCancel(true).build())
        stopSelf()
    }

    override fun onDestroy() {
        if (active === this) active = null
        scope.cancel(); ScreenControl.pairingFinished(ScreenControl.state.value.error); super.onDestroy()
    }
    companion object {
        private var active: AdbPairingService? = null

        /** Called on the main thread by the user-started Settings assistant. */
        internal fun submitFromSettings(port: Int, code: String, connectionPort: Int?): Boolean =
            active?.submit(port, code, connect = true, connectionPort = connectionPort) == true

        private const val CHANNEL = "screen-adb-pairing"
        private const val ID = 4602
        internal const val SUBMIT = "com.github.ytlog.mobby.android.screen.PAIR_ADB"
        private const val CODE = "code"
    }
}
