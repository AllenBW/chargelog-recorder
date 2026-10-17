// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.capture.BatteryStatus
import io.github.allenbw.chargelog.data.SampleEntity
import kotlin.math.abs

/**
 * Whether a phone's gauge reports current in mA, from the session's own samples — the phone half
 * of the scale probe, used both while recording ([GaugeScaleProbe]) and when reading a
 * `gauge-phone` log that carries no `gauge_scale` event ([speaksMilliAmps]).
 *
 * A watch is judged by the median alone ([GaugeProfiles.plausibility]). A phone cannot be: a full
 * phone's µA trickle reads under [GaugeProfiles.MA_DETECT_RAW_MAX] too, and an mA verdict on a µA
 * gauge is persisted and reads every later session a thousand times HIGH. So a phone's verdict
 * needs two independent answers to agree.
 *
 * 1. **The median**, over the first [GaugeScaleProbe.SAMPLES] samples that are genuinely charging
 *    — status `CHARGING`, level under [MAX_LEVEL], a non-zero current. A median at or above the
 *    threshold settles it as µA, at once.
 * 2. **The level.** Read as µA, the same samples' integrated current must be able to explain the
 *    level's rise. Once the level has climbed [MIN_LEVEL_RISE] points, a µA reading that delivered
 *    less than a [MARGIN]th of what the rise needs on a [SMALLEST_CELL_MAH] mAh cell is
 *    physically impossible, so the current is mA. A hold — Adaptive, a charge limit, a thermal
 *    pause — never raises the level, so however small its current it is never evidence.
 *
 * On every real µA phone recording in the corpus no 15-sample charging window has a median under
 * 611,000 µA, a hundred times the threshold. A µA phone fails rule 2 at least tenfold even on a
 * 500 mAh cell and eighty-fold on a 4,000 mAh one; an mA phone filling a 4,000 mAh cell clears it
 * about sixfold at the first two points, and by more with every point after.
 */
class PhoneScaleEvidence {

    companion object {
        /** Charging at or above this level is too close to full to be evidence. */
        const val MAX_LEVEL = 90

        /** Level points the charge must climb before rule 2 can answer; a one-point step can be
         *  a rounding of almost no charge. */
        const val MIN_LEVEL_RISE = 2

        /** The smallest phone cell the rule allows for, in mAh — below any phone shipped. */
        const val SMALLEST_CELL_MAH = 500

        /** How far a µA reading must fall short of the level's rise to be called impossible. */
        const val MARGIN = 10

        private const val MICRO_AMP_MS_PER_MAH = 3_600_000_000.0

        private const val PERCENT = 100.0

        /** Whether [samples], one `gauge-phone` session in recording order, prove an mA gauge. */
        fun speaksMilliAmps(samples: List<SampleEntity>): Boolean {
            val evidence = PhoneScaleEvidence()
            return samples.any { evidence.offer(it.elapsedRealtimeMs, it.currentRaw, it.level, it.status) == true }
        }
    }

    private val window = ArrayList<Long>(GaugeScaleProbe.SAMPLES)
    private var firstLevel = -1
    private var lastE = 0L
    private var lastMagnitude = 0L
    private var microAmpMs = 0.0
    private var verdict: Boolean? = null

    /**
     * Offers one sample. Returns `true` once the evidence proves mA, `false` once it proves µA, and
     * null while it cannot say — which is every answer a session that never charges below
     * [MAX_LEVEL] gets. A verdict, once given, is returned again for every later sample.
     */
    fun offer(eMs: Long, currentRaw: Long?, levelPct: Int?, status: Int?): Boolean? {
        if (verdict != null) return verdict
        if (status != BatteryStatus.CHARGING || levelPct == null || levelPct >= MAX_LEVEL) return null
        if (currentRaw == null || currentRaw == 0L) return null
        if (firstLevel >= 0) microAmpMs += lastMagnitude * (eMs - lastE).coerceAtLeast(0L).toDouble()
        if (firstLevel < 0) firstLevel = levelPct
        lastE = eMs
        lastMagnitude = abs(currentRaw)
        if (window.size < GaugeScaleProbe.SAMPLES) {
            window += lastMagnitude
            if (window.size < GaugeScaleProbe.SAMPLES) return null
            if (!GaugeProfiles.medianReadsMilliAmps(window)) verdict = false
        }
        if (verdict == null && levelRiseIsImpossibleInMicroAmps(levelPct - firstLevel)) verdict = true
        return verdict
    }

    private fun levelRiseIsImpossibleInMicroAmps(rise: Int): Boolean {
        if (rise < MIN_LEVEL_RISE) return false
        val deliveredMah = microAmpMs / MICRO_AMP_MS_PER_MAH
        val neededMah = (rise - 1) / PERCENT * SMALLEST_CELL_MAH
        return deliveredMah * MARGIN < neededMah
    }
}
