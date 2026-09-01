// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.data.SampleEntity

/**
 * A neutral projection of one sample for analysis: the same shape whether the sample was read
 * back from the database ([SampleEntity], via [toAnalyzerSample]) or is arriving live from the
 * recorder (`RawLine.Sample`, via `capture/AnalyzerAdapters.kt`), so an analysis written against
 * this type serves a recorded session and an in-progress one identically.
 *
 * `SampleEntity`'s `chargeCounterRaw`, `voltageAgeMs`, and `sessionId` are deliberately not
 * carried — a consumer that needs them reads the entity.
 */
data class AnalyzerSample(
    val eMs: Long,                       // elapsedRealtimeMs
    val wallClockMs: Long,
    val currentRaw: Long?,
    val voltageRaw: Int?,
    val level: Int?,
    val status: Int?,
    val plugged: Int?,
    val maxChargingCurrentRaw: Int?,
    val maxChargingVoltageRaw: Int?,
    val tempDeciC: Int?,
    val hingeDeg: Float?,
    val screenOn: Boolean?,
    val thermalStatus: Int?,
)

/**
 * Adapts a [SampleEntity] to an [AnalyzerSample] for analytics processing.
 */
fun SampleEntity.toAnalyzerSample(): AnalyzerSample =
    AnalyzerSample(
        eMs = elapsedRealtimeMs,
        wallClockMs = wallClockMs,
        currentRaw = currentRaw,
        voltageRaw = voltageRaw,
        level = level,
        status = status,
        plugged = plugged,
        maxChargingCurrentRaw = maxChargingCurrentRaw,
        maxChargingVoltageRaw = maxChargingVoltageRaw,
        tempDeciC = tempDeciC,
        hingeDeg = hingeDeg,
        screenOn = screenOn,
        thermalStatus = thermalStatus,
    )
