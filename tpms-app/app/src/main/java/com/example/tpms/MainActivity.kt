package com.example.tpms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.example.tpms.ui.PairingUI
import com.example.tpms.ui.SettingsUI
import com.example.tpms.ui.TpmsDashboard
import com.example.tpms.ui.UpdateDialogHost
import com.example.tpms.update.UpdateManager
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val ACTION_USB_PERMISSION = "com.example.tpms.USB_PERMISSION"
    private val currentIntent = mutableStateOf<Intent?>(null)

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d("MainActivity", "Permission granted for device $it")
                            sendBroadcast(Intent("com.example.tpms.ACTION_CONNECT_USB").setPackage(packageName))
                        }
                    } else {
                        Log.d("MainActivity", "Permission denied for device $device")
                        Toast.makeText(context, "USB permission denied. Cannot read TPMS data.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentIntent.value = intent
        TpmsManager.init(this)
        UpdateManager.init(this)

        // Register USB permission receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }

        // Request notification permission for Android 13+
        checkNotificationPermission()

        // Scan and request USB permissions if needed
        checkUsbPermission()

        // Start background service
        val serviceIntent = Intent(this, TpmsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            var currentScreen by remember { mutableStateOf("dashboard") }
            val tpmsState by TpmsManager.tpmsState.collectAsState()
            val activeIntent = currentIntent.value

            LaunchedEffect(activeIntent) {
                if (activeIntent?.getBooleanExtra("TRIGGER_ALARM", false) == true) {
                    if (currentScreen != "settings" && currentScreen != "pairing") {
                        currentScreen = "dashboard"
                    }
                }
            }

            when (currentScreen) {
                "dashboard" -> TpmsDashboard(
                    state = tpmsState,
                    onPairingClick = { currentScreen = "pairing" },
                    onSettingsClick = { currentScreen = "settings" }
                )
                "settings" -> SettingsUI(onBack = { currentScreen = "dashboard" })
                "pairing" -> PairingUI(
                    onBack = { currentScreen = "dashboard" },
                    onPairPosition = { pos ->
                        TpmsManager.startPairing(pos)
                        Toast.makeText(this, "Pairing mode started for position $pos. Mount sensor to pair.", Toast.LENGTH_LONG).show()
                    }
                )
            }

            UpdateDialogHost()
            // Head units often stay on this screen for hours and get online late;
            // the check itself is throttled inside UpdateManager.
            LaunchedEffect(Unit) {
                while (true) {
                    delay(5 * 60_000L)
                    UpdateManager.maybeAutoCheck(this@MainActivity)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkUsbPermission()
        UpdateManager.onResume(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntent.value = intent
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun checkUsbPermission() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) return

        val driver = availableDrivers.firstOrNull {
            it.device.vendorId == 0x1A86 && (it.device.productId == 0x7523 || it.device.productId == 0x5523)
        } ?: availableDrivers.first()

        val device = driver.device
        if (!manager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            // Explicit package: Android 14+ rejects mutable PendingIntents wrapping implicit intents.
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            manager.requestPermission(device, permissionIntent)
        } else {
            sendBroadcast(Intent("com.example.tpms.ACTION_CONNECT_USB").setPackage(packageName))
        }
    }
}
