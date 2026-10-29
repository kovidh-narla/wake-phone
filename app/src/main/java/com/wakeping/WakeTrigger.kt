package com.wakeping

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object WakeTrigger {

    fun triggerWake(context: Context) {
        // 1. Ensure notification channel exists
        WakePingMessagingService.createNotificationChannel(context)

        // 2. Temporarily acquire a CPU wake lock (bounded timeout 5s) to guarantee notification dispatch
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "wakeping:ble_cpu_wake"
        )
        wakeLock?.acquire(5000)

        // 3. Build full-screen intent targeting MainActivity (wakes screen when locked on Android 8+)
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Construct and dispatch high-priority notification with full-screen intent
        val notification = NotificationCompat.Builder(context, WakePingMessagingService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("WakePing")
            .setContentText("Your phone is being woken up.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(WakePingMessagingService.NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission missing
        }
    }
}
