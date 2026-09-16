// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {

    @Test
    fun `watts converts uA and mV magnitudes to W`() {
        assertEquals(15.0, Units.watts(3_000_000, 5_000)!!, 1e-9)
    }

    @Test
    fun `watts is sign-insensitive to current direction`() {
        assertEquals(15.0, Units.watts(-3_000_000, 5_000)!!, 1e-9)
    }

    @Test
    fun `watts is null when currentRaw is null`() {
        assertNull(Units.watts(null, 5_000))
    }

    @Test
    fun `watts is null when voltageRawMv is null`() {
        assertNull(Units.watts(3_000_000, null))
    }

    @Test
    fun `watts with a scale multiplies the raw current before converting`() {
        assertEquals(Units.watts(350_000L, 4_000), Units.watts(350L, 4_000, CurrentScale.MILLI_AMP))
        assertEquals(Units.watts(350_000L, 4_000), Units.watts(350_000L, 4_000, CurrentScale.MICRO_AMP))
        assertEquals(null, Units.watts(null, 4_000, CurrentScale.MILLI_AMP))
    }

    @Test
    fun `tempC converts deci-degrees to degrees`() {
        assertEquals(31.2, Units.tempC(312)!!, 1e-9)
    }

    @Test
    fun `tempC is null when tempDeciC is null`() {
        assertNull(Units.tempC(null))
    }

    @Test
    fun `signedWatts keeps the raw sign on a charging-positive gauge`() {
        assertEquals(15.0, Units.signedWatts(3_000_000, 5_000, CurrentScale.MICRO_AMP, chargingPositive = true)!!, 1e-9)
        assertEquals(-15.0, Units.signedWatts(-3_000_000, 5_000, CurrentScale.MICRO_AMP, chargingPositive = true)!!, 1e-9)
    }

    @Test
    fun `signedWatts flips the raw sign on a charging-negative gauge`() {
        assertEquals(15.0, Units.signedWatts(-3_000_000, 5_000, CurrentScale.MICRO_AMP, chargingPositive = false)!!, 1e-9)
        assertEquals(-15.0, Units.signedWatts(3_000_000, 5_000, CurrentScale.MICRO_AMP, chargingPositive = false)!!, 1e-9)
    }

    @Test
    fun `signedWatts is null when the convention is unknown, never a guessed sign`() {
        assertNull(Units.signedWatts(3_000_000, 5_000, CurrentScale.MICRO_AMP, chargingPositive = null))
        assertNull(Units.signedWatts(-3_000_000, 5_000, CurrentScale.MICRO_AMP, chargingPositive = null))
    }

    @Test
    fun `signedWatts applies the scale and is null on a missing reading`() {
        assertEquals(-1.4, Units.signedWatts(-350L, 4_000, CurrentScale.MILLI_AMP, chargingPositive = true)!!, 1e-9)
        assertNull(Units.signedWatts(null, 4_000, CurrentScale.MICRO_AMP, chargingPositive = true))
        assertNull(Units.signedWatts(3_000_000, null, CurrentScale.MICRO_AMP, chargingPositive = true))
    }

    @Test
    fun `watts stays sign-insensitive beside signedWatts`() {
        assertEquals(Units.watts(-3_000_000, 5_000), Units.watts(3_000_000, 5_000))
    }

    @Test
    fun `signedWatts never returns negative zero`() {
        val onNegativeGauge = Units.signedWatts(0L, 4_000, CurrentScale.MICRO_AMP, chargingPositive = false)!!
        val zeroVolts = Units.signedWatts(-3_000_000, 0, CurrentScale.MICRO_AMP, chargingPositive = true)!!
        assertEquals("0.0", String.format(java.util.Locale.US, "%.1f", onNegativeGauge))
        assertEquals("0.0", String.format(java.util.Locale.US, "%.1f", zeroVolts))
        assertEquals(0.0, onNegativeGauge, 0.0)
        assertEquals(0.0, zeroVolts, 0.0)
    }
}

class UnitsLevelPctTest {

    @Test
    fun `levelPct passes a percent-scaled level through unchanged`() {
        assertEquals(87, Units.levelPct(87, 100))
    }

    @Test
    fun `levelPct passes a level with no scale through unchanged`() {
        assertEquals(87, Units.levelPct(87, null))
    }

    @Test
    fun `levelPct normalises a non-percent scale to the nearest percent`() {
        assertEquals(50, Units.levelPct(500, 1000))
        assertEquals(33, Units.levelPct(333, 1000))
        assertEquals(34, Units.levelPct(335, 1000))
        assertEquals(100, Units.levelPct(1000, 1000))
    }

    @Test
    fun `levelPct leaves a level alone under a scale it cannot divide by`() {
        assertEquals(87, Units.levelPct(87, 0))
        assertEquals(87, Units.levelPct(87, -1))
    }

    @Test
    fun `levelPct is null when the level is null`() {
        assertNull(Units.levelPct(null, 1000))
    }
}
