package com.example.tpms.ui.main

import com.example.tpms.TpmsParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TpmsParserTest {

    @Test
    fun initialState_isEmpty() = runTest {
        val parser = TpmsParser()
        val state = parser.tpmsState.first()
        assertNull(state.frontLeft)
        assertNull(state.frontRight)
        assertNull(state.rearLeft)
        assertNull(state.rearRight)
    }

    @Test
    fun parseValidFrame() = runTest {
        val parser = TpmsParser()
        // 55 AA 08 01 47 47 00 F6 — Front Right, 71*0.5=35.5 PSI, 71-50=21°C, status=00
        val frame = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(), 0x08.toByte(), 0x01.toByte(),
            0x47.toByte(), 0x47.toByte(), 0x00.toByte(), 0xF6.toByte()
        )
        parser.processBytes(frame)
        val state = parser.tpmsState.first()
        val tire = state.frontRight
        assertNotNull(tire)
        assertEquals(35.5, tire!!.pressurePsi, 0.01)
        assertEquals(21, tire.temperatureC)
        assertEquals(0, tire.status)
        assertFalse(tire.isLowBattery)
    }

    @Test
    fun badChecksum_isRejected() = runTest {
        val parser = TpmsParser()
        // Corrupt the checksum byte
        val frame = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(), 0x08.toByte(), 0x01.toByte(),
            0x47.toByte(), 0x47.toByte(), 0x00.toByte(), 0x00.toByte() // bad checksum
        )
        parser.processBytes(frame)
        val state = parser.tpmsState.first()
        assertNull(state.frontRight)
    }

    @Test
    fun lowBatteryFlag_detectedViaBitmask() = runTest {
        val parser = TpmsParser()
        // Status = 0x01 (low battery), XOR: 55^AA^08^01^47^47^01 = F7
        val frame = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(), 0x08.toByte(), 0x01.toByte(),
            0x47.toByte(), 0x47.toByte(), 0x01.toByte(), 0xF7.toByte()
        )
        parser.processBytes(frame)
        val state = parser.tpmsState.first()
        assertNotNull(state.frontRight)
        assertTrue(state.frontRight!!.isLowBattery)
    }

    @Test
    fun combinedAlarmAndBattery_detectedViaBitmask() = runTest {
        val parser = TpmsParser()
        // Status = 0x09 (0x08 alarm | 0x01 low battery), XOR: 55^AA^08^01^47^47^09 = FF
        val frame = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(), 0x08.toByte(), 0x01.toByte(),
            0x47.toByte(), 0x47.toByte(), 0x09.toByte(), 0xFF.toByte()
        )
        parser.processBytes(frame)
        val state = parser.tpmsState.first()
        assertNotNull(state.frontRight)
        assertTrue(state.frontRight!!.isLowBattery)
        assertEquals(0x09, state.frontRight!!.status) // status preserved raw
    }

    @Test
    fun skipsGarbageBeforeFrame() = runTest {
        val parser = TpmsParser()
        val data = byteArrayOf(
            0x00, 0xFF.toByte(), 0x00, // garbage
            0x55.toByte(), 0xAA.toByte(), 0x08.toByte(), 0x01.toByte(),
            0x47.toByte(), 0x47.toByte(), 0x00.toByte(), 0xF6.toByte()
        )
        parser.processBytes(data)
        val state = parser.tpmsState.first()
        assertNotNull(state.frontRight)
        assertEquals(35.5, state.frontRight!!.pressurePsi, 0.01)
    }
}
