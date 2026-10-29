package com.wakeping

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat

class BleWakeSender(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeScanCallback: ScanCallback? = null
    @Volatile
    private var activeGatt: BluetoothGatt? = null

    sealed class WakeResult {
        object Success : WakeResult()
        data class Failure(val reason: String) : WakeResult()
    }

    @Suppress("DEPRECATION")
    fun sendWakeCommand(onResult: (WakeResult) -> Unit) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "sendWakeCommand: Bluetooth is disabled or unavailable")
            onResult(WakeResult.Failure("Bluetooth is turned off"))
            return
        }

        val permCheck = checkClientPermissions()
        if (permCheck != null) {
            Log.e(TAG, "sendWakeCommand: Permission check failed: $permCheck")
            onResult(WakeResult.Failure(permCheck))
            return
        }

        val authToken = DevicePreferences.getBleAuthToken(context)
        val cachedMac = DevicePreferences.getPhoneMacAddress(context)

        Log.i(TAG, "sendWakeCommand: Initiating. cachedMac=$cachedMac, tokenLen=${authToken.length}")

        val stateLock = Any()
        var completed = false
        var isConnected = false
        var hasRetriedScan = false
        var directConnectRunnable: Runnable? = null
        var totalTimeoutRunnable: Runnable? = null

        stopActiveScan()
        closeActiveGatt()

        fun cancelDirectConnectTimeout() {
            directConnectRunnable?.let {
                mainHandler.removeCallbacks(it)
                directConnectRunnable = null
                Log.i(TAG, "Direct connect fallback timeout cancelled.")
            }
        }

        fun finish(result: WakeResult) {
            synchronized(stateLock) {
                if (!completed) {
                    completed = true
                    cancelDirectConnectTimeout()
                    totalTimeoutRunnable?.let {
                        mainHandler.removeCallbacks(it)
                        totalTimeoutRunnable = null
                    }
                    stopActiveScan()
                    closeActiveGatt()
                    Log.i(TAG, "sendWakeCommand finished with result: $result")
                    mainHandler.post { onResult(result) }
                }
            }
        }

        // Bounded overall operation timeout
        totalTimeoutRunnable = Runnable {
            Log.w(TAG, "Overall operation timeout reached ($TOTAL_OPERATION_TIMEOUT_MS ms)")
            finish(WakeResult.Failure("Connection timed out (phone not found nearby)"))
        }
        mainHandler.postDelayed(totalTimeoutRunnable!!, TOTAL_OPERATION_TIMEOUT_MS)

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                synchronized(stateLock) {
                    if (gatt !== activeGatt) {
                        Log.w(TAG, "Ignoring onConnectionStateChange for stale/unowned GATT (${gatt.device.address}, status=$status, newState=$newState)")
                        try {
                            gatt.disconnect()
                            gatt.close()
                        } catch (_: Exception) {}
                        return
                    }

                    Log.i(TAG, "Wake GATT state change: device=${gatt.device.address}, status=$status, newState=$newState (CONNECTED=${BluetoothProfile.STATE_CONNECTED})")
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        isConnected = true
                        // CRITICAL: Immediately cancel direct connect fallback so we never close an established connection!
                        cancelDirectConnectTimeout()
                        Log.i(TAG, "Connected to receiver GATT! Initiating service discovery...")
                        try {
                            val discoveryStarted = gatt.discoverServices()
                            Log.i(TAG, "GATT discoverServices initiated: $discoveryStarted")
                        } catch (e: SecurityException) {
                            finish(WakeResult.Failure("Security error discovering services: ${e.message}"))
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        isConnected = false
                        if (!completed) {
                            Log.w(TAG, "GATT disconnected before command completion. Status=$status")
                            finish(WakeResult.Failure("Disconnected before wake command was accepted (status $status)"))
                        }
                    }
                    Unit
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                synchronized(stateLock) {
                    if (gatt !== activeGatt) {
                        Log.w(TAG, "Ignoring onServicesDiscovered for stale GATT (${gatt.device.address})")
                        return
                    }

                    Log.i(TAG, "Wake GATT services discovered: status=$status (SUCCESS=${BluetoothGatt.GATT_SUCCESS})")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val service = gatt.getService(BleConstants.SERVICE_UUID)
                        if (service == null) {
                            Log.e(TAG, "WakePing service ${BleConstants.SERVICE_UUID} not found in GATT database")
                            finish(WakeResult.Failure("WakePing service not found on receiver"))
                            return
                        }

                        val wakeChar = service.getCharacteristic(BleConstants.WAKE_CHAR_UUID)
                        if (wakeChar == null) {
                            Log.e(TAG, "Wake characteristic ${BleConstants.WAKE_CHAR_UUID} not found")
                            finish(WakeResult.Failure("Wake characteristic not found on receiver"))
                            return
                        }

                        try {
                            val tokenBytes = authToken.toByteArray(Charsets.UTF_8)
                            wakeChar.value = tokenBytes
                            val properties = wakeChar.properties
                            val writeType = if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            } else {
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            }
                            wakeChar.writeType = writeType

                            Log.i(TAG, "Writing 16-byte wake token to ${wakeChar.uuid} (writeType=$writeType, bytes=${tokenBytes.size})")
                            val initiated = gatt.writeCharacteristic(wakeChar)
                            Log.i(TAG, "Wake characteristic write initiated: $initiated")
                            if (!initiated) {
                                finish(WakeResult.Failure("Failed to initiate wake characteristic write"))
                            }
                        } catch (e: SecurityException) {
                            finish(WakeResult.Failure("Permission denied writing wake command: ${e.message}"))
                        }
                    } else {
                        finish(WakeResult.Failure("Service discovery failed with status $status"))
                    }
                    Unit
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                synchronized(stateLock) {
                    if (gatt !== activeGatt) {
                        Log.w(TAG, "Ignoring onCharacteristicWrite for stale GATT (${gatt.device.address})")
                        return
                    }

                    Log.i(TAG, "Wake onCharacteristicWrite: char=${characteristic.uuid}, status=$status (SUCCESS=${BluetoothGatt.GATT_SUCCESS})")
                    if (characteristic.uuid == BleConstants.WAKE_CHAR_UUID) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.i(TAG, "Receiver confirmed wake command accepted! Status=$status")
                            DevicePreferences.setPhoneMacAddress(context, gatt.device.address)
                            finish(WakeResult.Success)
                        } else {
                            Log.e(TAG, "Receiver rejected wake command with status $status")
                            finish(WakeResult.Failure("Receiver rejected wake command (authorization mismatch, status $status)"))
                        }
                    }
                    Unit
                }
            }
        }

        fun connectToDevice(device: BluetoothDevice) {
            synchronized(stateLock) {
                if (completed) return
                stopActiveScan()
                Log.i(TAG, "Initiating GATT connection to: ${device.address}")
                try {
                    activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(context, false, gattCallback)
                    }
                } catch (e: SecurityException) {
                    finish(WakeResult.Failure("Permission denied connecting: ${e.message}"))
                }
            }
        }

        fun startBleScan() {
            synchronized(stateLock) {
                if (completed || isConnected) return
                val currentAdapter = bluetoothAdapter
                val scanner = currentAdapter?.bluetoothLeScanner

                if (currentAdapter == null || !currentAdapter.isEnabled || scanner == null) {
                    finish(WakeResult.Failure("Bluetooth LE scanner not available"))
                    return
                }

                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                    .build()

                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

                val scanCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult?) {
                        val device = result?.device ?: return
                        synchronized(stateLock) {
                            if (completed || isConnected) return
                            Log.i(TAG, "BLE scan found WakePing receiver: ${device.address}, RSSI=${result.rssi}")
                            stopActiveScan()
                            connectToDevice(device)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        val errorDesc = describeScanError(errorCode)
                        Log.e(TAG, "BLE scan failed: code=$errorCode ($errorDesc)")

                        if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED && !hasRetriedScan && !completed) {
                            hasRetriedScan = true
                            Log.w(TAG, "Handling error 2: Cleaning up scanner registration and retrying in 350ms...")
                            stopActiveScan()
                            mainHandler.postDelayed({
                                synchronized(stateLock) {
                                    if (!completed && !isConnected) {
                                        startBleScan()
                                    }
                                }
                            }, 350)
                            return
                        }

                        finish(WakeResult.Failure("BLE scan failed: $errorDesc (code $errorCode)"))
                    }
                }

                stopActiveScan()
                activeScanCallback = scanCallback

                try {
                    Log.i(TAG, "Starting targeted BLE scan for service UUID ${BleConstants.SERVICE_UUID}...")
                    scanner.startScan(listOf(filter), settings, scanCallback)
                } catch (e: SecurityException) {
                    activeScanCallback = null
                    finish(WakeResult.Failure("Permission denied starting BLE scan: ${e.message}"))
                } catch (e: IllegalStateException) {
                    activeScanCallback = null
                    finish(WakeResult.Failure("Bluetooth adapter state error: ${e.message}"))
                }
            }
        }

        // Direct connect if cached MAC exists; otherwise scan immediately
        if (!cachedMac.isNullOrBlank() && BluetoothAdapter.checkBluetoothAddress(cachedMac)) {
            Log.i(TAG, "Attempting direct GATT connection to cached MAC: $cachedMac")
            try {
                val remoteDevice = adapter.getRemoteDevice(cachedMac)
                connectToDevice(remoteDevice)

                directConnectRunnable = Runnable {
                    synchronized(stateLock) {
                        if (!completed && !isConnected) {
                            Log.i(TAG, "Direct connection to $cachedMac pending or timed out after ${DIRECT_CONNECT_TIMEOUT_MS}ms. Transitioning to targeted BLE scan...")
                            closeActiveGatt()
                            startBleScan()
                        } else {
                            Log.i(TAG, "Direct connect timeout fired but already connected/completed. No-op.")
                        }
                    }
                }
                mainHandler.postDelayed(directConnectRunnable!!, DIRECT_CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Direct connection setup error: ${e.message}. Starting scan...")
                startBleScan()
            }
        } else {
            startBleScan()
        }
    }

    @Suppress("DEPRECATION")
    fun pairOverBle(pin: String, onResult: (Boolean, String?) -> Unit) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            onResult(false, "Bluetooth is turned off")
            return
        }

        val permCheck = checkClientPermissions()
        if (permCheck != null) {
            onResult(false, permCheck)
            return
        }

        Log.i(TAG, "pairOverBle: Starting pairing handshake with PIN: $pin")

        var completed = false
        var hasRetriedScan = false
        var pairingTimeoutRunnable: Runnable? = null

        stopActiveScan()
        closeActiveGatt()

        fun finish(success: Boolean, message: String?) {
            if (!completed) {
                completed = true
                pairingTimeoutRunnable?.let {
                    mainHandler.removeCallbacks(it)
                    pairingTimeoutRunnable = null
                }
                stopActiveScan()
                closeActiveGatt()
                Log.i(TAG, "pairOverBle finished: success=$success, message=$message")
                mainHandler.post { onResult(success, message) }
            }
        }

        pairingTimeoutRunnable = Runnable {
            Log.w(TAG, "Pairing operation timeout reached ($PAIRING_TIMEOUT_MS ms)")
            finish(false, "Pairing timed out (phone not found nearby)")
        }
        mainHandler.postDelayed(pairingTimeoutRunnable!!, PAIRING_TIMEOUT_MS)

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (gatt !== activeGatt) {
                    Log.w(TAG, "Ignoring pairing onConnectionStateChange for stale GATT (${gatt.device.address})")
                    return
                }
                Log.i(TAG, "Pairing GATT state change: device=${gatt.device.address}, status=$status, newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Connected to receiver for pairing! Discovering services...")
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        finish(false, "Security error: ${e.message}")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!completed) {
                        Log.w(TAG, "Disconnected during pairing handshake. Status=$status")
                        finish(false, "Disconnected during pairing (status $status)")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (gatt !== activeGatt) {
                    Log.w(TAG, "Ignoring pairing onServicesDiscovered for stale GATT (${gatt.device.address})")
                    return
                }
                Log.i(TAG, "Pairing GATT services discovered: status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(BleConstants.SERVICE_UUID)
                    val pairChar = service?.getCharacteristic(BleConstants.PAIR_CHAR_UUID)

                    if (pairChar != null) {
                        try {
                            pairChar.value = pin.toByteArray(Charsets.UTF_8)
                            pairChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            val initiated = gatt.writeCharacteristic(pairChar)
                            Log.i(TAG, "Writing pairing PIN initiated: $initiated")
                        } catch (e: SecurityException) {
                            finish(false, "Permission denied writing PIN: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "PAIR_CHAR_UUID not found on receiver service")
                        finish(false, "Pairing characteristic not found on phone")
                    }
                } else {
                    finish(false, "Service discovery failed with status $status")
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (gatt !== activeGatt) {
                    Log.w(TAG, "Ignoring pairing onCharacteristicWrite for stale GATT (${gatt.device.address})")
                    return
                }
                Log.i(TAG, "Pairing onCharacteristicWrite: char=${characteristic.uuid}, status=$status")
                if (characteristic.uuid == BleConstants.PAIR_CHAR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Pairing PIN accepted by phone! Reading persistent auth token...")
                        try {
                            val initiated = gatt.readCharacteristic(characteristic)
                            Log.i(TAG, "Reading auth token initiated: $initiated")
                        } catch (e: SecurityException) {
                            finish(false, "Permission denied reading auth token: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "Pairing PIN rejected by phone with status $status")
                        finish(false, "Pairing PIN rejected by phone (status $status)")
                    }
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (gatt !== activeGatt) {
                    Log.w(TAG, "Ignoring pairing onCharacteristicRead for stale GATT (${gatt.device.address})")
                    return
                }
                Log.i(TAG, "Pairing onCharacteristicRead: char=${characteristic.uuid}, status=$status")
                if (characteristic.uuid == BleConstants.PAIR_CHAR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val token = characteristic.value?.let { String(it, Charsets.UTF_8) }
                        Log.i(TAG, "Received auth token from phone: length=${token?.length}")
                        if (!token.isNullOrBlank()) {
                            DevicePreferences.setBleAuthToken(context, token)
                            DevicePreferences.setPhoneMacAddress(context, gatt.device.address)
                            Log.i(TAG, "Auth token and phone MAC ${gatt.device.address} successfully saved!")
                            finish(true, null)
                        } else {
                            finish(false, "Empty auth token returned by phone")
                        }
                    } else {
                        Log.e(TAG, "Failed to read auth token from phone. Status=$status")
                        finish(false, "Failed to read auth token from phone (status $status)")
                    }
                }
            }
        }

        fun startPairingScan() {
            if (completed) return
            val currentAdapter = bluetoothAdapter
            val scanner = currentAdapter?.bluetoothLeScanner

            if (currentAdapter == null || !currentAdapter.isEnabled || scanner == null) {
                finish(false, "Bluetooth LE scanner not available")
                return
            }

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val device = result?.device ?: return
                    Log.i(TAG, "Pairing scan found matching device: ${device.address}, RSSI=${result.rssi}")
                    stopActiveScan()
                    try {
                        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                        } else {
                            device.connectGatt(context, false, gattCallback)
                        }
                    } catch (e: SecurityException) {
                        finish(false, "Permission denied connecting: ${e.message}")
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    val errorDesc = describeScanError(errorCode)
                    Log.e(TAG, "Pairing scan failed: code=$errorCode ($errorDesc)")

                    if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED && !hasRetriedScan && !completed) {
                        hasRetriedScan = true
                        stopActiveScan()
                        mainHandler.postDelayed({
                            if (!completed) {
                                startPairingScan()
                            }
                        }, 350)
                        return
                    }

                    finish(false, "BLE scan failed: $errorDesc (code $errorCode)")
                }
            }

            stopActiveScan()
            activeScanCallback = scanCallback

            try {
                Log.i(TAG, "Starting pairing scan for service UUID ${BleConstants.SERVICE_UUID}...")
                scanner.startScan(listOf(filter), settings, scanCallback)
            } catch (e: SecurityException) {
                activeScanCallback = null
                finish(false, "Permission denied starting scan: ${e.message}")
            }
        }

        startPairingScan()
    }

    private fun stopActiveScan() {
        val callback = activeScanCallback ?: return
        try {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner != null && hasScanPermission()) {
                scanner.stopScan(callback)
                Log.i(TAG, "Active BLE scan callback stopped cleanly.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during stopScan: ${e.message}")
        } finally {
            activeScanCallback = null
        }
    }

    private fun closeActiveGatt() {
        val gatt = activeGatt ?: return
        activeGatt = null
        try {
            gatt.disconnect()
            gatt.close()
            Log.i(TAG, "Active GATT closed cleanly.")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing active GATT: ${e.message}")
        }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkClientPermissions(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                return "BLUETOOTH_SCAN permission not granted"
            }
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return "BLUETOOTH_CONNECT permission not granted"
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return "Location permission not granted"
            }
            // On Android 6 to 11, Location Services must be turned ON for BLE scans
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager?.isLocationEnabled == true
            } else {
                @Suppress("DEPRECATION")
                val mode = android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_OFF
                )
                mode != android.provider.Settings.Secure.LOCATION_MODE_OFF
            }
            if (!locationEnabled) {
                return "Location Services (GPS) must be enabled on Android 11 or lower to scan for BLE devices"
            }
        }
        return null
    }

    private fun describeScanError(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "SCAN_ALREADY_STARTED"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APPLICATION_REGISTRATION_FAILED (stack limit or stale client)"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            else -> "ERROR_$errorCode"
        }
    }

    companion object {
        private const val TAG = "BleWakeSender"
        private const val DIRECT_CONNECT_TIMEOUT_MS = 3500L
        private const val TOTAL_OPERATION_TIMEOUT_MS = 12000L
        private const val PAIRING_TIMEOUT_MS = 12000L
    }
}
