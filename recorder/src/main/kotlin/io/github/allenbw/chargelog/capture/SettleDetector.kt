// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.measure.CurrentScale
import io.github.allenbw.chargelog.measure.Units
import kotlin.math.min

/**
 * The terminal-state rule: a charge has SETTLED after [holdMs] of continuous
 * "holding" samples — status FULL, or NOT_CHARGING at the target, or level pinned at its running
 * max (≥ min(target, 95)) with trickle current — and RESUMES when real charging returns below the
 * max. A pause at low level (thermal) never holds, because none of the three predicates admit it.
 * Pure and pump-confined: owned by [SessionStateMachine], fed every persisted-or-not tick.
 *
 * @param scale the gauge's current scale, so `w` is read in real watts. **Today it is
 *   threshold-neutral, by construction**: every decision compares `w` against a fraction of
 *   `peakW`, and `peakW` is accumulated from the same scaled `w`, so a positive constant
 *   multiplier cancels out of both `w <= trickleFraction * peakW` and `w > resumeFraction * peakW`.
 *   It is kept because it is the right shape for `SamplerProfile.currentScale`, and because a
 *   future absolute floor (a milliwatt threshold rather than a fraction) could not be expressed
 *   without it. Nothing in the suite discriminates on it today, so do not read a passing test
 *   here as evidence that the scale is being honoured.
 */
class SettleDetector(
    private val holdMs: Long = 120_000L,
    private val trickleFraction: Double = 0.15,
    private val resumeFraction: Double = 0.30,
    private val scale: CurrentScale = CurrentScale.MICRO_AMP,
) {
    enum class Transition { SETTLED, RESUMED }

    var settled: Boolean = false
        private set
    private var peakW = 0.0
    private var maxLevel = -1
    private var holdSinceE: Long? = null

    fun reset() { settled = false; peakW = 0.0; maxLevel = -1; holdSinceE = null }

    fun offer(sample: RawLine.Sample, targetLevel: Int): Transition? {
        val w = Units.watts(sample.currentRaw, sample.voltageRaw, scale)
        if (w != null && w > peakW) peakW = w
        val level = sample.level
        if (level != null && level > maxLevel) maxLevel = level

        if (!settled) {
            val holdLevel = min(targetLevel, 95)
            val atTarget = level != null && level >= targetLevel - 1
            val pinned = level != null && level >= holdLevel && level == maxLevel
            val trickle = w != null && peakW > 0.0 && w <= trickleFraction * peakW
            val holding = sample.status == BatteryStatus.FULL ||
                (sample.status == BatteryStatus.NOT_CHARGING && atTarget) ||
                (pinned && trickle)
            if (!holding) { holdSinceE = null; return null }
            val since = holdSinceE ?: sample.e.also { holdSinceE = it }
            if (sample.e - since < holdMs) return null
            settled = true; holdSinceE = null
            return Transition.SETTLED
        }
        val resumed = sample.status == BatteryStatus.CHARGING &&
            w != null && w > resumeFraction * peakW &&
            level != null && level < maxLevel
        if (!resumed) return null
        settled = false
        return Transition.RESUMED
    }
}
