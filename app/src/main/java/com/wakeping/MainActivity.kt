package com.wakeping

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {

    private lateinit var wakeButton: Button
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val functions by lazy { FirebaseFunctions.getInstance() }
    private val bleSender by lazy { BleWakeSender(this) }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission handled
    }

    private var onBluetoothPermissionsGranted: (() -> Unit)? = null

    private val requestBluetoothPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            onBluetoothPermissionsGranted?.invoke()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required for offline mode.", Toast.LENGTH_SHORT).show()
        }
        onBluetoothPermissionsGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable display illumination over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_main)

        WakePingMessagingService.createNotificationChannel(this)
        requestNotificationPermission()
        ensureAuthenticated()

        // If phone is already paired as receiver, ensure BLE listener service is active
        if (DevicePreferences.isPaired(this) && DevicePreferences.getRole(this) == DevicePreferences.ROLE_RECEIVER) {
            startBleReceiverIfSupported()
        }

        wakeButton = findViewById(R.id.btnWakePhone)
        wakeButton.setOnClickListener {
            handleWakeButtonClick()
        }

        wakeButton.setOnLongClickListener {
            if (DevicePreferences.isPaired(this)) {
                showResetPairingDialog()
                true
            } else {
                false
            }
        }
    }

    private fun ensureAuthenticated(onSuccess: (() -> Unit)? = null) {
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    onSuccess?.invoke()
                }
                .addOnFailureListener {
                    // Anonymous auth failure will not prevent offline BLE operation
                    onSuccess?.invoke()
                }
        } else {
            onSuccess?.invoke()
        }
    }

    private fun handleWakeButtonClick() {
        if (!DevicePreferences.isPaired(this)) {
            showRoleSelectionDialog()
            return
        }

        val role = DevicePreferences.getRole(this)
        if (role == DevicePreferences.ROLE_RECEIVER) {
            Toast.makeText(
                this,
                "This device is set as the receiving phone.\nLong-press button to reset pairing.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Device is paired as Sender (Tablet) -> Send wake ping (BLE first, Firebase fallback)
        sendDualModeWakePing()
    }

    private fun sendDualModeWakePing() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter

        if (adapter != null && !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is turned off on this tablet.", Toast.LENGTH_SHORT).show()
        }

        // Ensure permissions are granted before initiating BLE
        if (hasMissingSenderPermissions()) {
            onBluetoothPermissionsGranted = { sendDualModeWakePing() }
            requestSenderPermissions()
            return
        }

        wakeButton.isEnabled = false
        Toast.makeText(this, "Sending wake ping...", Toast.LENGTH_SHORT).show()

        // 1. Attempt offline BLE direct wake first
        bleSender.sendWakeCommand { result ->
            when (result) {
                is BleWakeSender.WakeResult.Success -> {
                    Toast.makeText(this, "Wake ping sent via Bluetooth!", Toast.LENGTH_SHORT).show()
                    wakeButton.postDelayed({ wakeButton.isEnabled = true }, 3000)
                }
                is BleWakeSender.WakeResult.Failure -> {
                    // 2. Bluetooth failed or out of range -> seamless fallback to Firebase
                    sendFirebaseFallbackPing(bleFailureReason = result.reason)
                }
            }
        }
    }

    private fun sendFirebaseFallbackPing(bleFailureReason: String) {
        Toast.makeText(this, "Bluetooth unreachable ($bleFailureReason). Trying Internet...", Toast.LENGTH_SHORT).show()

        functions.getHttpsCallable("wakePhone")
            .call()
            .addOnSuccessListener {
                Toast.makeText(this, "Wake ping sent via Internet (Firebase)!", Toast.LENGTH_SHORT).show()
                wakeButton.postDelayed({ wakeButton.isEnabled = true }, 3000)
            }
            .addOnFailureListener { e ->
                val errorMsg = e.message ?: "Network error"
                Toast.makeText(
                    this,
                    "Send failed.\nBLE: $bleFailureReason\nInternet: $errorMsg",
                    Toast.LENGTH_LONG
                ).show()
                wakeButton.postDelayed({ wakeButton.isEnabled = true }, 2000)
            }
    }

    private fun showRoleSelectionDialog() {
        AlertDialog.Builder(this)
            .setTitle("WakePing Setup")
            .setMessage("Select this device's role:")
            .setPositiveButton("This is Phone (Receiver)") { _, _ ->
                checkReceiverPermissionsAndSetup()
            }
            .setNegativeButton("This is Tablet (Sender)") { _, _ ->
                checkSenderPermissionsAndSetup()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun checkReceiverPermissionsAndSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (needed.isNotEmpty()) {
                onBluetoothPermissionsGranted = { proceedReceiverHardwareCheck() }
                requestBluetoothPermissionsLauncher.launch(needed.toTypedArray())
                return
            }
        }

        proceedReceiverHardwareCheck()
    }

    private fun proceedReceiverHardwareCheck() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter

        if (adapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device.", Toast.LENGTH_LONG).show()
            setupAsReceiver(bleSupported = false)
            return
        }

        if (!adapter.isEnabled) {
            Toast.makeText(this, "Please turn on Bluetooth to enable offline wake.", Toast.LENGTH_LONG).show()
        }

        val adv = adapter.bluetoothLeAdvertiser
        val isMultiAdvSupported = adapter.isMultipleAdvertisementSupported

        if (adv == null && !isMultiAdvSupported) {
            AlertDialog.Builder(this)
                .setTitle("BLE Advertising Unsupported")
                .setMessage("This phone's Bluetooth chip does not support BLE Peripheral mode. Only Firebase wake mode will work.")
                .setPositiveButton("Continue with Firebase") { _, _ ->
                    setupAsReceiver(bleSupported = false)
                }
                .show()
            return
        }

        setupAsReceiver(bleSupported = true)
    }

    private fun setupAsReceiver(bleSupported: Boolean) {
        Toast.makeText(this, "Registering receiver...", Toast.LENGTH_SHORT).show()

        // Generate and store persistent BLE auth secret
        DevicePreferences.getBleAuthToken(this)

        // Attempt online registration with Firebase; if offline, generate local PIN
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                functions.getHttpsCallable("registerReceiver")
                    .call(mapOf("fcmToken" to token))
                    .addOnSuccessListener { result ->
                        val data = result.data as? Map<*, *>
                        val code = data?.get("pairingCode") as? String ?: generateFallbackPin()
                        completeReceiverSetup(code, bleSupported)
                    }
                    .addOnFailureListener {
                        val code = generateFallbackPin()
                        completeReceiverSetup(code, bleSupported)
                    }
            }
            .addOnFailureListener {
                val code = generateFallbackPin()
                completeReceiverSetup(code, bleSupported)
            }
    }

    private fun completeReceiverSetup(code: String, bleSupported: Boolean) {
        DevicePreferences.setActivePin(this, code)
        DevicePreferences.setPairing(this, DevicePreferences.ROLE_RECEIVER)

        if (bleSupported) {
            startBleReceiverIfSupported()
        }

        AlertDialog.Builder(this)
            .setTitle("Pairing Code")
            .setMessage("Your 6-digit pairing code is:\n\n$code\n\nEnter this code on your tablet to complete pairing (valid for 10 minutes).")
            .setPositiveButton("Done", null)
            .show()
    }

    private fun hasMissingSenderPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestSenderPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isNotEmpty()) {
            requestBluetoothPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun checkSenderPermissionsAndSetup() {
        if (hasMissingSenderPermissions()) {
            onBluetoothPermissionsGranted = { setupAsSender() }
            requestSenderPermissions()
        } else {
            setupAsSender()
        }
    }

    private fun setupAsSender() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "6-digit code"
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 10, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Pair Tablet")
            .setMessage("Enter the 6-digit pairing code shown on your phone:")
            .setView(container)
            .setPositiveButton("Pair") { _, _ ->
                val code = input.text.toString().trim()
                if (code.length == 6) {
                    pairTabletDualMode(code)
                } else {
                    Toast.makeText(this, "Please enter a valid 6-digit code.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pairTabletDualMode(code: String) {
        Toast.makeText(this, "Pairing with phone...", Toast.LENGTH_SHORT).show()

        // 1. Establish offline BLE pairing secret
        bleSender.pairOverBle(code) { bleSuccess, bleMsg ->
            if (bleSuccess) {
                DevicePreferences.setPairing(this, DevicePreferences.ROLE_SENDER)
                Toast.makeText(this, "Bluetooth paired! Tap WAKE PHONE to alert.", Toast.LENGTH_LONG).show()
            } else {
                Log.w("MainActivity", "BLE pairing skipped or failed: $bleMsg")
            }

            // 2. Also register with Firebase if internet is available
            functions.getHttpsCallable("pairTablet")
                .call(mapOf("code" to code))
                .addOnSuccessListener {
                    DevicePreferences.setPairing(this, DevicePreferences.ROLE_SENDER)
                    if (!bleSuccess) {
                        Toast.makeText(this, "Internet paired! Tap WAKE PHONE to alert.", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener { e ->
                    if (!bleSuccess) {
                        Toast.makeText(this, "Pairing failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun startBleReceiverIfSupported() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter

        if (adapter != null) {
            try {
                BleReceiverService.start(this)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start BLE service: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showResetPairingDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Pairing?")
            .setMessage("Do you want to reset pairing on this device?")
            .setPositiveButton("Reset") { _, _ ->
                BleReceiverService.stop(this)
                DevicePreferences.reset(this)
                Toast.makeText(this, "Pairing reset.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun generateFallbackPin(): String {
        val rand = SecureRandom()
        val num = 100000 + rand.nextInt(900000)
        return num.toString()
    }
}
