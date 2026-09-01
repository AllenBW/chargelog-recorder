// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.RawLine

/** BatteryManager returns MIN_VALUE for unsupported properties — that is absence, not data. */
object Sentinels {
    fun intOrNull(v: Int): Int? = if (v == Int.MIN_VALUE) null else v
    fun longOrNull(v: Long): Long? = if (v == Long.MIN_VALUE) null else v
}

/**
 * Persistence gate: fuel gauges refresh slower than we tick, and persisting
 * duplicate readings fabricates data. A sample is persisted
 * only when the gauge tuple changed; an interval > gapFactor * tickMs is
 * recorded as an explicit gap event so silence is never mistaken for data.
 */
class SampleGate(private val gapFactor: Long = 3) {

    sealed interface Decision {
        data class Persist(val sample: RawLine.Sample, val gap: RawLine.Event?) : Decision
        data class Skip(val gap: RawLine.Event?) : Decision
    }

    private var last: RawLine.Sample? = null

    fun offer(s: RawLine.Sample, tickMs: Long): Decision {
        val prev = last
        val gap = prev?.let {
            val dt = s.e - it.e
            if (dt > gapFactor * tickMs) {
                RawLine.Event(t = s.t, e = s.e, kind = EventKinds.GAP, detail = "elapsedGapMs=$dt")
            } else null
        }
        val changed = prev == null || gaugeTuple(prev) != gaugeTuple(s)
        last = s
        return if (changed) Decision.Persist(s, gap) else Decision.Skip(gap)
    }

    fun reset() { last = null }

    private fun gaugeTuple(s: RawLine.Sample) = listOf<Any?>(
        s.currentRaw, s.chargeCounterRaw, s.voltageRaw, s.tempDeciC, s.level,
        s.scale, s.status, s.plugged, s.maxChargingCurrentRaw,
        s.maxChargingVoltageRaw, s.thermalStatus, s.screenOn, s.hingeDeg,
    )
}
