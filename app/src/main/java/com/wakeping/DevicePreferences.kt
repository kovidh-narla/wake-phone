package com.wakeping

import android.content.Context
import java.util.UUID

object DevicePreferences {
    private const val PREFS_NAME = "wakeping_prefs"
    private const val KEY_ROLE = "device_role"
    private const val KEY_IS_PAIRED = "is_paired"
    private const val KEY_BLE_AUTH_TOKEN = "ble_auth_token"
    private const val KEY_BLE_PHONE_MAC = "ble_phone_mac"
    private const val KEY_ACTIVE_PIN = "active_pairing_pin"

    const val ROLE_RECEIVER = "receiver"
    const val ROLE_SENDER = "sender"

    fun getRole(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, null)
    }

    fun isPaired(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_PAIRED, false)
    }

    fun setPairing(context: Context, role: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROLE, role)
            .putBoolean(KEY_IS_PAIRED, true)
            .apply()
    }

    // BLE persistent authentication secret (16-char hex token, fits cleanly in single ATT MTU)
    fun getBleAuthToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_BLE_AUTH_TOKEN, null)
        if (token == null) {
            token = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            prefs.edit().putString(KEY_BLE_AUTH_TOKEN, token).apply()
        }
        return token
    }

    fun setBleAuthToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLE_AUTH_TOKEN, token)
            .apply()
    }

    // Cached Phone BLE MAC address on Tablet for instant connection
    fun getPhoneMacAddress(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BLE_PHONE_MAC, null)
    }

    fun setPhoneMacAddress(context: Context, mac: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLE_PHONE_MAC, mac)
            .apply()
    }

    // Temporary active 6-digit pairing PIN shown on the phone screen
    fun getActivePin(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PIN, null)
    }

    fun setActivePin(context: Context, pin: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PIN, pin)
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
