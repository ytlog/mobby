package com.github.ytlog.mobby.android.device

import android.app.*
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import com.github.ytlog.mobby.android.localization.AppStrings
import kotlinx.coroutines.*

/** Ordinary app UID. A short-lived notification keeps the system pairing dialog in front. */
@android.annotation.TargetApi(33)
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var port: Int? = null
    private var connectionPort: Int? = null
    private var returnTo: ComponentName? = null
    private var submitted = false
    private var finished = false
    private var discovery: Job? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == SUBMIT) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString().orEmpty()
            port?.let { submit(it, code) }
        } else if (discovery == null) {
            returnTo = intent?.getStringExtra(RETURN_TO_ACTIVITY)?.let { ComponentName(packageName, it) }
            notifications.cancel(ID + 1)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, AppStrings.adbPairingNotification, NotificationManager.IMPORTANCE_DEFAULT))
            startForeground(ID, notice(AppStrings.adbPairingSearching))
            active = this
            discovery = scope.launch {
                try {
                    withTimeout(120_000) {
                        val discovered = EmbeddedAdb.discoverPairingPort(this@AdbPairingService)
                        if (port == null) offerReply(discovered)
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

    private fun offerReply(port: Int) {
        if (submitted || !ScreenControl.state.value.adbPairing || this.port == port) return
        this.port = port
        notifications.notify(ID, notice(AppStrings.adbPairingReplyHelp, reply = true))
    }

    private fun submit(port: Int, code: String): Boolean {
        if (submitted || !ScreenControl.state.value.adbPairing) return false
        submitted = true
        discovery?.cancel()
        val portHint = (connectionPort ?: EmbeddedAdb.port)?.takeUnless { it == port }
        notifications.notify(ID, notice(AppStrings.adbPairingInProgress))
        scope.launch {
            try { ScreenControl.pairAdb(this@AdbPairingService, port.toString(), code) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finishPairing(AppStrings.adbPairingFailed); return@launch }
            notifications.notify(ID, notice(AppStrings.adbConnectingAfterPairing))
            val connectionError = try {
                ScreenControl.connectAfterPairing(this@AdbPairingService, ScreenControl.state.value.adbPairingSuccess, portHint)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppStrings.adbConnectionFailed
            }
            finishPairing(connectionError, paired = true)
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

    private fun finishPairing(error: String?, paired: Boolean = false) {
        if (active !== this) return
        finished = true
        active = null
        ScreenControl.pairingFinished(error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        val message = error ?: AppStrings.screenBackendReady
        val result = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(AppStrings.adbPairingNotification).setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true)
        returnTo?.let { component ->
            result.setContentIntent(PendingIntent.getActivity(this, 0, wizardIntent(component), PendingIntent.FLAG_IMMUTABLE))
            if (paired) ScreenAccessService.returnFromAdbPairing(component)
        }
        notifications.notify(ID + 1, result.build())
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (active === this) active = null
        if (!finished) ScreenControl.pairingFinished(ScreenControl.state.value.error)
        super.onDestroy()
    }
    companion object {
        private var active: AdbPairingService? = null

        /** Called on the main thread by the user-started Settings assistant. */
        internal fun submitFromSettings(port: Int, code: String): Boolean = active?.submit(port, code) == true

        internal fun offerSettingsPort(port: Int) { active?.offerReply(port) }
        internal fun offerConnectionPort(port: Int) { active?.takeUnless { it.submitted }?.connectionPort = port }

        internal fun wizardIntent(component: ComponentName) = Intent().setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private const val CHANNEL = "screen-adb-pairing"
        private const val ID = 4602
        internal const val SUBMIT = "com.github.ytlog.mobby.android.screen.PAIR_ADB"
        internal const val RETURN_TO_ACTIVITY = "return_to_activity"
        private const val CODE = "code"
    }
}
