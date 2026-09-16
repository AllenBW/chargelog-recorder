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
    /**
     * The gauge's counter kind for this session (`CounterKinds`), carried so a consumer can tell
     * whether [energyAh] is a measurement or arithmetic.
     *
     * On a `SOC_DERIVED` gauge the charge counter IS capacity x state-of-charge, so an
     * energy-over-level-gain quotient cancels to the declared capacity constant no matter what
     * the cell did. A consumer that divides [energyAh] by a level delta and calls the result
     * measured is wrong on exactly those devices — see `BatteryHealth.CapacityBasis`.
     *
     * Resolved the way the current scale is, two lines below: the header's declaration when it
     * made one, else the gauge catalog's kind for this session's profile. A schema-1 header
     * predates the capability block and declares nothing — on the owner's own phone 3 of 42
     * sessions are such rows — and reading that absence as "unknown" made every phone estimate
     * they contributed to non-unanimous, which the health screen then presented as a gauge-declared
     * constant. It is a coulomb counter; the catalog has always said so (device pass 2026-09-08).
     *
     * Defaulted so adding it does not break a host that constructs [SessionFacts] itself
     * (`RecorderHost`'s stability rule); null only when neither the header nor the catalog knows.
     */
    val counterKind: String? = null,
    /**
     * Whether a positive raw current on this session's gauge means charge flowing INTO the
     * battery — what [Units.signedWatts] needs to say "plugged in but draining" without guessing.
     * Resolved like [counterKind]: the header's declaration when it made one, else the gauge
     * catalog's measured convention for this session's profile, else null (an unmeasured gauge).
     * Defaulted for the seam's stability rule.
     */
    val chargingPositive: Boolean? = null,
    /**
     * Whether this session's raw currents are µA or mA — the units every `Units.watts` call over
     * these samples has to be given, carried here so a consumer never has to re-derive it.
     *
     * Resolved from the gauge catalog by this session's profile, the way [peakW] above already
     * resolves it; unlike [counterKind] and [chargingPositive] there is no header declaration to
     * prefer, because the scale is a property of the gauge the catalog measures rather than
     * something a recording announces. Falls back to the microamp convention for an unknown or
     * absent profile, which is what a phone gauge is by definition.
     *
     * Non-null, and defaulted for the seam's stability rule. It exists because both consumers that
     * held a [SessionFacts] and needed the scale — Session Detail's efficiency cell and Deep Stats'
     * weekly trend — assumed microamps and read a milliamp gauge 1000x low, measured at 0.02 %
     * against a true 21.8 % on the corpus's Samsung fixtures (audit 2026-09-14).
     */
    val currentScale: CurrentScale = CurrentScale.MICRO_AMP,
)

fun sessionFacts(session: SessionEntity, samples: List<SampleEntity>): SessionFacts {
    val scale = GaugeProfiles.byId(session.gaugeProfileId)?.currentScale ?: CurrentScale.MICRO_AMP
    val peak = samples.mapNotNull { Units.watts(it.currentRaw, it.voltageRaw, scale) }.maxOrNull()
    val maxT = samples.mapNotNull { Units.tempC(it.tempDeciC) }.maxOrNull()
    val ah = if (session.startChargeCounterRaw != null && session.endChargeCounterRaw != null)
        (session.endChargeCounterRaw - session.startChargeCounterRaw) / 1_000_000.0 else null
    val source = chargeSourceOf(
        samples.firstNotNullOfOrNull { it.plugged?.takeIf { p -> p != 0 } },
        isWatch = session.isWatch,
    )
    return SessionFacts(
        durationMs = session.endedAtMs?.let { it - session.startedAtMs },
        startLevel = session.startLevel, endLevel = session.endLevel,
        energyAh = ah, peakW = peak, maxTempC = maxT, source = source,
        counterKind = session.counterKind
            ?: (GaugeProfiles.byId(session.gaugeProfileId) ?: GaugeProfiles.PHONE).counterKind,
        chargingPositive = session.chargingPositive
            ?: (GaugeProfiles.byId(session.gaugeProfileId) ?: GaugeProfiles.PHONE).chargingPositive,
        currentScale = scale,
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
