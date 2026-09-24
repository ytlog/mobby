package com.github.ytlog.mobby.android.device

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import com.github.ytlog.mobby.android.deviceinteraction.model.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray

/** API submission and radio sent receipts are different facts. Delivery is not inferred. */
internal class SmsSendOperation(private val context: Context, private val gate: DeviceActionGate) {
    fun send(to: String, body: String): DeviceResult {
        val manager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = manager.divideMessage(body)
        val receipt = UUID.randomUUID().toString()
        val action = "${context.packageName}.SMS_SENT.$receipt"
        val completed = CountDownLatch(parts.size)
        val codes = AtomicIntegerArray(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val index = intent?.data?.lastPathSegment?.toIntOrNull() ?: return
                if (index !in parts.indices || !codes.compareAndSet(index, 0, if (resultCode == Activity.RESULT_OK) 1 else -1)) return
                completed.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(action))
        val intents = ArrayList(parts.indices.map { index -> PendingIntent.getBroadcast(context, 0,
            Intent(action).setPackage(context.packageName).setData(android.net.Uri.parse("mobby-sms://$receipt/$index")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT) })
        try {
            gate.checkActive()
            manager.sendMultipartTextMessage(to, null, parts, intents, null)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (completed.count > 0 && System.nanoTime() < deadline) {
                completed.await(100, TimeUnit.MILLISECONDS)
                try { gate.checkActive() } catch (_: java.util.concurrent.CancellationException) { break }
            }
            val sent = parts.indices.count { codes.get(it) == 1 }
            val failed = parts.indices.count { codes.get(it) == -1 }
            val state = when { sent == parts.size -> "succeeded"; failed > 0 -> "failed"; else -> "unknown" }
            val effect = when { sent == parts.size -> EffectState.CONFIRMED; sent > 0 -> EffectState.PARTIAL; failed == parts.size -> EffectState.NONE; else -> EffectState.SUBMITTED }
            return deviceResult("message_receipt", fields("transport" to "sms", "submitted" to true, "sent" to state,
                "delivered" to "unknown", "receiptId" to receipt), effect)
        } finally {
            context.unregisterReceiver(receiver)
            intents.forEach { it.cancel() }
        }
    }
}
