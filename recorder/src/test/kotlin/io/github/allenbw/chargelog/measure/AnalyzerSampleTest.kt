// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.data.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyzerSampleTest {

    @Test
    fun `fully-populated SampleEntity maps all fields to AnalyzerSample`() {
        val entity = SampleEntity(
            sessionId = 1001L,
            wallClockMs = 2002L,
            elapsedRealtimeMs = 3003L,
            currentRaw = 4004L,
            chargeCounterRaw = 5005L,
            voltageRaw = 6006,
            voltageAgeMs = 7007L,
            tempDeciC = 8008,
            level = 9009,
            status = 10010,
            plugged = 11011,
            maxChargingCurrentRaw = 12012,
            maxChargingVoltageRaw = 13013,
            thermalStatus = 14014,
            screenOn = true,
            hingeDeg = 15015.5f,
        )

        val sample = entity.toAnalyzerSample()

        assertEquals(3003L, sample.eMs)
        assertEquals(2002L, sample.wallClockMs)
        assertEquals(4004L, sample.currentRaw)
        assertEquals(6006, sample.voltageRaw)
        assertEquals(9009, sample.level)
        assertEquals(10010, sample.status)
        assertEquals(11011, sample.plugged)
        assertEquals(12012, sample.maxChargingCurrentRaw)
        assertEquals(13013, sample.maxChargingVoltageRaw)
        assertEquals(8008, sample.tempDeciC)
        assertEquals(15015.5f, sample.hingeDeg!!, 0.001f)
        assertEquals(true, sample.screenOn)
        assertEquals(14014, sample.thermalStatus)
    }

    @Test
    fun `null-heavy SampleEntity maps nulls through to AnalyzerSample`() {
        val entity = SampleEntity(
            sessionId = 1L,
            wallClockMs = 2000L,
            elapsedRealtimeMs = 3000L,
            currentRaw = null,
            chargeCounterRaw = null,
            voltageRaw = null,
            voltageAgeMs = null,
            tempDeciC = null,
            level = null,
            status = null,
            plugged = null,
            maxChargingCurrentRaw = null,
            maxChargingVoltageRaw = null,
            thermalStatus = null,
            screenOn = null,
            hingeDeg = null,
        )

        val sample = entity.toAnalyzerSample()

        assertEquals(3000L, sample.eMs)
        assertEquals(2000L, sample.wallClockMs)
        assertNull(sample.currentRaw)
        assertNull(sample.voltageRaw)
        assertNull(sample.level)
        assertNull(sample.status)
        assertNull(sample.plugged)
        assertNull(sample.maxChargingCurrentRaw)
        assertNull(sample.maxChargingVoltageRaw)
        assertNull(sample.tempDeciC)
        assertNull(sample.hingeDeg)
        assertNull(sample.screenOn)
        assertNull(sample.thermalStatus)
    }
}
