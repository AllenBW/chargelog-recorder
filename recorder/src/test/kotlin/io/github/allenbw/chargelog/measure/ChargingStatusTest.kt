// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingStatusTest {

    @Test fun `the five values are the HAL's BatteryChargingState numbering`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            listOf(ChargingStatus.INVALID, ChargingStatus.NORMAL, ChargingStatus.TOO_COLD, ChargingStatus.TOO_HOT, ChargingStatus.LONG_LIFE, ChargingStatus.ADAPTIVE),
        )
    }
}
