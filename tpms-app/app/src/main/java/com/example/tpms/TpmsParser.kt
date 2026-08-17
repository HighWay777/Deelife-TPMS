package com.example.tpms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.util.Log

data class TireData(
    val position: Int,
    val pressurePsi: Double,
    val temperatureC: Int,
    val isLowBattery: Boolean,
    val status: Int = 0,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

data class TpmsState(
    val frontLeft: TireData? = null,
    val frontRight: TireData? = null,
    val rearLeft: TireData? = null,
    val rearRight: TireData? = null
)

class TpmsParser {
    private val _tpmsState = MutableStateFlow(TpmsState())
    val tpmsState: StateFlow<TpmsState> = _tpmsState.asStateFlow()

    private val buffer = ArrayDeque<Byte>()

    fun processBytes(bytes: ByteArray) {
        buffer.addAll(bytes.toList())

        while (buffer.size >= 5) {
            // Find frame header 0x55 0xAA
            if (buffer[0] != 0x55.toByte() || buffer[1] != 0xAA.toByte()) {
                buffer.removeFirst()
                continue
            }

            val length = buffer[2].toInt() and 0xFF
            if (length < 5 || length > 20) {
                // Invalid length byte, remove the first header to search again
                buffer.removeFirst()
                continue
            }

            if (buffer.size < length) {
                // Not enough bytes yet for the full frame
                break
            }

            val frame = ByteArray(length)
            for (i in 0 until length) {
                frame[i] = buffer[i]
            }

            // Verify XOR Checksum (all bytes from 0 up to length-2)
            var checksum = frame[0].toInt() and 0xFF
            for (i in 1 until length - 1) {
                checksum = checksum xor (frame[i].toInt() and 0xFF)
            }
            val frameChecksum = frame[length - 1].toInt() and 0xFF

            if (checksum != frameChecksum) {
                Log.w("TpmsParser", "Checksum mismatch! Calculated: ${checksum.toString(16)}, Frame: ${frameChecksum.toString(16)}")
                buffer.removeFirst() // Remove first byte and try parsing again
                continue
            }

            // Frame is valid, process it based on length
            if (length == 8) {
                val position = frame[3].toInt() and 0xFF
                val pressureHex = frame[4].toInt() and 0xFF
                val tempHex = frame[5].toInt() and 0xFF
                val status = frame[6].toInt() and 0xFF

                // Formulas:
                // Pressure: Value * 0.5 = PSI (or Value * 0.0345 = BAR)
                val calOffset = when (position) {
                    0x00 -> TpmsManager.calFl.value
                    0x01 -> TpmsManager.calFr.value
                    0x10 -> TpmsManager.calRl.value
                    0x11 -> TpmsManager.calRr.value
                    else -> 0f
                }
                val pressurePsi = maxOf(0.0, (pressureHex * 0.5) + calOffset)
                // Temperature: Value - 50 = °C
                val temperatureC = tempHex - 50
                // Low battery: check status bit 0x01
                val isLowBattery = (status and 0x01) != 0

                val tireData = TireData(
                    position = position,
                    pressurePsi = pressurePsi,
                    temperatureC = temperatureC,
                    isLowBattery = isLowBattery,
                    status = status,
                    lastUpdatedMs = System.currentTimeMillis()
                )

                // Handle pairing success (multi-step drop and rise check)
                val pState = TpmsManager.pairingState.value
                if (pState is PairingState.Pairing && pState.positionCode == position) {
                    if (pState.step == PairingStep.WAITING_FOR_DROP) {
                        // Phase 1: Wait for pressure drop (unscrewing sensor)
                        if ((status and 0x08) != 0 || pressurePsi < 5.0) {
                            TpmsManager.updatePairingStep(position, PairingStep.WAITING_FOR_RISE)
                            Log.i("TpmsParser", "Pairing position $position: Detected pressure drop to ${pressurePsi} PSI. Waiting for rise.")
                        }
                    } else if (pState.step == PairingStep.WAITING_FOR_RISE) {
                        // Phase 2: Wait for pressure rise (screwing sensor back on)
                        if (pressurePsi >= 15.0 && (status and 0x08) == 0) {
                            TpmsManager.pairingSuccess(position)
                            Log.i("TpmsParser", "Pairing position $position: Detected pressure rise to ${pressurePsi} PSI. Pairing successful.")
                        }
                    }
                }

                val sensorName = when (position) {
                    0x00 -> "Front Left"; 0x01 -> "Front Right"
                    0x10 -> "Rear Left"; 0x11 -> "Rear Right"; else -> null
                }
                _tpmsState.update { current ->
                    when (position) {
                        0x00 -> current.copy(frontLeft = tireData)
                        0x01 -> current.copy(frontRight = tireData)
                        0x10 -> current.copy(rearLeft = tireData)
                        0x11 -> current.copy(rearRight = tireData)
                        else -> current
                    }
                }
                // Record pressure for trend arrow (Idea J)
                sensorName?.let { TpmsManager.recordPressure(it, pressurePsi) }
            } else if (length == 6) {
                // Command response or state notification from dongle
                val command = frame[3].toInt() and 0xFF
                val position = frame[4].toInt() and 0xFF
                Log.i("TpmsParser", "Command response length 6: command=$command, position=$position")
                if (command == 0x02) { // 0x02 is pairing success!
                    TpmsManager.pairingSuccess(position)
                    Log.i("TpmsParser", "Hardware pairing success ACK received for position $position")
                }
            } else if (length == 9) {
                // Query ID response
                val position = frame[3].toInt() and 0xFF
                val id = String.format("%02X%02X%02X%02X", frame[4], frame[5], frame[6], frame[7])
                Log.i("TpmsParser", "Query ID response for position $position: $id")
            }

            // Discard the processed frame
            repeat(length) { buffer.removeFirst() }
        }
    }
}
