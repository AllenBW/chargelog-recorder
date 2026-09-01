// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.data.SampleEntity
import io.github.allenbw.chargelog.data.SessionEntity
import io.github.allenbw.chargelog.data.isWatch

enum class ChargeSource { WIRED, WIRELESS, DOCK, UNKNOWN }

data class SessionFacts(
    val durationMs: Long?, val startLevel: Int?, val endLevel: Int?,
    val energyAh: Double?, val peakW: Double?, val maxTempC: Double?,
    val source: ChargeSource,
)

fun sessionFacts(session: SessionEntity, samples: List<SampleEntity>): SessionFacts {
    val scale = GaugeProfiles.byId(session.gaugeProfileId)?.currentScale ?: CurrentScale.MICRO_AMP
    val peak = samples.mapNotNull { Units.watts(it.currentRaw, it.voltageRaw, scale) }.maxOrNull()
    val maxT = samples.mapNotNull { Units.tempC(it.tempDeciC) }.maxOrNull()
    val ah = if (session.startChargeCounterRaw != null && session.endChargeCounterRaw != null)
        (session.endChargeCounterRaw - session.startChargeCounterRaw) / 1_000_000.0 else null
    val source = chargeSourceOf(samples.firstNotNullOfOrNull { it.plugged }, isWatch = session.isWatch)
    return SessionFacts(
        durationMs = session.endedAtMs?.let { it - session.startedAtMs },
        startLevel = session.startLevel, endLevel = session.endLevel,
        energyAh = ah, peakW = peak, maxTempC = maxT, source = source,
    )
}

/** Raw `EXTRA_PLUGGED` → [ChargeSource]: the one mapping shared by
 *  [sessionFacts], the live per-sample source row, and the recording
 *  notification's source word. On a watch the plug value is not trusted for the label — pogo
 *  docks enumerate as AC/USB, cradles as WIRELESS — so the source is always
 *  [ChargeSource.DOCK] and the raw `plugged` stays in the sample. */
fun chargeSourceOf(plugged: Int?, isWatch: Boolean = false): ChargeSource {
    if (isWatch) return ChargeSource.DOCK
    return when (plugged) {
        1, 2 -> ChargeSource.WIRED
        4 -> ChargeSource.WIRELESS
        8 -> ChargeSource.DOCK
        else -> ChargeSource.UNKNOWN
    }
}

/** Below this the `@hide` `max_charging_current` extra is noise, not a negotiation — the
 *  original Pixel Watch reports a bogus 300 µA. */
const val MIN_PLAUSIBLE_NEGOTIATED_UA = 50_000

/** Negotiated charger power in watts from `BatteryManager.EXTRA_MAX_CHARGING_*`
 *  — both raw fields are micro-units (µA, µV), unlike the sampled
 *  current/voltage pair which mixes µA with mV. Null if either is absent, or if
 *  the current is below [MIN_PLAUSIBLE_NEGOTIATED_UA]. */
fun negotiatedW(maxChargingCurrentRaw: Int?, maxChargingVoltageRaw: Int?): Double? {
    val ua = maxChargingCurrentRaw ?: return null
    val uv = maxChargingVoltageRaw ?: return null
    if (ua < MIN_PLAUSIBLE_NEGOTIATED_UA) return null
    return ua / 1_000_000.0 * (uv / 1_000_000.0)
}
