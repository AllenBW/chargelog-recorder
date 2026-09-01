// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.measure.AnalyzerSample

/**
 * Adapts a live [RawLine.Sample] to an [AnalyzerSample] for analytics processing.
 *
 * This is `capture/`'s own copy of `data/Replay.kt`'s `RawLine.Sample -> SampleEntity` mapping
 * (see `measure/AnalyzerSample.kt`'s `SampleEntity.toAnalyzerSample()`): both drop `scale`, which
 * `AnalyzerSample` has no slot for, and both map `t -> wallClockMs` / `e -> elapsedRealtimeMs`.
 * It lives here, not in `measure/`, so that `measure/` never imports `capture/` — the dependency
 * runs one way only (`capture/` already imports `measure.Units`), keeping `capture/` from
 * depending on anything outside the recorder.
 */
fun RawLine.Sample.toAnalyzerSample(): AnalyzerSample =
    AnalyzerSample(
        eMs = e,
        wallClockMs = t,
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
