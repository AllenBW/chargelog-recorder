// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

/** How the gauge reports current. Raw values stay raw in the log; the scale applies at read time. */
enum class CurrentScale(val toMicroAmps: Double) { MICRO_AMP(1.0), MILLI_AMP(1_000.0) }

/**
 * The **recording** half of a gauge's identity: the three facts the recorder itself needs, and
 * nothing else. Selected once per session by [GaugeProfiles.forDevice], written into the header as
 * `gaugeProfileId`, and re-resolved by id at read time ([GaugeProfiles.byId]).
 *
 * A gauge also has a *reading* half — a noise floor, a smoothing window, an expected cadence, a
 * design-capacity range, a support tier and a display name. Those are calibration constants for
 * whatever analyzes a session, not for the instrument that records it: no code in this module
 * reads them, and measuring them takes the hardware in hand. They live in the host, keyed by the
 * same [id], and this module deliberately does not name them. A host that ships its own analysis
 * keeps its own table; a host that does not, does not need one.
 *
 * Pure: no Android imports; the caller passes `Build.MANUFACTURER` / `Build.MODEL` strings in.
 *
 * @property id the table key. It travels in the session header and is the only handle a reader
 *   has on the gauge afterwards, so it is stable: a changed characterization gets a NEW id (see
 *   [GaugeProfiles.UNKNOWN_MA]), never a redefined one.
 * @property currentScale whether this gauge's raw current is µA or mA — applied at read time by
 *   [Units.watts], so raw values stay raw in the log.
 * @property counterKind `"COULOMB"` or `"SOC_DERIVED"` — the header's `Capabilities.counterKind`
 *   vocabulary, carried as a plain string and pinned to it by a test — or null when unknown
 *   (treated as absent for health arithmetic).
 * @property chargingPositive whether a positive raw current means charge flowing INTO the battery,
 *   declared only where the gauge itself was measured: `true` on the Pixel Watch 5 (1,914 of 1,927
 *   charging samples positive, projection DB pulled 2026-09-08); null everywhere else, which
 *   [Units.signedWatts] turns into no signed number rather than a guessed sign. No phone profile
 *   declares one. `gauge-phone` once said `true` from the owner's own Pixel and a tester's phone
 *   proved it a property of the device, not of "a phone": 7,446 of its 7,464 samples negative
 *   while the counter rose. A phone's convention is read from each session's own evidence instead
 *   ([SignEvidence], applied by [sessionFacts]). Defaulted, so a host constructing or copying a
 *   profile keeps compiling (CONTRIBUTING.md's seam rule).
 */
data class GaugeProfile(
    val id: String,
    val currentScale: CurrentScale,
    val counterKind: String?,
    val chargingPositive: Boolean? = null,
)

object GaugeProfiles {

    /** Charging current below this raw magnitude while `status == CHARGING` means the gauge speaks
     *  mA: no watch cell charges at under 5 mA. */
    const val MA_DETECT_RAW_MAX = 5_000L

    /** The phone's own gauge — a µA coulomb counter, which is what this recorder was written on.
     *  No sign convention: phones disagree, so each session's is derived from its own samples
     *  ([SignEvidence]). A phone whose samples prove mA is refined to [PHONE_MA]. */
    val PHONE = GaugeProfile(id = "gauge-phone", currentScale = CurrentScale.MICRO_AMP, counterKind = "COULOMB")

    /** Google Pixel Watch (Qualcomm SW5100 BMS): µA, and its counter follows the reported level
     *  rather than a real coulomb count. Charging-positive, measured on the Pixel Watch 5 (1,914 of
     *  1,927 charging samples positive, 2026-09-08). */
    val QBG = GaugeProfile(id = "gauge-qbg", currentScale = CurrentScale.MICRO_AMP, counterKind = "SOC_DERIVED", chargingPositive = true)

    /** Samsung `sec_battery`: reports in mA, SOC-derived counter. */
    val SEC = GaugeProfile(id = "gauge-sec", currentScale = CurrentScale.MILLI_AMP, counterKind = "SOC_DERIVED")

    /** Any other watch: level-only until a probe says otherwise; the scale is detected, and flagged. */
    val UNKNOWN = GaugeProfile(id = "gauge-unknown", currentScale = CurrentScale.MICRO_AMP, counterKind = null)

    /** [UNKNOWN] with the mA scale [plausibility] detected — a table entry of its own so the header's
     *  `gaugeProfileId` records the detected scale and [byId] resolves it back at read time. Never
     *  returned by [forDevice]: nothing about a manufacturer implies the scale, only the samples do. */
    val UNKNOWN_MA = UNKNOWN.copy(id = "gauge-unknown-ma", currentScale = CurrentScale.MILLI_AMP)

    /** [PHONE] with the mA scale [PhoneScaleEvidence] proved — a Samsung-style phone gauge. A table
     *  entry of its own for the reason [UNKNOWN_MA] is one: the header's id must carry the measured
     *  scale back through [byId]. Never returned by [forDevice]. */
    val PHONE_MA = PHONE.copy(id = "gauge-phone-ma", currentScale = CurrentScale.MILLI_AMP)

    val all: List<GaugeProfile> = listOf(PHONE, QBG, SEC, UNKNOWN, UNKNOWN_MA, PHONE_MA)

    fun byId(id: String?): GaugeProfile? = id?.let { wanted -> all.firstOrNull { it.id == wanted } }

    /** Bundled-table selection. Phones always get [PHONE] (the scale probe may refine it to
     *  [PHONE_MA]); watches by manufacturer. */
    fun forDevice(manufacturer: String, model: String, isWatch: Boolean): GaugeProfile {
        if (!isWatch) return PHONE
        return when (manufacturer.trim().lowercase()) {
            "google" -> QBG
            "samsung" -> SEC
            else -> UNKNOWN
        }
    }

    /**
     * The first-samples plausibility check: an UNKNOWN gauge whose charging current magnitude reads
     * under [MA_DETECT_RAW_MAX] is speaking mA, so the profile becomes [UNKNOWN_MA] — **the id
     * changes so the header records the detected scale and read time resolves it** ([byId] → mA).
     * An off-table `copy(currentScale = MILLI_AMP)` that kept the id `gauge-unknown` would be read
     * back through the table as µA, i.e. 1000× low — the exact failure the scale field exists to
     * prevent, moved from record time to read time. Measured profiles are never second-guessed: a
     * probe outranks a heuristic.
     *
     * [PHONE] is not judged here: a full phone's µA trickle reads under the threshold too (the
     * corpus's own Pixel holds at 100 % have medians of 1,823 to 4,948 µA), so a phone needs the
     * corroborated rule in [PhoneScaleEvidence].
     */
    fun plausibility(currentRawWhileCharging: List<Long>, selected: GaugeProfile): GaugeProfile {
        if (selected !== UNKNOWN || currentRawWhileCharging.isEmpty()) return selected
        return if (medianReadsMilliAmps(currentRawWhileCharging)) UNKNOWN_MA else selected
    }

    internal fun medianReadsMilliAmps(currentRaw: List<Long>): Boolean {
        val magnitudes = currentRaw.map { kotlin.math.abs(it) }.sorted()
        return magnitudes[magnitudes.size / 2] in 1 until MA_DETECT_RAW_MAX
    }
}
