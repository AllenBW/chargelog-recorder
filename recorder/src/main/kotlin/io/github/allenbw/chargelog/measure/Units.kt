// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import kotlin.math.abs

/**
 * Physical conversions from RAW stored gauge values (raw at write, transform at read). All
 * values are battery-side and uncalibrated — label accordingly wherever they are shown.
 * Display formatting lives in `Format`.
 */
object Units {
    /** Watts from a raw µA current and a raw mV voltage; sign-insensitive; null if either is
     *  missing. */
    fun watts(currentRaw: Long?, voltageRawMv: Int?): Double? {
        val ua = currentRaw ?: return null
        val mv = voltageRawMv ?: return null
        return abs(ua) / 1_000_000.0 * (mv / 1_000.0)
    }

    /** [watts] for a gauge whose raw current is in [scale] units; the two-argument form is the
     *  µA convention a phone gauge uses and stays byte-for-byte what it was. */
    fun watts(currentRaw: Long?, voltageRawMv: Int?, scale: CurrentScale): Double? {
        val raw = currentRaw ?: return null
        val mv = voltageRawMv ?: return null
        return abs(raw * scale.toMicroAmps) / 1_000_000.0 * (mv / 1_000.0)
    }

    /** Degrees Celsius from a raw deci-degree reading; null if missing. */
    fun tempC(tempDeciC: Int?): Double? = tempDeciC?.let { it / 10.0 }
}
