package com.wakeping

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BleReceiverService : Service() {

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val authorizedPairingDevices = mutableSetOf<String>()

    @Volatile
    private var isAdvertising = false
    private var isReceiverRegistered = false
    private var advertiseRetryCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRestartRunnable: Runnable? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val previousState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR)
                Log.i(TAG, "Bluetooth state changed: previousState=$previousState, state=$state")

                when (state) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.i(TAG, "Bluetooth adapter turned/turning OFF. Cleaning up BLE server and advertising...")
                        cancelPendingRestart()
                        advertiseRetryCount = 0
                        stopBleServer()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth adapter turned ON. Scheduling BLE server recreation and advertising restart...")
                        cancelPendingRestart()
                        advertiseRetryCount = 0
                        // Short delay allows Android's Bluetooth stack and HAL to settle after turning on
                        scheduleRestart(ADAPTER_RESTART_DELAY_MS)
                    }
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            advertiseRetryCount = 0
            Log.i(TAG, "BLE advertising started successfully in low-power mode (Service UUID: ${BleConstants.SERVICE_UUID}). Recovery complete.")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val errorDesc = describeAdvertiseError(errorCode)
            Log.e(TAG, "BLE advertising failed with error code $errorCode ($errorDesc)")
            handleAdvertiseFailure(errorCode)
        }
    }

    private fun handleAdvertiseFailure(errorCode: Int) {
        if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
            isAdvertising = true
            advertiseRetryCount = 0
            Log.i(TAG, "BLE advertising is already running on the controller.")
            return
        }

        if (advertiseRetryCount < MAX_ADVERTISE_RETRIES) {
            advertiseRetryCount++
            val delayMs = advertiseRetryCount * 800L
            Log.w(TAG, "Retrying BLE advertising in ${delayMs}ms (attempt $advertiseRetryCount of $MAX_ADVERTISE_RETRIES)...")
            scheduleRestart(delayMs)
        } else {
            Log.e(TAG, "Max advertising retries ($MAX_ADVERTISE_RETRIES) reached. Halting restart loop to prevent battery drain.")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "GATT server onConnectionStateChange: device=${device.address}, status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                authorizedPairingDevices.remove(device.address)
                Log.i(TAG, "Device ${device.address} disconnected. Reset temporary pairing state.")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val writtenValue = value?.let { String(it, Charsets.UTF_8).trim() } ?: ""
            Log.i(TAG, "GATT write request on ${characteristic.uuid} from ${device.address}")

            when (characteristic.uuid) {
                BleConstants.WAKE_CHAR_UUID -> {
                    val expectedToken = DevicePreferences.getBleAuthToken(this@BleReceiverService)
                    if (writtenValue == expectedToken) {
                        Log.i(TAG, "Authenticated BLE wake command ACCEPTED from ${device.address}!")
                        WakeTrigger.triggerWake(this@BleReceiverService)

                        if (responseNeeded) {
                            sendResponseSafe(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                        }
                    } else {
                        Log.w(TAG, "Unauthenticated BLE wake command REJECTED from ${device.address}. Expected len=${expectedToken.length}, received len=${writtenValue.length}")
                        if (responseNeeded) {
                            sendResponseSafe(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                        }
                    }
                }
                BleConstants.PAIR_CHAR_UUID -> {
                    val activePin = DevicePreferences.getActivePin(this@BleReceiverService)
                    if (!activePin.isNullOrBlank() && writtenValue == activePin) {
                        Log.i(TAG, "Pairing PIN VERIFIED for device ${device.address}. Device authorized to read auth token.")
                        authorizedPairingDevices.add(device.address)
                        if (responseNeeded) {
                            sendResponseSafe(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                        }
                    } else {
                        Log.w(TAG, "Invalid pairing PIN from ${device.address}. Expected=$activePin, received=$writtenValue")
                        if (responseNeeded) {
                            sendResponseSafe(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                        }
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown characteristic write request: ${characteristic.uuid}")
                    if (responseNeeded) {
                        sendResponseSafe(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.i(TAG, "GATT read request on ${characteristic.uuid} from ${device.address}, offset=$offset")
            if (characteristic.uuid == BleConstants.PAIR_CHAR_UUID) {
                if (authorizedPairingDevices.contains(device.address)) {
                    val fullToken = DevicePreferences.getBleAuthToken(this@BleReceiverService).toByteArray(Charsets.UTF_8)
                    val slice = if (offset > 0) {
                        if (offset < fullToken.size) {
                            fullToken.copyOfRange(offset, fullToken.size)
                        } else {
                            ByteArray(0)
                        }
                    } else {
                        fullToken
                    }
                    sendResponseSafe(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                    Log.i(TAG, "Dispatched auth token (offset=$offset, bytes=${slice.size}) to ${device.address}")
                    // Keep device in authorizedPairingDevices until disconnected, so multi-part blob reads succeed
                } else {
                    Log.w(TAG, "Unauthorized read request on PAIR_CHAR_UUID from ${device.address}")
                    sendResponseSafe(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            } else {
                sendResponseSafe(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createServiceNotificationChannel()
        startForeground(BleConstants.NOTIFICATION_ID, buildForegroundNotification())
        registerBluetoothStateReceiver()
        initializeBleServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isAdvertising && (gattServer == null || advertiser == null)) {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter != null && adapter.isEnabled) {
                Log.i(TAG, "onStartCommand: Server not active but Bluetooth is enabled. Initializing...")
                initializeBleServer()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelPendingRestart()
        unregisterBluetoothStateReceiver()
        stopBleServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerBluetoothStateReceiver() {
        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                registerReceiver(bluetoothStateReceiver, filter)
                isReceiverRegistered = true
                Log.i(TAG, "Bluetooth state BroadcastReceiver registered successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register Bluetooth state receiver: ${e.message}")
            }
        }
    }

    private fun unregisterBluetoothStateReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothStateReceiver)
                Log.i(TAG, "Bluetooth state BroadcastReceiver unregistered.")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering Bluetooth state receiver: ${e.message}")
            } finally {
                isReceiverRegistered = false
            }
        }
    }

    private fun cancelPendingRestart() {
        pendingRestartRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingRestartRunnable = null
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        cancelPendingRestart()
        pendingRestartRunnable = Runnable {
            pendingRestartRunnable = null
            Log.i(TAG, "Executing scheduled BLE server restart...")
            restartBleServer()
        }
        mainHandler.postDelayed(pendingRestartRunnable!!, delayMs)
    }

    private fun restartBleServer() {
        stopBleServer()
        initializeBleServer()
    }

    private fun initializeBleServer() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth is not supported on this device.")
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Log.w(TAG, "Bluetooth is currently disabled. Waiting for Bluetooth to be turned ON...")
            return
        }

        val adv = bluetoothAdapter?.bluetoothLeAdvertiser
        val isMultiAdvSupported = bluetoothAdapter?.isMultipleAdvertisementSupported == true

        if (adv == null && !isMultiAdvSupported) {
            Log.e(TAG, "Device Bluetooth hardware does not support BLE advertising.")
            return
        }

        Log.i(TAG, "Initializing BLE GATT server and advertising...")
        startGattServer()
        startAdvertising()
    }

    private fun startGattServer() {
        if (!hasConnectPermission()) {
            Log.e(TAG, "Cannot start GATT server: missing BLUETOOTH_CONNECT permission.")
            return
        }

        // Avoid duplicate GATT servers
        if (gattServer != null) {
            Log.w(TAG, "Closing existing GATT server before recreating...")
            try {
                gattServer?.close()
            } catch (_: Exception) {}
            gattServer = null
        }

        try {
            gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
            if (gattServer == null) {
                Log.e(TAG, "Unable to create GATT server (openGattServer returned null).")
                return
            }

            val service = BluetoothGattService(
                BleConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // Wake Characteristic (Write with response or no response)
            val wakeChar = BluetoothGattCharacteristic(
                BleConstants.WAKE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            // Pairing Characteristic (Write PIN, Read persistent auth token)
            val pairChar = BluetoothGattCharacteristic(
                BleConstants.PAIR_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )

            service.addCharacteristic(wakeChar)
            service.addCharacteristic(pairChar)
            val added = gattServer?.addService(service) == true
            Log.i(TAG, "GATT server created with WakePing service (${BleConstants.SERVICE_UUID}). addService result: $added")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied opening GATT server: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error opening GATT server: ${e.message}")
        }
    }

    private fun startAdvertising() {
        if (isAdvertising) {
            Log.i(TAG, "BLE advertising is already running.")
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser not available.")
            if (advertiseRetryCount < MAX_ADVERTISE_RETRIES) {
                advertiseRetryCount++
                val delayMs = advertiseRetryCount * 500L
                Log.w(TAG, "BluetoothLeAdvertiser not ready yet, retrying in ${delayMs}ms (attempt $advertiseRetryCount of $MAX_ADVERTISE_RETRIES)...")
                scheduleRestart(delayMs)
            }
            return
        }

        if (!hasAdvertisePermission()) {
            Log.e(TAG, "Cannot start advertising: missing BLUETOOTH_ADVERTISE permission.")
            return
        }

        // Lowest-power advertising settings: ~1.28s intervals, ultra-low TX power
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        try {
            Log.i(TAG, "Starting BLE advertising with Service UUID: ${BleConstants.SERVICE_UUID}")
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied starting BLE advertising: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting BLE advertising: ${e.message}")
            handleAdvertiseFailure(-1)
        }
    }

    private fun stopBleServer() {
        cancelPendingRestart()

        if (advertiser != null && isAdvertising) {
            if (hasAdvertisePermission()) {
                try {
                    advertiser?.stopAdvertising(advertiseCallback)
                    Log.i(TAG, "BLE advertising stopped.")
                } catch (e: Exception) {
                    Log.w(TAG, "Exception stopping BLE advertising: ${e.message}")
                }
            }
        }
        isAdvertising = false
        advertiser = null

        if (gattServer != null) {
            if (hasConnectPermission()) {
                try {
                    gattServer?.clearServices()
                } catch (_: Exception) {}
                try {
                    gattServer?.close()
                    Log.i(TAG, "GATT server closed cleanly.")
                } catch (e: Exception) {
                    Log.w(TAG, "Exception closing GATT server: ${e.message}")
                }
            }
            gattServer = null
        }

        authorizedPairingDevices.clear()
    }

    private fun sendResponseSafe(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) {
        if (hasConnectPermission()) {
            try {
                gattServer?.sendResponse(device, requestId, status, offset, value)
            } catch (e: SecurityException) {
                Log.e(TAG, "Error sending GATT response: ${e.message}")
            }
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BleConstants.NOTIFICATION_CHANNEL_ID,
                "Bluetooth Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps WakePing listening for Bluetooth wake alerts"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, BleConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("WakePing Bluetooth")
            .setContentText("Listening for offline wake alerts")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun describeAdvertiseError(errorCode: Int): String {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            else -> "UNKNOWN_ERROR_$errorCode"
        }
    }

    companion object {
        private const val TAG = "BleReceiverService"
        private const val ADAPTER_RESTART_DELAY_MS = 400L
        private const val MAX_ADVERTISE_RETRIES = 3

        fun start(context: Context) {
            val intent = Intent(context, BleReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleReceiverService::class.java))
        }
    }
}
