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

    /**
     * Watts WITH a sign: positive is charge flowing into the battery, negative is the battery
     * running the device — the "plugged in but draining" case (a wired plug-in whose current ran
     * the wrong way for 19 s while the OS said charging) that [watts], being sign-insensitive by
     * design, renders as a healthy-looking number.
     *
     * Null when either reading is missing, and null when [chargingPositive] is — an unmeasured
     * convention yields no signed number, never a guessed one. [watts] is untouched: every existing
     * consumer keeps the magnitude it always had.
     */
    fun signedWatts(currentRaw: Long?, voltageRawMv: Int?, scale: CurrentScale, chargingPositive: Boolean?): Double? {
        val positive = chargingPositive ?: return null
        val raw = currentRaw ?: return null
        val magnitude = watts(raw, voltageRawMv, scale) ?: return null
        val rawSign = if (raw < 0) -1.0 else 1.0
        val signed = if (positive) rawSign * magnitude else -rawSign * magnitude
        return if (signed == 0.0) 0.0 else signed
    }

    /** Degrees Celsius from a raw deci-degree reading; null if missing. */
    fun tempC(tempDeciC: Int?): Double? = tempDeciC?.let { it / 10.0 }

    /**
     * A battery level as a percent, from the raw `EXTRA_LEVEL` and the `EXTRA_SCALE` it was
     * reported against. The scale is the level's maximum — 100 on every gauge observed so far
     * (42,607 of 42,607 samples), which is what every level downstream assumes — so a different
     * one is normalised here, at ingest, to the nearest percent. A scale that cannot divide
     * (null, zero, negative) leaves the level as written rather than inventing a number.
     */
    fun levelPct(level: Int?, scale: Int?): Int? {
        val l = level ?: return null
        if (scale == null || scale <= 0 || scale == 100) return l
        return Math.round(l * 100.0 / scale).toInt()
    }
}
