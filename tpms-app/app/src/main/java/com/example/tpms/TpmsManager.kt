package com.example.tpms

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PairingStep {
    WAITING_FOR_DROP,
    WAITING_FOR_RISE
}

sealed class PairingState {
    object Idle : PairingState()
    data class Pairing(
        val positionCode: Int,
        val timeRemainingSec: Int,
        val step: PairingStep = PairingStep.WAITING_FOR_DROP
    ) : PairingState()
    data class Success(val positionCode: Int) : PairingState()
}

object TpmsManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val parser = TpmsParser()
    val tpmsState: StateFlow<TpmsState> = parser.tpmsState

    private val _commandsToSend = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val commandsToSend: SharedFlow<ByteArray> = _commandsToSend.asSharedFlow()

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    // Live configuration states
    private val _useBar = MutableStateFlow(true)
    val useBar = _useBar.asStateFlow()

    private val _useFahrenheit = MutableStateFlow(false)
    val useFahrenheit = _useFahrenheit.asStateFlow()

    private val _highPressurePsi = MutableStateFlow(45f)
    val highPressurePsi = _highPressurePsi.asStateFlow()

    private val _lowPressurePsi = MutableStateFlow(28f)
    val lowPressurePsi = _lowPressurePsi.asStateFlow()

    private val _highTempC = MutableStateFlow(65)
    val highTempC = _highTempC.asStateFlow()

    private val _activeAlarms = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeAlarms = _activeAlarms.asStateFlow()

    private val _alarmSoundUri = MutableStateFlow<String?>(null)
    val alarmSoundUri = _alarmSoundUri.asStateFlow()

    private val _calFl = MutableStateFlow(0f)
    val calFl = _calFl.asStateFlow()

    private val _calFr = MutableStateFlow(0f)
    val calFr = _calFr.asStateFlow()

    private val _calRl = MutableStateFlow(0f)
    val calRl = _calRl.asStateFlow()

    private val _calRr = MutableStateFlow(0f)
    val calRr = _calRr.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _isTestingSound = MutableStateFlow(false)
    val isTestingSound = _isTestingSound.asStateFlow()

    val snoozedAlarms = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val _alarmSnoozedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val alarmSnoozedEvent = _alarmSnoozedEvent.asSharedFlow()

    // Pressure history for trend arrows: sensor name → list of last 5 pressures (newest last)
    private val _pressureHistory = MutableStateFlow<Map<String, List<Double>>>(emptyMap())
    val pressureHistory = _pressureHistory.asStateFlow()

    fun recordPressure(sensorName: String, pressurePsi: Double) {
        val current = _pressureHistory.value.toMutableMap()
        val history = (current[sensorName] ?: emptyList()).toMutableList()
        history.add(pressurePsi)
        if (history.size > 5) history.removeAt(0)
        current[sensorName] = history
        _pressureHistory.value = current
    }

    fun getTrend(sensorName: String): Int {
        val history = _pressureHistory.value[sensorName] ?: return 0
        if (history.size < 3) return 0
        val recent = history.last()
        val older = history.first()
        return when {
            recent > older + 0.3 -> 1   // rising ▲
            recent < older - 0.3 -> -1  // falling ▼
            else -> 0                    // stable —
        }
    }

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState = _pairingState.asStateFlow()

    private var pairingJob: kotlinx.coroutines.Job? = null

    fun init(context: Context) {
        if (prefs == null) {
            appContext = context.applicationContext
            prefs = context.applicationContext.getSharedPreferences("tpms_prefs", Context.MODE_PRIVATE)
            loadSettings()
        }
    }

    private fun loadSettings() {
        prefs?.let { p ->
            _useBar.value = p.getBoolean("use_bar", true)
            _useFahrenheit.value = p.getBoolean("use_fahrenheit", false)
            _highPressurePsi.value = p.getFloat("high_pressure_psi", 45f)
            _lowPressurePsi.value = p.getFloat("low_pressure_psi", 28f)
            _highTempC.value = p.getInt("high_temp_c", 65)
            _alarmSoundUri.value = p.getString("alarm_sound_uri", null)
            _calFl.value = p.getFloat("cal_fl", 0f)
            _calFr.value = p.getFloat("cal_fr", 0f)
            _calRl.value = p.getFloat("cal_rl", 0f)
            _calRr.value = p.getFloat("cal_rr", 0f)
        }
    }

    fun setUseBar(value: Boolean) {
        _useBar.value = value
        prefs?.edit()?.putBoolean("use_bar", value)?.apply()
    }

    fun setUseFahrenheit(value: Boolean) {
        _useFahrenheit.value = value
        prefs?.edit()?.putBoolean("use_fahrenheit", value)?.apply()
    }

    fun setHighPressurePsi(value: Float) {
        _highPressurePsi.value = value
        prefs?.edit()?.putFloat("high_pressure_psi", value)?.apply()
    }

    fun setLowPressurePsi(value: Float) {
        _lowPressurePsi.value = value
        prefs?.edit()?.putFloat("low_pressure_psi", value)?.apply()
    }

    fun setHighTempC(value: Int) {
        _highTempC.value = value
        prefs?.edit()?.putInt("high_temp_c", value)?.apply()
    }

    fun setAlarmSoundUri(uri: String?) {
        _alarmSoundUri.value = uri
        prefs?.edit()?.putString("alarm_sound_uri", uri)?.apply()
    }

    fun getCalibrationPsi(positionCode: Int): Float {
        return when (positionCode) {
            0 -> _calFl.value
            1 -> _calFr.value
            16 -> _calRl.value
            17 -> _calRr.value
            else -> 0f
        }
    }

    fun setCalibrationPsi(positionCode: Int, value: Float) {
        when (positionCode) {
            0 -> {
                _calFl.value = value
                prefs?.edit()?.putFloat("cal_fl", value)?.apply()
            }
            1 -> {
                _calFr.value = value
                prefs?.edit()?.putFloat("cal_fr", value)?.apply()
            }
            16 -> {
                _calRl.value = value
                prefs?.edit()?.putFloat("cal_rl", value)?.apply()
            }
            17 -> {
                _calRr.value = value
                prefs?.edit()?.putFloat("cal_rr", value)?.apply()
            }
        }
    }

    fun adjustCalibration(positionCode: Int, isIncrement: Boolean) {
        val current = getCalibrationPsi(positionCode)
        val useBarVal = _useBar.value
        val next: Float
        if (useBarVal) {
            // Step is 0.01 BAR. 1 BAR = 14.5038 PSI.
            val currentBar = Math.round((current / 14.5038f) * 100f) / 100f
            var nextBar = if (isIncrement) currentBar + 0.01f else currentBar - 0.01f
            if (nextBar < -0.5f) nextBar = -0.5f
            if (nextBar > 0.5f) nextBar = 0.5f
            next = nextBar * 14.5038f
        } else {
            // Step is 0.1 PSI.
            val currentPsi = Math.round(current * 10f) / 10f
            var nextPsi = if (isIncrement) currentPsi + 0.1f else currentPsi - 0.1f
            if (nextPsi < -7.0f) nextPsi = -7.0f
            if (nextPsi > 7.0f) nextPsi = 7.0f
            next = nextPsi
        }
        setCalibrationPsi(positionCode, next)
    }

    fun resetCalibration() {
        setCalibrationPsi(0, 0f)
        setCalibrationPsi(1, 0f)
        setCalibrationPsi(16, 0f)
        setCalibrationPsi(17, 0f)
    }

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }

    fun setTestingSound(value: Boolean) {
        _isTestingSound.value = value
    }

    fun snoozeTireAlarms(name: String, durationMs: Long) {
        val keys = listOf("$name:Low", "$name:High", "$name:Temp", "$name:Batt", "$name:FastLeak", "$name:SlowLeak", "$name:Offline")
        val snoozeUntil = if (durationMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + durationMs
        keys.forEach { key ->
            snoozedAlarms[key] = snoozeUntil
        }
        scope.launch {
            _alarmSnoozedEvent.emit(Unit)
        }
    }

    fun updateActiveAlarm(sensorName: String, alarmKey: String?) {
        val current = _activeAlarms.value.toMutableMap()
        if (alarmKey != null) {
            current[sensorName] = alarmKey
        } else {
            current.remove(sensorName)
        }
        _activeAlarms.value = current
    }

    fun playPairingSuccessSound() {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.Default) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            var savedVol = -1
            try {
                savedVol = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                val targetVol = (savedVol + 2).coerceAtMost(maxVol)
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, targetVol, 0)

                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                try {
                    repeat(3) {
                        toneGen.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 100)
                        delay(180)
                    }
                    delay(500)
                } finally {
                    toneGen.release()
                }
            } catch (e: Exception) {
                android.util.Log.e("TpmsManager", "Failed to play success tone: ${e.message}")
            } finally {
                // Always restore the original alarm volume, even if playback failed partway.
                if (savedVol >= 0) {
                    try {
                        am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedVol, 0)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun pairingSuccess(positionCode: Int) {
        pairingJob?.cancel()
        _pairingState.value = PairingState.Success(positionCode)
        playPairingSuccessSound()
        scope.launch {
            delay(3000)
            if (_pairingState.value is PairingState.Success) {
                _pairingState.value = PairingState.Idle
            }
        }
    }

    // Commands
    fun updatePairingStep(positionCode: Int, nextStep: PairingStep) {
        val current = _pairingState.value
        if (current is PairingState.Pairing && current.positionCode == positionCode) {
            _pairingState.value = PairingState.Pairing(positionCode, current.timeRemainingSec, nextStep)
        }
    }

    fun startPairing(positionCode: Int) {
        // Frame: [0x55, 0xAA, 0x06, 0x01, position_code, checksum]
        val frame = ByteArray(6)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[2] = 0x06.toByte()
        frame[3] = 0x01.toByte()
        frame[4] = positionCode.toByte()
        frame[5] = calcChecksum(frame)
        _commandsToSend.tryEmit(frame)

        pairingJob?.cancel()
        pairingJob = scope.launch {
            for (i in 120 downTo 0) {
                val current = _pairingState.value
                val step = if (current is PairingState.Pairing) current.step else PairingStep.WAITING_FOR_DROP
                _pairingState.value = PairingState.Pairing(positionCode, i, step)
                delay(1000)
            }
            _pairingState.value = PairingState.Idle
        }
    }

    fun stopPairing() {
        pairingJob?.cancel()
        _pairingState.value = PairingState.Idle
        // Frame: [0x55, 0xAA, 0x06, 0x06, 0x00, checksum]
        val frame = ByteArray(6)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[2] = 0x06.toByte()
        frame[3] = 0x06.toByte()
        frame[4] = 0x00.toByte()
        frame[5] = calcChecksum(frame)
        _commandsToSend.tryEmit(frame)
    }

    fun querySensorIds() {
        // Frame: [0x55, 0xAA, 0x06, 0x07, 0x00, checksum]
        val frame = ByteArray(6)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[2] = 0x06.toByte()
        frame[3] = 0x07.toByte()
        frame[4] = 0x00.toByte()
        frame[5] = calcChecksum(frame)
        _commandsToSend.tryEmit(frame)
    }

    fun resetDevice() {
        // Frame: [0x55, 0xAA, 0x06, 0x58, 0x55, checksum]
        val frame = ByteArray(6)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[2] = 0x06.toByte()
        frame[3] = 0x58.toByte()
        frame[4] = 0x55.toByte()
        frame[5] = calcChecksum(frame)
        _commandsToSend.tryEmit(frame)
    }

    private fun calcChecksum(frame: ByteArray): Byte {
        val length = frame[2].toInt() and 0xFF
        var checksum = frame[0].toInt() and 0xFF
        for (i in 1 until length - 1) {
            checksum = checksum xor (frame[i].toInt() and 0xFF)
        }
        return checksum.toByte()
    }
}
