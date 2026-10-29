package com.wakeping

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val role = DevicePreferences.getRole(context)
            val isPaired = DevicePreferences.isPaired(context)

            if (role == DevicePreferences.ROLE_RECEIVER && isPaired) {
                Log.d(TAG, "Device rebooted. Starting BleReceiverService...")
                try {
                    BleReceiverService.start(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start BleReceiverService on boot: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
