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
            val selected = port
            if (selected != null && !submitted) {
                submitted = true
                notifications.notify(ID, notice(AppStrings.adbPairingInProgress))
                scope.launch {
                    try { ScreenControl.pairAdb(this@AdbPairingService, selected.toString(), code); finishPairing(null) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { finishPairing(AppStrings.adbPairingFailed) }
                }
            }
        } else if (discovery == null) {
            notifications.cancel(ID + 1)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, AppStrings.adbPairingNotification, NotificationManager.IMPORTANCE_DEFAULT))
            startForeground(ID, notice(AppStrings.adbPairingSearching))
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
        scope.cancel(); ScreenControl.pairingFinished(ScreenControl.state.value.error); super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "screen-adb-pairing"
        private const val ID = 4602
        internal const val SUBMIT = "com.github.ytlog.mobby.android.screen.PAIR_ADB"
        private const val CODE = "code"
    }
}
