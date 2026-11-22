// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.measure.CurrentScale
import io.github.allenbw.chargelog.measure.Units
import kotlin.math.min

private const val FLAT_HOLD_MS = 30 * 60_000L

/**
 * The terminal-state rule. A charge has SETTLED after [holdMs] of continuous "holding" samples —
 * status FULL, or NOT_CHARGING at the target, or level pinned at its running max
 * (≥ min(target, 95)) with trickle current — and RESUMES when real charging returns below the
 * max, or when the level rises above the level it settled at while still CHARGING. That rise is
 * proof the pinned predicate settled early on this charge (a top-of-charge level increment can
 * outlast [holdMs]), so after it the pinned predicate is not trusted again until [reset]: only a
 * status can settle the charge from then on.
 *
 * A charge also settles when nothing the platform broadcasts about it — level, status, plug type
 * and charging status — has changed for [flatHoldMs], whatever the level and the target. That is a
 * charge the phone holds short of the user's target while still reporting it charging (an OEM
 * charge limit, an Adaptive Charging hold), which none of the three predicates admit. A charge
 * settled this way resumes on the first sample in which any of those four differs, as well as on
 * the two resumes above, and never withdraws trust in the pinned predicate. Every resume starts the
 * [flatHoldMs] again, even one in which none of the four changed, and so does [reset], so a charge
 * plugged in again exactly as the last one ended waits its own. A pause at a low level (thermal)
 * settles only if it outlasts [flatHoldMs].
 *
 * Pure and pump-confined: owned by [SessionStateMachine], fed every persisted-or-not tick.
 *
 * @param flatHoldMs how long a charge must sit unchanged to settle as a hold: longer than any
 *   level step of a charge that is still climbing, so a slow charge is never mistaken for one.
 *   `docs/recording-service-threading.md`, "A hold below the target", has the evidence.
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
    private val flatHoldMs: Long = FLAT_HOLD_MS,
) {
    enum class Transition { SETTLED, RESUMED }

    private data class Broadcast(val level: Int, val status: Int?, val plugged: Int?, val chargingStatus: Int?)

    var settled: Boolean = false
        private set
    private var peakW = 0.0
    private var maxLevel = -1
    private var holdSinceE: Long? = null
    private var settledAtLevel = -1
    private var pinnedTrusted = true
    private var flat: Broadcast? = null
    private var flatSinceE = 0L
    private var settledFlat: Broadcast? = null

    fun reset() {
        settled = false; peakW = 0.0; maxLevel = -1; holdSinceE = null
        settledAtLevel = -1; pinnedTrusted = true
        flat = null; flatSinceE = 0L; settledFlat = null
    }

    fun offer(sample: RawLine.Sample, targetLevel: Int): Transition? {
        val w = Units.watts(sample.currentRaw, sample.voltageRaw, scale)
        if (w != null && w > peakW) peakW = w
        val level = sample.level
        if (level != null && level > maxLevel) maxLevel = level
        val unchangedMs = trackFlat(sample)
        return if (settled) resume(sample, w) else settle(sample, w, targetLevel, unchangedMs)
    }

    private fun trackFlat(sample: RawLine.Sample): Long {
        val now = sample.level?.let { Broadcast(it, sample.status, sample.plugged, sample.chargingStatus) }
        if (now == null || now != flat) {
            flat = now
            flatSinceE = sample.e
        }
        return if (now == null) 0L else sample.e - flatSinceE
    }

    private fun settle(sample: RawLine.Sample, w: Double?, targetLevel: Int, unchangedMs: Long): Transition? {
        holdSinceE = if (holding(sample, w, targetLevel)) holdSinceE ?: sample.e else null
        val held = holdSinceE?.let { sample.e - it >= holdMs } == true
        if (!held && unchangedMs < flatHoldMs) return null
        settled = true
        holdSinceE = null
        settledAtLevel = maxLevel
        settledFlat = if (held) null else flat
        return Transition.SETTLED
    }

    private fun holding(sample: RawLine.Sample, w: Double?, targetLevel: Int): Boolean {
        val level = sample.level
        val atTarget = level != null && level >= targetLevel - 1
        val pinned = pinnedTrusted && level != null && level >= min(targetLevel, 95) && level == maxLevel
        val trickle = w != null && peakW > 0.0 && w <= trickleFraction * peakW
        return sample.status == BatteryStatus.FULL ||
            (sample.status == BatteryStatus.NOT_CHARGING && atTarget) ||
            (pinned && trickle)
    }

    private fun resume(sample: RawLine.Sample, w: Double?): Transition? {
        val level = sample.level
        val charging = sample.status == BatteryStatus.CHARGING && level != null
        val rose = charging && level > settledAtLevel
        val bounced = charging && w != null && w > resumeFraction * peakW && level < maxLevel
        val moved = settledFlat != null && flat != settledFlat
        if (!rose && !bounced && !moved) return null
        if (rose && settledFlat == null) pinnedTrusted = false
        settled = false
        settledFlat = null
        flatSinceE = sample.e
        return Transition.RESUMED
    }
}
