package com.wakeping

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class WakePingMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        WakeTrigger.triggerWake(this)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (DevicePreferences.getRole(this) == DevicePreferences.ROLE_RECEIVER) {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser != null) {
                com.google.firebase.functions.FirebaseFunctions.getInstance()
                    .getHttpsCallable("registerReceiver")
                    .call(mapOf("fcmToken" to token))
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "wake_ping_channel"
        const val CHANNEL_NAME = "Wake Alerts"
        const val NOTIFICATION_ID = 1001

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Channel for WakePing alerts"
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.createNotificationChannel(channel)
            }
        }
    }
}
