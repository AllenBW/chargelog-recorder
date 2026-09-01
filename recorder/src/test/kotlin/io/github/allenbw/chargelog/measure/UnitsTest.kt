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
}
