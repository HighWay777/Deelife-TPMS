package com.example.tpms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

class TpmsService : Service() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.tpms.USB_PERMISSION_SVC"
        private const val ALARM_REPEAT_MS = 60_000L  // repeat sound every 60 seconds
        private const val OFFLINE_ALARM_MS = 30_000L // sound an alarm if a paired tire stops reporting
    }

    private val serviceScope = CoroutineScope(CoroutineName("TpmsServiceScope") + Job())

    private var usbPort: UsbSerialPort? = null
    @Volatile private var isConnected = false
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var alarmJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    // Alarm state
    @Volatile private var isAlarmActive = false
    private var lastActiveAlarmKey: String? = null
    private var savedAlarmVolume: Int = -1 // saved original volume to restore on stop

    // Repeating sound runnable - fires every ALARM_REPEAT_MS while alarm is active
    private val alarmSoundRunnable = object : Runnable {
        override fun run() {
            if (!isAlarmActive) return
            playAlarmSound(boostVolume = false)
            mainHandler.postDelayed(this, ALARM_REPEAT_MS)
        }
    }

    // Track initial ping callbacks so they can be cancelled on re-trigger
    private val initialPingRunnable1 = Runnable {
        if (isAlarmActive) playAlarmSound(boostVolume = false)
    }
    private val initialPingRunnable2 = Runnable {
        if (isAlarmActive) playAlarmSound(boostVolume = false)
    }

    // Data class for tracking history
    private data class TireHistory(val time: Long, val pressure: Float, val temp: Int)

    // Tracks pressure/temp history for slow leak detection (thread-safe)
    private val pressureHistory = java.util.concurrent.ConcurrentHashMap<String, MutableList<TireHistory>>()

    // -----------------------------------------------------------------------
    // USB permission receiver (lives inside the service — works without UI)
    // -----------------------------------------------------------------------
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION != intent.action) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (granted) {
                Log.i("TpmsService", "USB permission GRANTED for ${device?.deviceName}")
                connectUsb()
            } else {
                Log.w("TpmsService", "USB permission DENIED for ${device?.deviceName}")
            }
        }
    }

    // -----------------------------------------------------------------------
    // USB attach/detach receiver
    // -----------------------------------------------------------------------
    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                "com.example.tpms.ACTION_CONNECT_USB" -> {
                    Log.d("TpmsService", "USB attach event — connecting...")
                    connectUsb()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d("TpmsService", "USB detach event")
                    disconnectUsb()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    override fun onCreate() {
        super.onCreate()
        TpmsManager.init(this)
        createNotificationChannel()
        startForeground(1, createNotification())

        // Register receivers
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction("com.example.tpms.ACTION_CONNECT_USB")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
            registerReceiver(usbAttachReceiver, attachFilter)
        }

        // Start monitoring
        startAlarmMonitor()

        // Try to connect immediately; if no permission, request it.
        // Also start a retry loop for the first 3 minutes after boot.
        connectUsb()
        startConnectionRetryLoop()

        serviceScope.launch {
            TpmsManager.isTestingSound.collect { testing ->
                if (testing) {
                    startTestSoundLoop()
                } else {
                    stopTestSoundLoop()
                }
            }
        }

        serviceScope.launch {
            TpmsManager.alarmSnoozedEvent.collect {
                evaluateAlarms(TpmsManager.tpmsState.value)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.example.tpms.ACTION_SNOOZE") {
            val key = intent.getStringExtra("ALARM_KEY")
            if (key != null) {
                val tireName = key.substringBefore(":")
                TpmsManager.snoozeTireAlarms(tireName, 10 * 60_000L)
                Log.i("TpmsService", "Alarm $key snoozed from notification action for 10 minutes")
            }
        }
        connectUsb()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
        disconnectUsb()
        serviceScope.cancel()
        stopAlarmSound()
        stopTestSoundLoop()
    }

    // -----------------------------------------------------------------------
    // Connection retry loop — critical for background / boot startup
    // After boot the USB permission dialog may need a moment to be shown and
    // accepted. We retry every 5 s for up to 3 minutes.
    // -----------------------------------------------------------------------
    private fun startConnectionRetryLoop() {
        serviceScope.launch {
            repeat(36) {   // 36 × 5 s = 3 minutes
                delay(5_000)
                if (!isConnected) {
                    Log.d("TpmsService", "Retry connect attempt $it")
                    connectUsb()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // USB helpers
    // -----------------------------------------------------------------------
    @Synchronized
    private fun connectUsb() {
        if (isConnected) return

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) {
            Log.d("TpmsService", "No USB serial drivers found")
            return
        }

        val driver = drivers.firstOrNull {
            it.device.vendorId == 0x1A86 &&
                    (it.device.productId == 0x7523 || it.device.productId == 0x5523)
        } ?: drivers.first()

        val device = driver.device

        // *** KEY FIX: if we don't have permission, REQUEST it from the service ***
        if (!manager.hasPermission(device)) {
            Log.d("TpmsService", "No USB permission — requesting from service...")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val pi = PendingIntent.getBroadcast(this, 2, intent, flags)
            manager.requestPermission(device, pi)
            return
        }

        try {
            val connection = manager.openDevice(device) ?: return
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbPort = port
            isConnected = true
            TpmsManager.setConnected(true)
            Log.i("TpmsService", "USB connected ✓")
            startCommunicationLoops()
        } catch (e: Exception) {
            Log.e("TpmsService", "USB connection failed: ${e.message}", e)
            disconnectUsb()
        }
    }

    @Synchronized
    private fun disconnectUsb() {
        readJob?.cancel()
        writeJob?.cancel()
        try { usbPort?.close() } catch (_: Exception) {}
        usbPort = null
        isConnected = false
        TpmsManager.setConnected(false)
        Log.d("TpmsService", "USB disconnected")
    }

    private fun startCommunicationLoops() {
        readJob?.cancel()
        writeJob?.cancel()

        readJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isActive && isConnected) {
                val port = usbPort ?: break
                try {
                    val n = port.read(buffer, 1000)
                    if (n > 0) TpmsManager.parser.processBytes(buffer.copyOf(n))
                } catch (e: Exception) {
                    Log.e("TpmsService", "Read error: ${e.message}")
                    mainHandler.post { disconnectUsb() }
                    break
                }
                delay(10)
            }
        }

        writeJob = serviceScope.launch(Dispatchers.IO) {
            TpmsManager.commandsToSend.collect { bytes ->
                if (isConnected) {
                    try { usbPort?.write(bytes, 1000) }
                    catch (e: Exception) { Log.e("TpmsService", "Write error: ${e.message}") }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Alarm monitor — re-evaluates on EVERY new TPMS packet AND on threshold
    // changes (so sound plays immediately when user tweaks settings in UI)
    // -----------------------------------------------------------------------
    private fun startAlarmMonitor() {
        alarmJob?.cancel()
        alarmJob = serviceScope.launch {
            combine(
                TpmsManager.tpmsState,
                TpmsManager.lowPressurePsi,
                TpmsManager.highPressurePsi,
                TpmsManager.highTempC
            ) { state, _, _, _ -> state }
                .collectLatest { state ->
                    evaluateAlarms(state)
                }
        }
        // Also re-evaluate on a plain timer. If the USB link drops entirely (cable knocked
        // loose, receiver dies), tpmsState stops changing and the collector above never
        // re-fires — without this, a full signal loss would never trigger the offline alarm.
        serviceScope.launch {
            while (isActive) {
                delay(OFFLINE_ALARM_MS)
                evaluateAlarms(TpmsManager.tpmsState.value)
            }
        }
    }

    private fun alarmSeverity(key: String): Int = when {
        key.endsWith(":FastLeak") -> 100
        key.endsWith(":Low")      -> 80
        key.endsWith(":High")     -> 70
        key.endsWith(":Temp")     -> 60
        key.endsWith(":SlowLeak") -> 50
        key.endsWith(":Batt")     -> 30
        key.endsWith(":Offline")  -> 20
        else                      -> 0
    }

    private fun evaluateAlarms(state: TpmsState) {
        if (!isConnected) {
            // When USB is disconnected, clear active alarm state and do not sound false offline alarms
            mainHandler.post {
                if (isAlarmActive) {
                    stopAlarmSound()
                    lastActiveAlarmKey = null
                    updateNotification(null, null)
                }
            }
            return
        }

        val tires = listOf(
            "Front Left"  to state.frontLeft,
            "Front Right" to state.frontRight,
            "Rear Left"   to state.rearLeft,
            "Rear Right"  to state.rearRight
        )

        val lowPsi        = TpmsManager.lowPressurePsi.value
        val highPsi       = TpmsManager.highPressurePsi.value
        val highTmp       = TpmsManager.highTempC.value
        val useBar        = TpmsManager.useBar.value
        val useFahrenheit = TpmsManager.useFahrenheit.value
        val now           = System.currentTimeMillis()

        var globalActiveAlarmMsg: String? = null
        var globalActiveAlarmKey: String? = null
        var highestSeverity = -1

        val pState = TpmsManager.pairingState.value
        val pairingPositionCode = if (pState is PairingState.Pairing) pState.positionCode else null

        for ((name, tire) in tires) {
            if (tire == null) {
                TpmsManager.updateActiveAlarm(name, null)
                continue
            }

            // 1. Skip sensor if it is currently in pairing mode
            val positionCode = when (name) {
                "Front Left" -> 0
                "Front Right" -> 1
                "Rear Left" -> 16
                "Rear Right" -> 17
                else -> -1
            }
            if (pairingPositionCode != null && pairingPositionCode == positionCode) {
                TpmsManager.updateActiveAlarm(name, null)
                continue // Do not check alarms for this tire while it is pairing!
            }

            // Thread-safe slow-leak history
            val history = pressureHistory.getOrPut(name) { mutableListOf() }
            synchronized(history) {
                history.add(TireHistory(now, tire.pressurePsi.toFloat(), tire.temperatureC))
                history.removeAll { it.time < now - 900_000 }
            }

            var tireAlarmKey: String? = null
            var tireAlarmMsg: String? = null

            // Prioritized per-tire evaluations:
            // 1. Fast Leak / Hardware Error Alarm (0x08 bitmask) - highest safety priority
            if ((tire.status and 0x08) != 0) {
                tireAlarmKey = "$name:FastLeak"
                tireAlarmMsg = "$name Fast Leak / Error!"
            }
            // 2. Low Pressure Alarm
            else if (tire.pressurePsi < lowPsi) {
                tireAlarmKey = "$name:Low"
                tireAlarmMsg = if (useBar) "$name Low: ${"%.2f".format(tire.pressurePsi / 14.5038f)} Bar"
                               else        "$name Low: ${"%.1f".format(tire.pressurePsi)} PSI"
            }
            // 3. High Pressure Alarm
            else if (tire.pressurePsi > highPsi) {
                tireAlarmKey = "$name:High"
                tireAlarmMsg = if (useBar) "$name High: ${"%.2f".format(tire.pressurePsi / 14.5038f)} Bar"
                               else        "$name High: ${"%.1f".format(tire.pressurePsi)} PSI"
            }
            // 4. High Temperature Alarm (respects useFahrenheit)
            else if (tire.temperatureC > highTmp) {
                tireAlarmKey = "$name:Temp"
                tireAlarmMsg = if (useFahrenheit) "$name Temp High: ${Math.round(tire.temperatureC * 9f / 5f + 32f)}°F"
                               else               "$name Temp High: ${tire.temperatureC}°C"
            }
            // 5. Slow Leak Alarm
            else {
                val hasSlowLeak = synchronized(history) {
                    history.size >= 2 && run {
                        val oldData = history.first()
                        val timeDelta = now - oldData.time
                        if (timeDelta < 300_000) return@run false
                        val rawPressureDrop = oldData.pressure - tire.pressurePsi
                        if (rawPressureDrop <= 2.0f) return@run false
                        val tempDrop = oldData.temp - tire.temperatureC
                        if (tempDrop > 5) return@run false
                        val expectedPressureDrop = tempDrop * 0.16f
                        val adjustedOldPressure = oldData.pressure - expectedPressureDrop
                        (adjustedOldPressure - tire.pressurePsi) > 2.0f
                    }
                }
                if (hasSlowLeak) {
                    tireAlarmKey = "$name:SlowLeak"
                    tireAlarmMsg = if (useBar) "$name Slow Leak! ${"%.2f".format(tire.pressurePsi / 14.5038f)} Bar"
                                   else        "$name Slow Leak! ${"%.1f".format(tire.pressurePsi)} PSI"
                }
                // 6. Low Battery Alarm
                else if (tire.isLowBattery) {
                    tireAlarmKey = "$name:Batt"
                    tireAlarmMsg = "$name Sensor Battery Low"
                }
                // 7. Offline / Signal Lost Alarm (only when USB is connected and this specific sensor dropped)
                else {
                    val ageMs = now - tire.lastUpdatedMs
                    if (ageMs > OFFLINE_ALARM_MS) {
                        tireAlarmKey = "$name:Offline"
                        tireAlarmMsg = "$name Sensor Offline — no signal for ${ageMs / 1000}s"
                    }
                }
            }

            TpmsManager.updateActiveAlarm(name, tireAlarmKey)

            if (tireAlarmKey != null) {
                val snoozeTime = TpmsManager.snoozedAlarms[tireAlarmKey] ?: 0L
                if (now >= snoozeTime) {
                    val severity = alarmSeverity(tireAlarmKey)
                    if (severity > highestSeverity) {
                        highestSeverity = severity
                        globalActiveAlarmKey = tireAlarmKey
                        globalActiveAlarmMsg = tireAlarmMsg
                    }
                }
            }
        }

        // Post to main thread
        mainHandler.post {
            if (globalActiveAlarmMsg != null && globalActiveAlarmKey != null) {
                val isNewAlarmType = globalActiveAlarmKey != lastActiveAlarmKey
                if (!isAlarmActive || isNewAlarmType) {
                    startAlarmSound()
                    lastActiveAlarmKey = globalActiveAlarmKey
                    updateNotification(globalActiveAlarmMsg, globalActiveAlarmKey)
                }
            } else {
                if (isAlarmActive) {
                    stopAlarmSound()
                    lastActiveAlarmKey = null
                    updateNotification(null, null)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Audio Sound Test Loop Helpers
    // -----------------------------------------------------------------------
    private var testSoundJob: Job? = null

    private fun startTestSoundLoop() {
        testSoundJob?.cancel()
        // Save current volume before test boost
        if (savedAlarmVolume < 0) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                savedAlarmVolume = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            } catch (_: Exception) {}
        }
        testSoundJob = serviceScope.launch {
            while (isActive) {
                val isPlaying = synchronized(this@TpmsService) {
                    activeMediaPlayer?.isPlaying == true
                }
                if (!isPlaying) {
                    playAlarmSound(boostVolume = true)
                }
                delay(1000)
            }
        }
    }

    private fun stopTestSoundLoop() {
        testSoundJob?.cancel()
        testSoundJob = null
        stopCurrentSound()
        // Restore original volume after test
        if (savedAlarmVolume >= 0) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            } catch (_: Exception) {}
            savedAlarmVolume = -1
        }
    }

    // -----------------------------------------------------------------------
    // Alarm sound — 3 quick pings on first trigger, then repeat every 60 s
    // Stops on snooze or when alarm condition clears naturally.
    // Volume is boosted for first ping only, original volume restored on stop.
    // -----------------------------------------------------------------------
    private fun startAlarmSound() {
        isAlarmActive = true
        mainHandler.removeCallbacks(alarmSoundRunnable)
        mainHandler.removeCallbacks(initialPingRunnable1)
        mainHandler.removeCallbacks(initialPingRunnable2)
        // Save original volume once, before any boost
        if (savedAlarmVolume < 0) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                savedAlarmVolume = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            } catch (_: Exception) {}
        }
        // Play 3 immediate pings (t=0, t=800ms, t=1600ms) — first one boosted
        playAlarmSound(boostVolume = true)
        mainHandler.postDelayed(initialPingRunnable1, 800)
        mainHandler.postDelayed(initialPingRunnable2, 1600)
        // Then begin the 60-second repeating cycle (no boost)
        mainHandler.postDelayed(alarmSoundRunnable, ALARM_REPEAT_MS)
    }

    private fun stopAlarmSound() {
        isAlarmActive = false
        mainHandler.removeCallbacks(alarmSoundRunnable)
        mainHandler.removeCallbacks(initialPingRunnable1)
        mainHandler.removeCallbacks(initialPingRunnable2)
        stopCurrentSound()
        // Restore original alarm volume
        if (savedAlarmVolume >= 0) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            } catch (_: Exception) {}
            savedAlarmVolume = -1
        }
    }

    private var activeMediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    @Synchronized
    private fun abandonAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus { }
            }
        } catch (e: Exception) {
            Log.e("TpmsService", "Error abandoning audio focus: ${e.message}")
        }
    }

    @Synchronized
    private fun playAlarmSound(boostVolume: Boolean = false) {
        stopCurrentSound()
        
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        
        // Request audio focus to duck music
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = focusRequest
            am.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus({ }, android.media.AudioManager.STREAM_ALARM, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }

        if (boostVolume) {
            try {
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxVol, 0)
            } catch (e: Exception) {
                Log.e("TpmsService", "Failed to set stream max volume: ${e.message}")
            }
        }
        try {
            val mp = MediaPlayer()
            activeMediaPlayer = mp
            val uriStr = TpmsManager.alarmSoundUri.value
            if (uriStr != null) {
                mp.setDataSource(this, Uri.parse(uriStr))
            } else {
                val afd = resources.openRawResourceFd(R.raw.alarm_ping)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setOnCompletionListener { 
                it.release() 
                synchronized(this) {
                    if (activeMediaPlayer == it) {
                        activeMediaPlayer = null
                    }
                }
                abandonAudioFocus() // Restore ducked car music volume immediately
            }
            mp.prepare()
            mp.start()
            Log.d("TpmsService", "🔔 Alarm sound played (custom: ${uriStr != null})")
        } catch (e: Exception) {
            Log.e("TpmsService", "Sound playback failed: ${e.message}", e)
            try {
                val mp = MediaPlayer()
                activeMediaPlayer = mp
                val afd = resources.openRawResourceFd(R.raw.alarm_ping)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setOnCompletionListener { 
                    it.release() 
                    synchronized(this) {
                        if (activeMediaPlayer == it) {
                            activeMediaPlayer = null
                        }
                    }
                    abandonAudioFocus()
                }
                mp.prepare()
                mp.start()
                Log.d("TpmsService", "🔔 Fallback alarm sound played")
            } catch (e2: Exception) {
                Log.e("TpmsService", "Fallback sound playback also failed: ${e2.message}", e2)
            }
        }
    }

    @Synchronized
    private fun stopCurrentSound() {
        abandonAudioFocus()
        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("TpmsService", "Error stopping sound: ${e.message}")
        }
        activeMediaPlayer = null
    }

    // -----------------------------------------------------------------------
    // Notification helpers
    // -----------------------------------------------------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Standard persistent notification channel (silent/minimized)
            val ch = NotificationChannel(
                "TPMS_CHANNEL", "TPMS Monitoring", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Monitors USB TPMS sensors in the background" }
            nm.createNotificationChannel(ch)

            // Dedicated alert channel with high importance so heads-up banners & fullScreenIntent work
            val alertCh = NotificationChannel(
                "TPMS_ALERT_CHANNEL", "TPMS Alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical TPMS tire pressure and temperature alarms"
                enableVibration(true)
            }
            nm.createNotificationChannel(alertCh)
        }
    }

    private fun createNotification(alertMsg: String? = null, alertKey: String? = null): Notification {
        val isAlert = alertMsg != null && alertKey != null
        val channelId = if (isAlert) "TPMS_ALERT_CHANNEL" else "TPMS_CHANNEL"
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOnlyAlertOnce(true)

        val appFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val appIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (isAlert) putExtra("TRIGGER_ALARM", true)
        }
        val appPi = PendingIntent.getActivity(this, 102, appIntent, appFlags)
        builder.setContentIntent(appPi)

        if (alertMsg != null && alertKey != null) {
            builder.setContentTitle("⚠️ TPMS Alert!")
                .setContentText(alertMsg)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setColor(Color.RED)

            // Wake the screen and bring the app to front even when the phone is locked
            // or fully backgrounded.
            val canFullScreen = if (Build.VERSION.SDK_INT >= 34) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
            } else true
            if (canFullScreen) {
                builder.setFullScreenIntent(appPi, true)
            } else {
                Log.w("TpmsService", "Full-screen intent not permitted by system; alert will only show as a notification")
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val snoozeIntent = Intent(this, TpmsService::class.java).apply {
                action = "com.example.tpms.ACTION_SNOOZE"
                putExtra("ALARM_KEY", alertKey)
            }
            val snoozePi = PendingIntent.getService(this, 101, snoozeIntent, flags)
            builder.addAction(android.R.drawable.ic_lock_silent_mode, "Snooze 10 Min", snoozePi)
        } else {
            builder.setContentTitle("TPMS Monitoring Active")
                .setContentText("Monitoring tire pressure in background…")
                .setPriority(NotificationCompat.PRIORITY_LOW)
        }

        return builder.build()
    }

    private fun updateNotification(alertMsg: String? = null, alertKey: String? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, createNotification(alertMsg, alertKey))
    }
}
