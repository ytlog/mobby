package com.github.ytlog.mobby.android.programs.base.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.programs.base.storage.invalid
import com.github.ytlog.mobby.android.programs.base.storage.schema
import com.github.ytlog.mobby.android.programs.base.storage.stringType
import com.github.ytlog.mobby.android.programs.base.storage.text
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger

/** `notify.show`: a notification under mobby's program channel, always naming the program. */
class NotifyCapability(
    private val context: Context,
    private val dao: ProgramsDao,
    /** Opens the program UI; null when the program has none or the host cannot open it. */
    private val openUi: (programId: String) -> PendingIntent?,
) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor("notify.show", 1, CapabilityKind.COMMAND,
        schema(listOf("title"), "title" to stringType(100), "body" to stringType(1_000), "openUi" to buildJsonObject { put("type", "boolean") }),
        buildJsonObject { put("type", "object") }, ScopeKind.NONE, setOf(SideEffect.SEND), listOf(Prerequisite("android-permission", "android.permission.POST_NOTIFICATIONS")),
        setOf(Surface.BACKGROUND, Surface.UI), 5_000, false, CapabilityLimits(4 * 1024, 10))

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val ids = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())

    override fun availability(): Availability {
        val permitted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val enabled = manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        return if (permitted && enabled) Availability.Available else Availability.Unavailable(AvailabilityReason.SYSTEM_PERMISSION_MISSING)
    }

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome {
        val title = call.args.text("title") ?: invalid("title_required")
        val program = dao.program(call.context.programId)?.name ?: call.context.programId
        manager.createNotificationChannel(NotificationChannel(CHANNEL, AppStrings.programNotifications, NotificationManager.IMPORTANCE_DEFAULT))
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("$program · $title").setAutoCancel(true)
        call.args.text("body")?.let { builder.setContentText(it).setStyle(Notification.BigTextStyle().bigText(it)) }
        if ((call.args["openUi"] as? JsonPrimitive)?.booleanOrNull == true) {
            builder.setContentIntent(openUi(call.context.programId) ?: invalid("program_has_no_ui"))
        }
        manager.notify(ids.incrementAndGet(), builder.build())
        return CapabilityOutcome(Effect.SUBMITTED, JsonNull)
    }

    companion object { const val CHANNEL = "programs" }
}
