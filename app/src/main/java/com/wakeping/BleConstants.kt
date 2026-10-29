package com.wakeping

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("00005047-0000-1000-8000-00805F9B34FB")
    val WAKE_CHAR_UUID: UUID = UUID.fromString("00005048-0000-1000-8000-00805F9B34FB")
    val PAIR_CHAR_UUID: UUID = UUID.fromString("00005049-0000-1000-8000-00805F9B34FB")

    const val NOTIFICATION_CHANNEL_ID = "ble_receiver_channel"
    const val NOTIFICATION_ID = 2001
}
